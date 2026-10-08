/*
 * Lets the viewer open from the home screen with no network.
 *
 * The page, its scripts and its stylesheet are cached on first visit and served from the cache
 * afterwards while a fresh copy is fetched behind them (stale-while-revalidate), so an update
 * arrives on the visit after it is published. Nothing here ever sees a family file: that is read
 * in the page from a file picker and goes nowhere, and this worker only handles GET requests for
 * the viewer's own assets and the two font hosts it already uses.
 *
 * Bump VERSION only to throw away old caches; a changed file needs no bump.
 */
const VERSION = 'v1';
const CACHE = `kutumb-viewer-${VERSION}`;

const SHELL = [
  './',
  'index.html',
  'playground.css',
  'main.js',
  'archive.js',
  'chart.js',
  'compact.js',
  'dates.js',
  'focus.js',
  'kinship-hi.js',
  'kinship-hindi.js',
  'layout.js',
  'model.js',
  'occasions.js',
  'search.js',
  'sort-key.js',
  'write.js',
  'remember.js',
  'manifest.webmanifest',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/apple-touch-icon.png',
  '../favicon.svg',
];

const FONT_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('kutumb-viewer-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const ours = url.origin === self.location.origin && url.pathname.startsWith(new URL('./', self.location).pathname);
  const fonts = FONT_HOSTS.includes(url.hostname);
  if (!ours && !fonts) return;

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(req, { ignoreSearch: true });
    const fresh = fetch(req).then((res) => {
      if (res.ok) cache.put(req, res.clone());
      return res;
    });
    if (hit) { fresh.catch(() => {}); return hit; }
    try { return await fresh; }
    catch (e) {
      // Offline and never seen: a navigation falls back to the cached page rather than an error.
      if (req.mode === 'navigate') { const page = await cache.match('index.html'); if (page) return page; }
      throw e;
    }
  })());
});
