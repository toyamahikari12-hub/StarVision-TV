// Service worker minimal untuk Panel Channels.
// Tujuannya cuma supaya browser mengizinkan "Install ke Layar Utama".
// Sengaja TIDAK meng-cache apa pun dari api.github.com -- data channels.json
// dan features.json harus selalu diambil fresh, tidak boleh basi.

const SHELL_CACHE = 'panel-channels-shell-v1';
const SHELL_FILES = [
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL_FILES))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== SHELL_CACHE)
          .map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // JANGAN pernah cache panggilan ke GitHub API -- selalu harus fresh.
  if (url.hostname.includes('github.com') || url.hostname.includes('githubusercontent.com')) {
    return; // biarkan request jalan normal ke jaringan
  }

  // Shell aplikasi sendiri: coba jaringan dulu, jatuh ke cache kalau offline.
  event.respondWith(
    fetch(event.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(SHELL_CACHE).then((cache) => cache.put(event.request, copy));
        return res;
      })
      .catch(() => caches.match(event.request))
  );
});
