const CACHE="yshott-web-0.1.0";
const SHELL=["./","./index.html","./style.css","./model.js","./storage.js","./app.js","./manifest.webmanifest","./assets/icon.svg","./assets/nunito.ttf","./assets/child-pictograms.png"];
self.addEventListener("install",e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL))));
self.addEventListener("activate",e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith("yshott-web-")&&k!==CACHE).map(k=>caches.delete(k))))));
self.addEventListener("fetch",e=>{
 const url=new URL(e.request.url),base=new URL("./",self.location.href);
 if(e.request.method!=="GET"||url.origin!==base.origin||!url.pathname.startsWith(base.pathname))return;
 const relative=url.pathname.slice(base.pathname.length);
 // Cache only app shell assets. Never cache API requests or personal responses.
 if(e.request.mode==="navigate"){e.respondWith(fetch(e.request).catch(()=>caches.match(new URL("index.html",base).href)));return;}
 if(!SHELL.some(p=>p.slice(2)===relative))return;
 e.respondWith(caches.match(e.request).then(cached=>cached||fetch(e.request)));
});