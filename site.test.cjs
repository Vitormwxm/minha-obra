'use strict';

// Public-site/offline regression checks; Node only, no browser or production data.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function worker() {
  const handlers = {};
  const storage = new Map();
  const state = { online: true, status: 200, body: 'network response', skipWaiting: false, claimed: false };
  const caches = {
    async open(name) {
      if (!storage.has(name)) storage.set(name, new Map());
      const entries = storage.get(name);
      return {
        async addAll(urls) { for (const url of urls) entries.set(url, new Response(`cached:${new URL(url).pathname}`)); },
        async put(key, response) { entries.set(String(key), response.clone()); },
        async match(key) { return entries.get(String(key))?.clone(); }
      };
    },
    async keys() { return [...storage.keys()]; },
    async delete(name) { return storage.delete(name); }
  };
  const self = {
    location: { href: 'https://minhaobra.example/preview/sw.js' },
    addEventListener(type, callback) { handlers[type] = callback; },
    async skipWaiting() { state.skipWaiting = true; },
    clients: { async claim() { state.claimed = true; } }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'sw.js'), 'utf8'), {
    self, caches, URL, Response,
    async fetch() {
      if (!state.online) throw new TypeError('Network unavailable');
      return new Response(state.body, { status: state.status });
    }
  });
  async function lifecycle(type) {
    const pending = [];
    handlers[type]({ waitUntil(promise) { pending.push(promise); } });
    await Promise.all(pending);
  }
  async function request(url, method = 'GET') {
    let response;
    const pending = [];
    handlers.fetch({ request: new Request(url, { method }),
      respondWith(value) { response = value; }, waitUntil(value) { pending.push(value); } });
    const result = response ? await response : null;
    await Promise.all(pending);
    return result;
  }
  return { state, storage, caches, lifecycle, request };
}

test('offline installation includes all four Poppins faces and the public app shell', async () => {
  const sw = worker();
  await sw.lifecycle('install');
  const names = await sw.caches.keys();
  assert.equal(names.length, 1);
  assert.equal(sw.state.skipWaiting, true);
  const cached = [...sw.storage.get(names[0]).keys()];
  assert.equal(cached.filter(url => url.endsWith('.ttf')).length, 4);
  for (const file of ['index.html', 'styles.css', 'calculator.js', 'app.js', 'web.js']) {
    assert.ok(cached.includes(`https://minhaobra.example/preview/${file}`));
  }
  for (const url of cached) {
    const local = path.join(__dirname, new URL(url).pathname.replace('/preview/', ''));
    assert.ok(fs.statSync(local).isFile(), `Cached asset exists: ${url}`);
  }
});

test('offline navigation and assets use the cached site and missing assets fail clearly', async () => {
  const sw = worker();
  await sw.lifecycle('install');
  sw.state.online = false;
  const home = await sw.request('https://minhaobra.example/preview/?scenario=local');
  assert.equal(home.status, 200);
  assert.equal(await home.text(), 'cached:/preview/index.html');
  const css = await sw.request('https://minhaobra.example/preview/styles.css?v=1');
  assert.equal(await css.text(), 'cached:/preview/styles.css');
  const cache = sw.storage.values().next().value;
  cache.delete('https://minhaobra.example/preview/app.js');
  const missing = await sw.request('https://minhaobra.example/preview/app.js');
  assert.equal(missing.status, 503);
  assert.match(await missing.text(), /internet/);
});

test('fresh public assets update the cache and temporary server failures retain the previous copy', async () => {
  const sw = worker();
  await sw.lifecycle('install');
  sw.state.body = 'updated stylesheet';
  const online = await sw.request('https://minhaobra.example/preview/styles.css');
  assert.equal(await online.text(), 'updated stylesheet');
  sw.state.status = 503;
  const fallback = await sw.request('https://minhaobra.example/preview/styles.css');
  assert.equal(await fallback.text(), 'updated stylesheet');
});

test('worker leaves other sites, non-GET requests and unrelated caches untouched', async () => {
  const sw = worker();
  await sw.lifecycle('install');
  await sw.caches.open('minha-obra-web:/preview/:old');
  await sw.caches.open('minha-obra-web:/other-site/:old');
  await sw.caches.open('unrelated-app');
  await sw.lifecycle('activate');
  assert.equal(sw.state.claimed, true);
  const names = await sw.caches.keys();
  assert.equal(names.includes('minha-obra-web:/preview/:old'), false);
  assert.ok(names.includes('minha-obra-web:/other-site/:old'));
  assert.ok(names.includes('unrelated-app'));
  assert.equal(await sw.request('https://caixa.gov.br/'), null);
  assert.equal(await sw.request('https://minhaobra.example/preview/index.html', 'POST'), null);
  assert.equal(await sw.request('https://minhaobra.example/api/account'), null);
});
