'use strict';
// Atualize a versão ao publicar alterações dos arquivos do site.
const VERSION = '1.0.1-web.1';
const BASE = new URL('./', self.location.href);
const PREFIX = `minha-obra-web:${BASE.pathname}:`;
const CACHE = PREFIX + VERSION;
const FILES = [
  'index.html', 'styles.css', 'calculator.js', 'app.js', 'web.js', 'favicon.svg',
  'assets/fonts/Poppins-Regular.ttf', 'assets/fonts/Poppins-Medium.ttf',
  'assets/fonts/Poppins-SemiBold.ttf', 'assets/fonts/Poppins-Bold.ttf'
];
const URLS = FILES.map(file => new URL(file, BASE).href);
const ALLOWED_PATHS = new Set(URLS.map(url => new URL(url).pathname));

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(URLS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(key => key.startsWith(PREFIX) && key !== CACHE).map(key => caches.delete(key))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== BASE.origin) return;
  const home = url.pathname === BASE.pathname || url.pathname === new URL('index.html', BASE).pathname;
  if (!home && !ALLOWED_PATHS.has(url.pathname)) return;
  const cacheKey = home ? new URL('index.html', BASE).href : new URL(url.pathname, BASE.origin).href;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const response = await fetch(request);
      if (response.ok && response.type !== 'opaque') {
        // O site só guarda arquivos públicos. Valores de simulação nunca entram no cache HTTP.
        event.waitUntil(cache.put(cacheKey, response.clone()).catch(() => {}));
        return response;
      }
      return (await cache.match(cacheKey)) || response;
    } catch {
      return (await cache.match(cacheKey)) || new Response('Reabra o site com internet para baixar os arquivos desta versão.', {
        status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' }
      });
    }
  })());
});
