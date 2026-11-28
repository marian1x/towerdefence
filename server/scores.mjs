import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

// Friends-only leaderboards (Daily Challenge, campaign levels, Endless).
// One request per connection after "AOS1 SCORES <lobby key>": a JSON line in, a JSON line out.

const BOARD_PATTERN = /^[a-z0-9-]{3,48}$/;
const MAX_LINE = 1024;
const MAX_BOARDS = 400;
const TOP = 50;
const REQUEST_TIMEOUT_MS = 5000;

export function createScores(file) {
    let boards = {};
    if (file) {
        try {
            boards = JSON.parse(readFileSync(file, 'utf8')).boards ?? {};
        } catch {
            boards = {};
        }
    }
    let saveTimer = null;

    function save() {
        if (!file || saveTimer) return;
        saveTimer = setTimeout(() => {
            saveTimer = null;
            try {
                mkdirSync(dirname(file), { recursive: true });
                const tmp = `${file}.tmp`;
                writeFileSync(tmp, JSON.stringify({ boards }));
                renameSync(tmp, file);
            } catch (error) {
                console.error('Could not save scores', error);
            }
        }, 1000);
        saveTimer.unref();
    }

    function validName(name) {
        return typeof name === 'string' && name.trim().length > 0 && name.length <= 18 && !/[\u0000-\u001f\u007f]/.test(name);
    }

    function int(value, max) {
        return Number.isInteger(value) && value >= 0 && value <= max;
    }

    function submit(msg) {
        const { board, name, score, stars, wave } = msg;
        if (!BOARD_PATTERN.test(board ?? '') || !validName(name) || !int(score, 10_000_000) || !int(stars ?? 0, 3) || !int(wave ?? 0, 100_000)) {
            return { t: 'error', text: 'invalid score' };
        }
        let list = boards[board];
        if (!list) {
            const ids = Object.keys(boards);
            if (ids.length >= MAX_BOARDS) {
                // Forget the board that was touched longest ago.
                ids.sort((a, b) => (boards[a].at ?? 0) - (boards[b].at ?? 0));
                delete boards[ids[0]];
            }
            list = boards[board] = { at: Date.now(), entries: [] };
        }
        list.at = Date.now();
        const clean = name.trim();
        const existing = list.entries.find(e => e.name === clean);
        if (existing) {
            if (score <= existing.score) return { t: 'ok', best: existing.score, rank: rankOf(list, clean) };
            Object.assign(existing, { score, stars: stars ?? 0, wave: wave ?? 0, at: Date.now() });
        } else {
            list.entries.push({ name: clean, score, stars: stars ?? 0, wave: wave ?? 0, at: Date.now() });
        }
        list.entries.sort((a, b) => b.score - a.score || a.at - b.at);
        list.entries.length = Math.min(list.entries.length, TOP);
        save();
        return { t: 'ok', best: score, rank: rankOf(list, clean) };
    }

    function rankOf(list, name) {
        return list.entries.findIndex(e => e.name === name) + 1;
    }

    function top(msg) {
        const { board } = msg;
        if (!BOARD_PATTERN.test(board ?? '')) return { t: 'error', text: 'invalid board' };
        const entries = (boards[board]?.entries ?? []).map(({ name, score, stars, wave }) => ({ name, score, stars, wave }));
        return { t: 'top', board, entries };
    }

    function handle(line) {
        let msg;
        try {
            msg = JSON.parse(line);
        } catch {
            return { t: 'error', text: 'invalid request' };
        }
        if (msg?.t === 'submit') return submit(msg);
        if (msg?.t === 'top') return top(msg);
        return { t: 'error', text: 'invalid request' };
    }

    /** Takes over a socket that passed the handshake. */
    function attach(socket) {
        let pending = '';
        const timer = setTimeout(() => socket.destroy(), REQUEST_TIMEOUT_MS);
        socket.write('READY\n');
        socket.on('data', chunk => {
            pending += chunk.toString('utf8');
            if (pending.length > MAX_LINE) {
                clearTimeout(timer);
                socket.destroy();
                return;
            }
            const newline = pending.indexOf('\n');
            if (newline < 0) return;
            clearTimeout(timer);
            const reply = handle(pending.slice(0, newline));
            socket.end(JSON.stringify(reply) + '\n');
        });
        socket.once('close', () => clearTimeout(timer));
    }

    return { attach, handle };
}
