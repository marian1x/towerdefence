import { randomBytes, timingSafeEqual } from 'node:crypto';

const MAX_PLAYERS = 24;
const INVITE_TIMEOUT_MS = 30_000;
const ROOM_START_TIMEOUT_MS = 45_000;
const ROOM_TICKET_TTL_MS = 8 * 60 * 60 * 1000;
const MAX_ROOM_TICKETS = 96;
const HEARTBEAT_MS = 15_000;
const MODES = new Set(['VERSUS', 'COOP']);

export const KEY_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

export function matchesKey(candidate, key) {
    if (typeof candidate !== 'string' || !key) return false;
    const bytes = Buffer.from(candidate);
    return bytes.length === key.length && timingSafeEqual(bytes, key);
}

export function createLobby(lobbyKey) {
    const secret = lobbyKey ? Buffer.from(lobbyKey) : null;
    const players = new Map();
    const tickets = new Map();

    function send(player, message) {
        if (player.socket.destroyed || player.socket.writableEnded) return;
        if (player.socket.writableLength > 32768) {
            player.socket.destroy();
            return;
        }
        player.socket.write(JSON.stringify(message) + '\n');
    }

    function roster() {
        for (const player of players.values()) {
            send(player, {
                t: 'roster',
                players: Array.from(players.values())
                    .filter(other => other !== player)
                    .map(({ id, name, invite, launching }) => ({ id, name, available: !invite && !launching })),
            });
        }
    }

    function clearInvite(invite) {
        clearTimeout(invite.timer);
        invite.from.invite = null;
        invite.to.invite = null;
        send(invite.from, { t: 'clearInvite' });
        send(invite.to, { t: 'clearInvite' });
    }

    function invitePlayer(player, targetId, mode) {
        const target = players.get(targetId);
        if (!MODES.has(mode) || target === player) return;
        if (!target || player.invite || target.invite || player.launching || target.launching) {
            send(player, { t: 'info', text: 'That player is unavailable.' });
            return;
        }
        const id = randomBytes(8).toString('hex');
        const invite = { id, from: player, to: target, mode };
        invite.timer = setTimeout(() => {
            clearInvite(invite);
            send(player, { t: 'info', text: 'Your invitation expired.' });
            roster();
        }, INVITE_TIMEOUT_MS);
        invite.timer.unref();
        player.invite = target.invite = invite;
        send(player, { t: 'outgoing', id, name: target.name });
        send(target, { t: 'invite', id, name: player.name, mode });
        roster();
    }

    function acceptInvite(invite) {
        for (const [room, ticket] of tickets) {
            if (ticket.expires <= Date.now()) tickets.delete(room);
        }
        const { from, to, mode } = invite;
        clearInvite(invite);
        if (tickets.size >= MAX_ROOM_TICKETS) {
            send(from, { t: 'info', text: 'The server is busy. Try again later.' });
            send(to, { t: 'info', text: 'The server is busy. Try again later.' });
            roster();
            return;
        }
        const room = randomBytes(16).toString('hex');
        const key = randomBytes(32).toString('hex');
        const ticket = {
            key: Buffer.from(key),
            expires: Date.now() + ROOM_TICKET_TTL_MS,
            guest: to,
            mode,
            timer: setTimeout(() => {
                tickets.delete(room);
                from.launching = to.launching = false;
                send(to, { t: 'info', text: 'The host could not open the match.' });
                roster();
            }, ROOM_START_TIMEOUT_MS),
        };
        ticket.timer.unref();
        tickets.set(room, ticket);
        from.launching = to.launching = true;
        send(from, { t: 'match', room, key, mode });
        send(to, { t: 'info', text: 'Opening the match…' });
        roster();
    }

    function handle(player, line) {
        const [command, id, mode, extra] = line.split(' ');
        if (command === 'PING' && id === undefined) return;
        if (command === 'INVITE' && extra === undefined) {
            invitePlayer(player, id, mode);
            return;
        }
        const invite = player.invite;
        if (invite?.id !== id || !invite || mode !== undefined) return;
        if (command === 'ACCEPT' && player === invite.to) {
            acceptInvite(invite);
        } else if (command === 'DECLINE') {
            clearInvite(invite);
            const other = player === invite.to ? invite.from : invite.to;
            send(other, {
                t: 'info',
                text: player === invite.to ? `${player.name} declined the invitation.` : `${player.name} cancelled the invitation.`,
            });
            roster();
        }
    }

    function attach(socket, key, encodedName) {
        if (!secret) return 'lobby-unavailable';
        if (!matchesKey(key, secret)) return 'lobby-key';
        if (!/^[A-Za-z0-9_-]{1,96}$/.test(encodedName ?? '')) return 'invalid-request';
        const name = Buffer.from(encodedName, 'base64url').toString('utf8').trim();
        if (!name || name.length > 18 || /[\u0000-\u001f\u007f]/.test(name)) return 'invalid-request';
        if (players.size >= MAX_PLAYERS) return 'lobby-full';

        const player = { id: randomBytes(8).toString('hex'), name, socket, invite: null, launching: false };
        players.set(player.id, player);
        let pending = '';
        let lastHeard = Date.now();
        const heartbeat = setInterval(() => {
            if (Date.now() - lastHeard >= 3 * HEARTBEAT_MS) socket.destroy();
            else send(player, { t: 'ping' });
        }, HEARTBEAT_MS);
        socket.on('data', chunk => {
            pending += chunk.toString('ascii');
            if (pending.length > 256) {
                socket.destroy();
                return;
            }
            let newline;
            while ((newline = pending.indexOf('\n')) >= 0) {
                const line = pending.slice(0, newline);
                pending = pending.slice(newline + 1);
                lastHeard = Date.now();
                handle(player, line);
            }
        });
        socket.once('close', () => {
            clearInterval(heartbeat);
            players.delete(player.id);
            if (player.invite) {
                const invite = player.invite;
                clearInvite(invite);
                send(invite.from === player ? invite.to : invite.from, { t: 'info', text: `${player.name} left the lobby.` });
            }
            roster();
        });
        socket.write('READY\n');
        roster();
    }

    function allowsHost(room, key) {
        const ticket = tickets.get(room);
        return Boolean(ticket && ticket.expires > Date.now() && matchesKey(key, ticket.key));
    }

    function roomReady(room) {
        const ticket = tickets.get(room);
        if (!ticket?.guest) return;
        clearTimeout(ticket.timer);
        send(ticket.guest, { t: 'match', room, mode: ticket.mode });
        ticket.guest = null;
        ticket.timer = null;
    }

    return { attach, allowsHost, roomReady };
}
