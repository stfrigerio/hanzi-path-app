/* Offline: the app, its library and the whole path are kept on first visit,
   so it works on a plane. Bump VERSION when any of them changes. */
const VERSION = "hanzi-path-1";
const SHELL = ["./", "index.html", "style.css", "app.js", "vendor/hanzi-writer.min.js",
  "manifest.webmanifest", "icon.svg", "data/path.json"];

self.addEventListener("install", e => {
  e.waitUntil((async () => {
    const c = await caches.open(VERSION);
    await c.addAll(SHELL);
    const path = await (await c.match("data/path.json")).json();
    const n = Math.ceil(path.items.length / path.chunk);
    await c.addAll(Array.from({ length: n }, (_, i) => `data/strokes-${i}.json`));
    self.skipWaiting();
  })());
});

self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// the app's own files from the cache; anything else (the fonts) from the
// network, kept for next time
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request).then(r => {
    if (r.ok && (r.type === "basic" || r.type === "cors")) {
      const copy = r.clone();
      caches.open(VERSION).then(c => c.put(e.request, copy));
    }
    return r;
  })));
});
