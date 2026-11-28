// Quick live check of the leaderboards: node selftest.mjs <lobby key>  (talks to the relay on 127.0.0.1:47822)
import { connect } from 'node:net';
import { createInterface } from 'node:readline';

const key = process.argv[2];

function request(msg) {
    return new Promise((resolve, reject) => {
        const socket = connect(47822, '127.0.0.1');
        const lines = createInterface({ input: socket });
        const replies = [];
        lines.on('line', line => {
            replies.push(line);
            if (line === 'READY') socket.write(JSON.stringify(msg) + '\n');
            else {
                resolve(line);
                socket.destroy();
            }
        });
        socket.on('error', reject);
        socket.setTimeout(3000, () => reject(new Error('timeout ' + replies.join('|'))));
        socket.write(`AOS1 SCORES ${key}\n`);
    });
}

console.log(await request({ t: 'submit', board: 'selftest-0', name: 'Selftest', score: 1, stars: 0, wave: 1 }));
console.log(await request({ t: 'top', board: 'selftest-0' }));
