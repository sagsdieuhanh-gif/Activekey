/* E-REPORT/SAGS V2.2.5 R2 · LIGHTWEIGHT SAFE UPDATE
 * BUILD: V2.2.5-SIGNATURE-EXPORT-STORAGE-FIX-R2
 *
 * Important:
 * - Do NOT duplicate the previous full cache during install.
 * - Validate only small/critical release metadata and runtime scripts.
 * - Wait for explicit UPDATE / SKIP_WAITING.
 * - On activate, remove old release caches to free storage.
 * - Rebuild cache lazily from network after activation.
 */
const CACHE_NAME="sags-v2.2.5-r2-light-update";
const BUILD="V2.2.5-SIGNATURE-EXPORT-STORAGE-FIX-R2";
const DISPLAY_VERSION="V2.2.5";

const PATCH_V21="./v2.1-runtime-patch.js";
const PATCH_V22="./v2.2-runtime-patch.js";
const PATCH_V222="./v2.2.2-runtime-patch.js";
const PATCH_V225="./v2.2.5-runtime-patch.js";

const FRESH_SUFFIXES=[
  "/version.json","/manifest.webmanifest","/index.html","/app.js","/ai.js",
  "/ui.css","/ui.js","/ios-export.js","/report.css","/report.js","/theme.css",
  "/daily-roster.js","/v2.1-runtime-patch.js","/v2.2-runtime-patch.js",
  "/v2.2.2-runtime-patch.js","/v2.2.5-runtime-patch.js"
];

function isFreshPath(pathname){
  return FRESH_SUFFIXES.some(x=>pathname.endsWith(x));
}

async function fetchNoStore(path){
  return fetch(path,{
    cache:"no-store",
    headers:{"Cache-Control":"no-cache","Pragma":"no-cache"}
  });
}

async function safePut(cache,key,response){
  try{
    if(response&&response.ok)await cache.put(key,response.clone());
  }catch(e){
    // Cache failure must not break online operation/update on storage-limited iOS.
    console.info("V2.2.5 R2 cache put skipped",key,e?.name||e?.message||e);
  }
}

