import { createLobby, KEY_PATTERN, matchesKey } from './lobby.mjs';
import { createScores } from './scores.mjs';
import { createServer } from 'node:net';
import { pathToFileURL } from 'node:url';

const ROOM_PATTERN = /^[a-f0-9]{32}$/;
const MAX_HANDSHAKE_BYTES = 256;
const MAX_ROOMS = 8;
const HANDSHAKE_TIMEOUT_MS = 5000;

export function createRelay(hostKey, lobbyKey, scoresFile = null) {
    if (!KEY_PATTERN.test(hostKey ?? '')) {
        throw new Error('Set AGES_OF_SIEGE_HOST_KEY to a random 32–128 character key.');
    }

    if (lobbyKey && !KEY_PATTERN.test(lobbyKey)) {
        throw new Error('Set AGES_OF_SIEGE_LOBBY_KEY to a random 32–128 character key.');
    }
    const secret = Buffer.from(hostKey);
    const lobbySecret = lobbyKey ? Buffer.from(lobbyKey) : null;
    const lobby = createLobby(lobbyKey);
    const scores = createScores(scoresFile);
    const rooms = new Map();
    const server = createServer(socket => {
        let handshake = Buffer.alloc(0);
        let room;
        let peer;

        socket.setNoDelay(true);
        socket.setKeepAlive(true, 15000);
        const handshakeTimeout = setTimeout(() => socket.destroy(), HANDSHAKE_TIMEOUT_MS);
        socket.on('error', () => socket.destroy());
        socket.on('close', () => {
            clearTimeout(handshakeTimeout);
            const activeHost = rooms.get(room)?.socket;
            if (activeHost === socket || (peer && activeHost === peer)) rooms.delete(room);
            if (peer && !peer.destroyed) {
                peer.end();
                peer.setTimeout(2000);
                peer.once('timeout', () => peer.destroy());
            }
        });
        socket.on('data', receiveHandshake);

        function reject(reason) {
            socket.removeListener('data', receiveHandshake);
            socket.end(`ERROR ${reason}\n`);
        }

        function receiveHandshake(chunk) {
            if (handshake.length + chunk.length > MAX_HANDSHAKE_BYTES) {
                reject('invalid-request');
                return;
            }
            handshake = Buffer.concat([handshake, chunk]);
            const newline = handshake.indexOf(10);
            if (newline === -1) return;
            if (newline !== handshake.length - 1) {
                reject('invalid-request');
                return;
            }

            socket.removeListener('data', receiveHandshake);
            const [version, command, code, key, extra] = handshake.subarray(0, newline).toString('utf8').split(' ');
            if (version !== 'AOS1' || extra !== undefined) {
                reject('invalid-request');
                return;
            }
            if (command === 'LOBBY') {
                const error = lobby.attach(socket, code, key);
                if (error) reject(error);
                else clearTimeout(handshakeTimeout);
                return;
            }
            if (command === 'SCORES' && key === undefined) {
                // Leaderboards are shared by the friends who know the lobby key.
                if (!lobbySecret) reject('lobby-unavailable');
                else if (!matchesKey(code, lobbySecret)) reject('lobby-key');
                else {
                    clearTimeout(handshakeTimeout);
                    scores.attach(socket);
                }
                return;
            }
            if (!ROOM_PATTERN.test(code ?? '')) {
                reject('invalid-request');
                return;
            }

            if (command === 'HOST' && key !== undefined) {
                if (!matchesKey(key, secret) && !lobby.allowsHost(code, key)) {
                    reject('host-key');
                    return;
                }
                if (rooms.has(code) || rooms.size >= MAX_ROOMS) {
                    reject('host-busy');
                    return;
                }
                room = code;
                rooms.set(code, {
                    socket,
                    paired: false,
                    attachGuest(guest) {
                        peer = guest;
                        this.paired = true;
                    },
                });
                clearTimeout(handshakeTimeout);
                socket.write('READY\n');
                lobby.roomReady(room);
            } else if (command === 'JOIN' && key === undefined) {
                const host = rooms.get(code);
                if (!host || host.paired || host.socket.writableEnded || host.socket.destroyed) {
                    reject('room-unavailable');
                    return;
                }
                room = code;
                peer = host.socket;
                host.attachGuest(socket);
                clearTimeout(handshakeTimeout);
                socket.write('PAIRED\n');
                peer.write('PAIRED\n');
                socket.pipe(peer, { end: false });
                peer.pipe(socket, { end: false });
            } else {
                reject('invalid-request');
            }
        }

    });
    server.maxConnections = 64;
    return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const dataDir = process.env.STATE_DIRECTORY ?? process.env.AGES_OF_SIEGE_DATA ?? '.';
    const server = createRelay(process.env.AGES_OF_SIEGE_HOST_KEY, process.env.AGES_OF_SIEGE_LOBBY_KEY, `${dataDir}/scores.json`);
    server.listen(47822, '127.0.0.1', () => console.log('Game relay listening on 127.0.0.1:47822'));
}
