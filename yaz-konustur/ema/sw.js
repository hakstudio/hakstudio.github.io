// Uygulamayı ve modeli önbellekte tutar, internetsiz açılsın. Metin hiçbir yere gönderilmez.
const APP = "yk-ema-app-v3";
const MODEL = "yk-ema-model-v1"; // model dosyaları büyük: sadece model değişince yenilenir
const FILES = ["./", "index.html", "ema-worker.js", "normalize.js", "manifest.webmanifest", "icon-192.png", "icon-512.png", "icon-maskable-512.png"];
self.addEventListener("install", e => { e.waitUntil(caches.open(APP).then(c => c.addAll(FILES))); self.skipWaiting(); });
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== APP && k !== MODEL).map(k => caches.delete(k)))));
  self.clients.claim();
});
// Çok çekirdekli çalışma için sayfanın "cross-origin isolated" olması gerekir. GitHub Pages bu başlıkları
// veremediği için burada ekliyoruz.
function coi(res) {
  if (!res || res.status === 0 || res.type === "opaque") return res;
  const h = new Headers(res.headers);
  h.set("Cross-Origin-Embedder-Policy", "credentialless");
  h.set("Cross-Origin-Opener-Policy", "same-origin");
  h.set("Cross-Origin-Resource-Policy", "cross-origin");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
}
const cacheFirst = (req, name) => caches.match(req).then(r => r || fetch(req).then(res => {
  if (res.ok) { const cp = res.clone(); caches.open(name).then(c => c.put(req, cp)); }
  return res;
}));
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  const url = new URL(e.request.url);
  // Model dosyaları ve onnxruntime değişmez: önce önbellek
  if (url.pathname.includes("/model/") || url.pathname.includes("/ort/")) { e.respondWith(cacheFirst(e.request, MODEL).then(coi)); return; }
  if (url.origin !== location.origin) { e.respondWith(cacheFirst(e.request, APP)); return; }
  // Uygulama dosyaları: önce ağ (güncellemeler gelsin), olmazsa önbellek
  e.respondWith(fetch(e.request).then(res => {
    if (res.ok) { const cp = res.clone(); caches.open(APP).then(c => c.put(e.request, cp)); }
    return res;
  }).catch(() => caches.match(e.request).then(r => r || caches.match("index.html"))).then(coi));
});
