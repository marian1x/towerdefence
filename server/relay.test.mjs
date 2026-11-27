import { once } from 'node:events';
import { connect } from 'node:net';
import { createInterface } from 'node:readline';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from './relay.mjs';

const hostKey = 'example-host-key-for-testing-only';
const room = 'a'.repeat(32);
const otherRoom = 'b'.repeat(32);

async function startRelay(t) {
    const server = createRelay(hostKey);
    const clients = new Set();
    t.after(() => {
        for (const client of clients) client.destroy();
        return new Promise(resolve => server.close(resolve));
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');

    return async () => {
        const socket = connect(server.address().port, '127.0.0.1');
        socket.setTimeout(2000, () => socket.destroy(new Error('Relay test timed out')));
        clients.add(socket);
        socket.once('close', () => clients.delete(socket));
        const lines = createInterface({ input: socket })[Symbol.asyncIterator]();
        await once(socket, 'connect');
        return {
            socket,
            send: line => socket.write(line + '\n'),
            read: async () => (await lines.next()).value,
        };
    };
}

test('requires the host key and pairs only players with the correct invite', async t => {
    const client = await startRelay(t);
    const invalid = await client();
    invalid.send(`AOS1 HOST ${room} ${'x'.repeat(hostKey.length)}`);
    assert.equal(await invalid.read(), 'ERROR host-key');

    const host = await client();
    host.send(`AOS1 HOST ${room} ${hostKey}`);
    assert.equal(await host.read(), 'READY');

    const stranger = await client();
    stranger.send(`AOS1 JOIN ${otherRoom}`);
    assert.equal(await stranger.read(), 'ERROR room-unavailable');

    const guest = await client();
    guest.send(`AOS1 JOIN ${room}`);
    assert.equal(await guest.read(), 'PAIRED');
    assert.equal(await host.read(), 'PAIRED');

    const third = await client();
    third.send(`AOS1 JOIN ${room}`);
    assert.equal(await third.read(), 'ERROR room-unavailable');
    guest.send('{"t":"hello","name":"Guest"}');
    assert.equal(await host.read(), '{"t":"hello","name":"Guest"}');
    host.send('{"t":"start"}');
    assert.equal(await guest.read(), '{"t":"start"}');
});

test('delivers the final frame before closing and allows the host to reopen the invite', async t => {
    const client = await startRelay(t);
    const host = await client();
    host.send(`AOS1 HOST ${room} ${hostKey}`);
    assert.equal(await host.read(), 'READY');
    const guest = await client();
    guest.send(`AOS1 JOIN ${room}`);
    assert.equal(await guest.read(), 'PAIRED');
    assert.equal(await host.read(), 'PAIRED');

    guest.socket.end('{"t":"bye"}\n');
    assert.equal(await host.read(), '{"t":"bye"}');
    assert.equal(await host.read(), undefined);

    const resumedHost = await client();
    resumedHost.send(`AOS1 HOST ${room} ${hostKey}`);
    assert.equal(await resumedHost.read(), 'READY');
    const resumedGuest = await client();
    resumedGuest.send(`AOS1 JOIN ${room}`);
    assert.equal(await resumedGuest.read(), 'PAIRED');
    assert.equal(await resumedHost.read(), 'PAIRED');
});
