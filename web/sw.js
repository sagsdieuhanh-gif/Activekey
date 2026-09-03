const CACHE="trungkien-web-v1-shell";const SHELL=["./","./index.html","./style.css","./app.js","./manifest.webmanifest","./icon.svg"];
self.addEventListener("install",e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL)));self.skipWaiting()});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==CACHE).map(x=>caches.delete(x)))));self.clients.claim()});
self.addEventListener("fetch",e=>{const u=new URL(e.request.url);if(u.pathname.endsWith("/models/lane_core.onnx"))return;e.respondWith(caches.match(e.request).then(x=>x||fetch(e.request).then(r=>{if(e.request.method==="GET"&&r.ok&&u.origin===location.origin)caches.open(CACHE).then(c=>c.put(e.request,r.clone()));return r})))});
