import { timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:net';
import { pathToFileURL } from 'node:url';

const HOST_KEY_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;
const ROOM_PATTERN = /^[a-f0-9]{32}$/;
const MAX_HANDSHAKE_BYTES = 256;
const MAX_ROOMS = 8;
const HANDSHAKE_TIMEOUT_MS = 5000;

export function createRelay(hostKey) {
    if (!HOST_KEY_PATTERN.test(hostKey ?? '')) {
        throw new Error('Set AGES_OF_SIEGE_HOST_KEY to a random 32–128 character key.');
    }

    const secret = Buffer.from(hostKey);
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
            if (version !== 'AOS1' || !ROOM_PATTERN.test(code ?? '') || extra !== undefined) {
                reject('invalid-request');
                return;
            }

            if (command === 'HOST' && key !== undefined) {
                const candidate = Buffer.from(key);
                if (candidate.length !== secret.length || !timingSafeEqual(candidate, secret)) {
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
    const server = createRelay(process.env.AGES_OF_SIEGE_HOST_KEY);
    server.listen(47822, '127.0.0.1', () => console.log('Game relay listening on 127.0.0.1:47822'));
}
