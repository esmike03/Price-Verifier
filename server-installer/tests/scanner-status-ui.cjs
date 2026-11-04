const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../portal/assets/app.js'), 'utf8');
const start = source.indexOf('(function scanners() {');
assert.ok(start >= 0, 'Scanner refresh code exists');
const end = source.indexOf('})();', start) + 5;
const requests = [];
const listeners = {};
let tick;
let interval;
let visible = true;
const box = {
    innerHTML: '',
    closest: () => ({ classList: { contains: () => visible } }),
};
const document = {
    hidden: false,
    addEventListener: (event, callback) => { listeners[event] = callback; },
};
function fetch(url, options) {
    return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject }));
}
vm.runInNewContext(source.slice(start, end), {
    $: () => box, window: { fetch }, fetch, document, cfg: { endpoint: '/index.php' },
    setInterval: (callback, milliseconds) => { tick = callback; interval = milliseconds; },
});
const flush = () => new Promise(resolve => setImmediate(resolve));

(async () => {
    assert.equal(interval, 1000, 'Refresh every second');
    assert.equal(requests.length, 1, 'Refresh immediately');
    assert.equal(requests[0].options.cache, 'no-store', 'Do not reuse cached scanner status');
    tick();
    tick();
    assert.equal(requests.length, 1, 'A slow server cannot create overlapping requests');
    requests[0].resolve({ ok: true, text: () => Promise.resolve('<div data-scanner-rows>Offline</div>') });
    await flush();
    assert.ok(box.innerHTML.includes('Offline'), 'Show returned offline status');
    tick();
    assert.equal(requests.length, 2, 'Polling resumes after a response');
    requests[1].reject(new Error('Disconnected server'));
    await flush();
    assert.ok(box.innerHTML.includes('Offline'), 'Keep the last known status on a failed request');
    document.hidden = true;
    tick();
    assert.equal(requests.length, 2, 'Hidden browser tabs do not poll');
    document.hidden = false;
    visible = false;
    tick();
    assert.equal(requests.length, 2, 'Other portal tabs do not poll');
    visible = true;
    listeners.visibilitychange();
    assert.equal(requests.length, 3, 'Returning to a visible scanner list refreshes immediately');
    requests[2].resolve({ ok: true, text: () => Promise.resolve('<div data-scanner-rows>Online</div>') });
    await flush();
    assert.ok(box.innerHTML.includes('Online'), 'Show reconnection status');
    console.log('All scanner UI refresh checks passed.');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
