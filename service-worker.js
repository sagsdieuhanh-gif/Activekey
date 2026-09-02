const CACHE_NAME="sags-v2.2.5-signature-export-storage-fix";
const BUILD="V2.2.5-SIGNATURE-EXPORT-STORAGE-FIX";
const DISPLAY_VERSION="V2.2.5";
const PATCH_V21="./v2.1-runtime-patch.js";
const PATCH_V22="./v2.2-runtime-patch.js";
const PATCH_V222="./v2.2.2-runtime-patch.js";
const PATCH_V225="./v2.2.5-runtime-patch.js";
const APP_SHELL=["./9Gfinal.png","./CBTT.png","./KTTB.png","./LNF.png","./PVHK.png","./PVHLNG.png","./VHTTB.png","./VJfinal.png","./VJfinal2.png","./VUfinal.png","./ai.js","./alert-crosscheck-complete.mp3","./alert-ket-so-moi.mp3","./alert-ket-so-thay-doi.mp3","./alert-mva.mp3","./alert-mvt.mp3","./alert-pushback-missing.mp3","./alert-read-sign.mp3","./app.js","./v2.1-runtime-patch.js","./v2.2-runtime-patch.js","./v2.2.2-runtime-patch.js","./v2.2.5-runtime-patch.js","./apple-touch-icon.png","./favicon-16.png","./favicon-32.png","./fsags13-official-continuation.png","./fsags13-official-page1.png","./icon-192.png","./icon-512.png","./index.html","./ios-export.js","./ket-so.wav","./login-bg.jpg","./login-logo-10years.png","./manifest.webmanifest","./page1.png","./page10.png","./page11.png","./page12.png","./page13.png","./page2.png","./page4.png","./page6.png","./page7.png","./page9.png","./report.css","./report.js","./rns-ke-continuation.png","./rns-ke-page1.png","./rns-lj-continuation.png","./rns-lj-page1.png","./rns-tw-page1.png","./rns-tw-participants.png","./sags-logo.png","./theme.css","./ui.css","./ui.js","./daily-roster.js","./version.json","./ĐH.png"];
const FRESH_SUFFIXES=["/version.json","/manifest.webmanifest","/index.html","/app.js","/ai.js","/ui.css","/ui.js","/ios-export.js","/report.css","/report.js","/theme.css","/daily-roster.js","/v2.1-runtime-patch.js","/v2.2-runtime-patch.js","/v2.2.2-runtime-patch.js","/v2.2.5-runtime-patch.js"];
function isFreshPath(pathname){return FRESH_SUFFIXES.some(x=>pathname.endsWith(x));}
async function previousCached(path){const keys=(await caches.keys()).filter(k=>k!==CACHE_NAME);for(const key of keys){try{const hit=await (await caches.open(key)).match(path);if(hit)return hit;}catch(_e){}}return null;}
async function fetchNoStore(path){return await fetch(path,{cache:"no-store",headers:{"Cache-Control":"no-cache","Pragma":"no-cache"}});}
function stripRetiredScripts(out){
  return String(out||"")
    .replace(/<script\b[^>]*\bv2\.2\.1-runtime-patch\.js(?:\?[^"'>\s]*)?[^>]*>\s*<\/script>\s*/gi,"")
    .replace(/<script\b[^>]*\bv2\.2\.3-runtime-patch\.js(?:\?[^"'>\s]*)?[^>]*>\s*<\/script>\s*/gi,"")
    .replace(/<script\b[^>]*\bv2\.2\.4-runtime-patch\.js(?:\?[^"'>\s]*)?[^>]*>\s*<\/script>\s*/gi,"");
}
function injectScript(out,file){if(out.includes(file))return out;const tag=`<script src="./${file}?v=${encodeURIComponent(DISPLAY_VERSION)}"></script>`;if(/<\/body>/i.test(out))return out.replace(/<\/body>/i,`${tag}\n</body>`);return out+`\n${tag}\n`;}
function patchIndexHtml(html){
  let out=stripRetiredScripts(String(html||""));
  out=out.replace(/(const\s+APP_BUILD_VERSION\s*=\s*)["'][^"']+["'](\s*;?)/,`$1"${BUILD}"$2`);
  out=out.replace(/(const\s+APP_DISPLAY_VERSION\s*=\s*)["'][^"']+["'](\s*;?)/,`$1"${DISPLAY_VERSION}"$2`);
  out=injectScript(out,"v2.1-runtime-patch.js");
  out=injectScript(out,"v2.2-runtime-patch.js");
  out=injectScript(out,"v2.2.2-runtime-patch.js");
  out=injectScript(out,"v2.2.5-runtime-patch.js");
  return out;
}
async function cacheCriticalFresh(dst){
  const vr=await fetchNoStore("./version.json?swcheck="+Date.now());
  if(!vr.ok)throw new Error("version.json HTTP "+vr.status);
  const vd=await vr.clone().json();
  if(String(vd?.build||"").trim()!==BUILD||String(vd?.displayVersion||vd?.version||"").trim()!==DISPLAY_VERSION)throw new Error("version.json chưa đồng bộ BUILD "+BUILD);
  await dst.put("./version.json",vr.clone());

  const ir=await fetchNoStore("./index.html?swcheck="+Date.now());
  if(!ir.ok)throw new Error("index.html HTTP "+ir.status);
  const html=patchIndexHtml(await ir.clone().text());
  const bm=html.match(/const\s+APP_BUILD_VERSION\s*=\s*["']([^"']+)["']/),dm=html.match(/const\s+APP_DISPLAY_VERSION\s*=\s*["']([^"']+)["']/);
  if(String(bm?.[1]||"").trim()!==BUILD||String(dm?.[1]||"").trim()!==DISPLAY_VERSION||!html.includes("daily-roster.js")||!html.includes("v2.1-runtime-patch.js")||!html.includes("v2.2-runtime-patch.js")||!html.includes("v2.2.2-runtime-patch.js")||!html.includes("v2.2.5-runtime-patch.js")||html.includes("v2.2.1-runtime-patch.js")||html.includes("v2.2.3-runtime-patch.js")||html.includes("v2.2.4-runtime-patch.js"))throw new Error("index.html chưa vá đúng "+DISPLAY_VERSION+" hoặc còn runtime thử nghiệm");
  await dst.put("./index.html",new Response(html,{status:200,headers:{"Content-Type":"text/html; charset=utf-8","Cache-Control":"no-cache"}}));

  const ar=await fetchNoStore("./app.js?swcheck="+Date.now());
  if(!ar.ok)throw new Error("app.js HTTP "+ar.status);
  const appText=await ar.clone().text();
  if(!appText.includes("E-REPORT/SAGS V1.1.103 RTDB ANCESTOR HOTFIX ONLY"))throw new Error("app.js chưa đúng nền V1.1.103");
  await dst.put("./app.js",ar.clone());

  const dr=await fetchNoStore("./daily-roster.js?swcheck="+Date.now());
  if(!dr.ok)throw new Error("daily-roster.js HTTP "+dr.status);
  await dst.put("./daily-roster.js",dr.clone());

  const ios=await fetchNoStore("./ios-export.js?swcheck="+Date.now());
  if(!ios.ok)throw new Error("ios-export.js HTTP "+ios.status);
  await dst.put("./ios-export.js",ios.clone());

  for(const pf of [PATCH_V21,PATCH_V22,PATCH_V222,PATCH_V225]){
    const pr=await fetchNoStore(pf+"?swcheck="+Date.now());
    if(!pr.ok)throw new Error(pf+" HTTP "+pr.status);
    const txt=await pr.clone().text();
    if(pf===PATCH_V22&&!txt.includes("V2.2-ARRDEP-CHOICE-LOCALFIRST"))throw new Error("V2.2 base patch không đúng");
    if(pf===PATCH_V222&&!txt.includes("V2.2.2-DEP-RECEIVE-AFTER-ARR"))throw new Error("V2.2.2 DEP patch không đúng");
    if(pf===PATCH_V225&&!txt.includes(BUILD))throw new Error("V2.2.5 patch chưa đúng BUILD "+BUILD);
    await dst.put(pf,pr.clone());
  }
}
async function installDirectCache(){
  const dst=await caches.open(CACHE_NAME);
  await cacheCriticalFresh(dst);
  const critical=new Set(["./index.html","./version.json","./app.js","./ios-export.js","./daily-roster.js",PATCH_V21,PATCH_V22,PATCH_V222,PATCH_V225]);
  await Promise.all(APP_SHELL.filter(path=>!critical.has(path)).map(async path=>{try{let response=null;const pathname=new URL(path,self.location.href).pathname;if(!isFreshPath(pathname))response=await previousCached(path);if(!response){const r=await fetchNoStore(path);if(r&&r.ok)response=r;}if(response)await dst.put(path,response.clone());}catch(_e){}}));
}
self.addEventListener("install",event=>{/* SAFE UPDATE: worker waits until user presses UPDATE. */event.waitUntil(installDirectCache());});
self.addEventListener("activate",event=>{event.waitUntil((async()=>{const keys=await caches.keys();await Promise.all(keys.filter(k=>k!==CACHE_NAME).map(k=>caches.delete(k)));await self.clients.claim();})());});
self.addEventListener("message",event=>{if(event.data&&event.data.type==="SKIP_WAITING")self.skipWaiting();});
self.addEventListener("fetch",event=>{
  if(event.request.method!=="GET")return;
  const url=new URL(event.request.url);
  if(url.origin!==self.location.origin)return;
  const nav=event.request.mode==="navigate",vf=url.pathname.endsWith("/version.json");
  if(vf){event.respondWith((async()=>{const c=await caches.open(CACHE_NAME);try{const n=await fetch(event.request,{cache:"no-store"});if(n&&n.ok)c.put("./version.json",n.clone()).catch(()=>{});return n}catch(_){return(await c.match("./version.json"))||new Response("OFFLINE",{status:503})}})());return;}
  if(nav){event.respondWith((async()=>{const c=await caches.open(CACHE_NAME),hit=await c.match("./index.html");if(hit)return hit;try{const n=await fetch(event.request,{cache:"no-store"});if(n&&n.ok){const p=new Response(patchIndexHtml(await n.clone().text()),{status:n.status,statusText:n.statusText,headers:{"Content-Type":"text/html; charset=utf-8","Cache-Control":"no-cache"}});c.put("./index.html",p.clone()).catch(()=>{});return p}return n}catch(_){return new Response("OFFLINE",{status:503})}})());return;}
  if(isFreshPath(url.pathname)){event.respondWith((async()=>{const c=await caches.open(CACHE_NAME),hit=await c.match(event.request);if(hit)return hit;const canonical=await c.match("./"+url.pathname.split('/').pop());if(canonical)return canonical;try{const r=await fetch(event.request,{cache:"no-store"});if(r&&r.ok)c.put(event.request,r.clone()).catch(()=>{});return r}catch(_){return new Response("OFFLINE",{status:503})}})());return;}
  event.respondWith((async()=>{const c=await caches.open(CACHE_NAME),hit=await c.match(event.request);if(hit)return hit;try{const r=await fetch(event.request);if(r&&r.ok)c.put(event.request,r.clone()).catch(()=>{});return r}catch(_){return new Response("OFFLINE",{status:503})}})());
});