function stripRetiredScripts(out){
  return String(out||"")
    .replace(/<script\b[^>]*\bv2\.2\.1-runtime-patch\.js(?:\?[^"'>\s]*)?[^>]*>\s*<\/script>\s*/gi,"")
    .replace(/<script\b[^>]*\bv2\.2\.3-runtime-patch\.js(?:\?[^"'>\s]*)?[^>]*>\s*<\/script>\s*/gi,"")
    .replace(/<script\b[^>]*\bv2\.2\.4-runtime-patch\.js(?:\?[^"'>\s]*)?[^>]*>\s*<\/script>\s*/gi,"");
}

function injectScript(out,file){
  if(out.includes(file))return out;
  const tag=`<script src="./${file}?v=${encodeURIComponent(DISPLAY_VERSION)}"></script>`;
  if(/<\/body>/i.test(out))return out.replace(/<\/body>/i,`${tag}\n</body>`);
  return out+`\n${tag}\n`;
}

function patchIndexHtml(html){
  let out=stripRetiredScripts(String(html||""));
  out=out.replace(
    /(const\s+APP_BUILD_VERSION\s*=\s*)["'][^"']+["'](\s*;?)/,
    `$1"${BUILD}"$2`
  );
  out=out.replace(
    /(const\s+APP_DISPLAY_VERSION\s*=\s*)["'][^"']+["'](\s*;?)/,
    `$1"${DISPLAY_VERSION}"$2`
  );
  out=injectScript(out,"v2.1-runtime-patch.js");
  out=injectScript(out,"v2.2-runtime-patch.js");
  out=injectScript(out,"v2.2.2-runtime-patch.js");
  out=injectScript(out,"v2.2.5-runtime-patch.js");
  return out;
}

async function validateRelease(){
  // No full APP_SHELL cache here. This is deliberate to avoid doubling the
  // V2.2.2 cache while the new worker is still waiting.
  const vr=await fetchNoStore("./version.json?swcheck="+Date.now());
  if(!vr.ok)throw new Error("version.json HTTP "+vr.status);
  const vd=await vr.clone().json();
  if(String(vd?.build||"").trim()!==BUILD)throw new Error("version.json BUILD mismatch");
  if(String(vd?.displayVersion||vd?.version||"").trim()!==DISPLAY_VERSION)throw new Error("version.json VERSION mismatch");

  const checks=[
    [PATCH_V22,"V2.2-ARRDEP-CHOICE-LOCALFIRST"],
    [PATCH_V222,"V2.2.2-DEP-RECEIVE-AFTER-ARR"],
    [PATCH_V225,BUILD]
  ];
  for(const [path,marker] of checks){
    const r=await fetchNoStore(path+"?swcheck="+Date.now());
    if(!r.ok)throw new Error(path+" HTTP "+r.status);
    const text=await r.text();
    if(!text.includes(marker))throw new Error(path+" marker mismatch");
  }

  // Check index is reachable, but DO NOT cache the huge index during install.
  const ir=await fetchNoStore("./index.html?swcheck="+Date.now());
  if(!ir.ok)throw new Error("index.html HTTP "+ir.status);
  return true;
}

self.addEventListener("install",event=>{
  // SAFE UPDATE: do not call skipWaiting here.
  event.waitUntil(validateRelease());
});

self.addEventListener("activate",event=>{
  event.waitUntil((async()=>{
    // Free old release caches before rebuilding the new one.
    const keys=await caches.keys();
    await Promise.all(keys.filter(k=>k!==CACHE_NAME).map(k=>caches.delete(k).catch(()=>false)));
    await caches.open(CACHE_NAME);
    await self.clients.claim();
  })());
});

self.addEventListener("message",event=>{
  if(event.data&&event.data.type==="SKIP_WAITING")self.skipWaiting();
});

self.addEventListener("fetch",event=>{
  if(event.request.method!=="GET")return;
  const url=new URL(event.request.url);
  if(url.origin!==self.location.origin)return;

  const nav=event.request.mode==="navigate";
  const isVersion=url.pathname.endsWith("/version.json");

  if(isVersion){
    event.respondWith((async()=>{
      const c=await caches.open(CACHE_NAME);
      try{
        const n=await fetch(event.request,{cache:"no-store"});
        await safePut(c,"./version.json",n);
        return n;
      }catch(_){
        return (await c.match("./version.json"))||new Response("OFFLINE",{status:503});
      }
    })());
    return;
  }

  if(nav){
    event.respondWith((async()=>{
      const c=await caches.open(CACHE_NAME);
      try{
        const n=await fetch(event.request,{cache:"no-store"});
        if(n&&n.ok){
          const html=patchIndexHtml(await n.clone().text());
          const p=new Response(html,{
            status:n.status,
            statusText:n.statusText,
            headers:{
              "Content-Type":"text/html; charset=utf-8",
              "Cache-Control":"no-cache"
            }
          });
          await safePut(c,"./index.html",p);
          return p;
        }
        return n;
      }catch(_){
        return (await c.match("./index.html"))||new Response("OFFLINE",{status:503});
      }
    })());
    return;
  }

  if(isFreshPath(url.pathname)){
    event.respondWith((async()=>{
      const c=await caches.open(CACHE_NAME);
      try{
        const r=await fetch(event.request,{cache:"no-store"});
        await safePut(c,event.request,r);
        return r;
      }catch(_){
        const hit=await c.match(event.request);
        if(hit)return hit;
        const canonical=await c.match("./"+url.pathname.split("/").pop());
        return canonical||new Response("OFFLINE",{status:503});
      }
    })());
    return;
  }

  event.respondWith((async()=>{
    const c=await caches.open(CACHE_NAME);
    const hit=await c.match(event.request);
    if(hit)return hit;
    try{
      const r=await fetch(event.request);
      await safePut(c,event.request,r);
      return r;
    }catch(_){
      return new Response("OFFLINE",{status:503});
    }
  })());
});
