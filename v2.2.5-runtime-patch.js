/* E-REPORT/SAGS V2.2.5 · SIGNATURE EXPORT STORAGE FIX
 * BUILD: V2.2.5-SIGNATURE-EXPORT-STORAGE-FIX-R2
 * Base in production: V2.2.2 + V2.2 local-first autosave.
 *
 * Fix:
 * - V2.2 stored the full envelope twice in localStorage:
 *   primary session key + sagsV22LocalCheckpoint::<session>.
 *   A signature/image-heavy form can therefore hit browser quota.
 * - Keep only ONE physical envelope copy (primary key).
 * - Checkpoint becomes metadata + envelopeRef, but V2.2 still sees a virtual
 *   full checkpoint through a transparent getItem compatibility shim.
 * - Before export, flush current in-memory form once.
 * - If already inside the same roster assignment, v310ExportAssignment no
 *   longer switches/reloads the same session before opening export.
 * - During the short export-open window only, legacy non-essential cache writes
 *   may ignore QuotaExceededError; normal editing/autosave errors are NOT hidden.
 */
(function(root){
  'use strict';

  const BUILD='V2.2.5-SIGNATURE-EXPORT-STORAGE-FIX-R2';
  if(root.__SAGS_V225_SIGNATURE_EXPORT===BUILD)return;
  root.__SAGS_V225_SIGNATURE_EXPORT=BUILD;

  const S=v=>String(v??'').trim();
  const safe=v=>S(v).replace(/[.#$\[\]\/]/g,'_');
  const nativeGet=Storage.prototype.getItem;
  const nativeSet=Storage.prototype.setItem;
  const nativeRemove=Storage.prototype.removeItem;

  let exportDepth=0;
  let compactedCount=0;
  let hydratedReads=0;
  let ignoredQuotaWrites=0;
  let lastQuotaKey='';
  let lastQuotaAtMs=0;
  let lastCompactAtMs=0;

  function isLocal(store){
    try{return store===root.localStorage}catch(_){return false}
  }
  function isQuota(e){
    const n=S(e?.name),m=S(e?.message).toLowerCase();
    return n==='QuotaExceededError'||n==='NS_ERROR_DOM_QUOTA_REACHED'||m.includes('quota');
  }
  function isCheckpointKey(k){
    return S(k).includes('sagsV22LocalCheckpoint::');
  }
  function activeMeta(){
    try{return root.currentFlightSessionMeta?.()||null}catch(_){return null}
  }
  function primaryKeyForSession(id){
    try{return typeof root.flightSessionStorageKey==='function'?S(root.flightSessionStorageKey(id)):''}catch(_){return ''}
  }
  function parseJson(s){
    try{return JSON.parse(s)}catch(_){return null}
  }
  function compactCheckpointValue(key,value){
    const cp=parseJson(value);
    if(!cp||typeof cp!=='object'||!cp.envelope)return {value,changed:false};

    const sid=S(cp.sessionId);
    const pk=primaryKeyForSession(sid);
    if(!pk)return {value,changed:false};

    const envText=JSON.stringify(cp.envelope);
    let primary=nativeGet.call(root.localStorage,pk);

    if(!primary){
      try{
        nativeSet.call(root.localStorage,pk,envText);
        primary=envText;
      }catch(e){
        // If the checkpoint itself is already occupying a lot of quota, remove
        // that old duplicate once and retry the canonical primary write.
        if(isQuota(e)){
          try{
            nativeRemove.call(root.localStorage,key);
            nativeSet.call(root.localStorage,pk,envText);
            primary=envText;
          }catch(_){}
        }
      }
    }else{
      // V2.2 writes primary immediately before checkpoint. Do not write a second
      // full copy here; primary is already canonical.
    }

    if(!primary)return {value,changed:false};

    const compact={...cp};
    delete compact.envelope;
    compact.envelopeRef=pk;
    compact.envelopeBytes=envText.length;
    compact.compactedBy=BUILD;
    compact.compactedAtMs=Date.now();
    return {value:JSON.stringify(compact),changed:true};
  }

  Storage.prototype.setItem=function(key,value){
    if(!isLocal(this))return nativeSet.call(this,key,value);

    const k=S(key);
    let v=String(value);

    if(isCheckpointKey(k)){
      try{
        const c=compactCheckpointValue(k,v);
        v=c.value;
        if(c.changed){
          compactedCount++;
          lastCompactAtMs=Date.now();
        }
      }catch(_){}
    }

    try{
      return nativeSet.call(this,key,v);
    }catch(e){
      // ONLY while opening/exporting a PDF. Editing/autosave outside this window
      // keeps the original error behavior so data-loss problems are not hidden.
      if(exportDepth>0 && isQuota(e) && (
        k.includes('rampFullTestV17Data')
        || isCheckpointKey(k)
        || k===primaryKeyForSession(activeMeta()?.id)
      )){
        ignoredQuotaWrites++;
        lastQuotaKey=k;
        lastQuotaAtMs=Date.now();
        console.warn('V2.2.5 export quota guard:',k);
        return;
      }
      throw e;
    }
  };

  Storage.prototype.getItem=function(key){
    const raw=nativeGet.call(this,key);
    if(!isLocal(this)||!raw||!isCheckpointKey(key))return raw;

    const cp=parseJson(raw);
    if(!cp||cp.envelope||!S(cp.envelopeRef))return raw;

    try{
      const envRaw=nativeGet.call(root.localStorage,S(cp.envelopeRef));
      const env=parseJson(envRaw);
      if(!env)return raw;
      hydratedReads++;
      return JSON.stringify({...cp,envelope:env});
    }catch(_){
      return raw;
    }
  };

  function migrateExistingCheckpoints(){
    let n=0;
    try{
      const keys=[];
      for(let i=0;i<root.localStorage.length;i++){
        const k=root.localStorage.key(i);
        if(isCheckpointKey(k))keys.push(k);
      }
      for(const k of keys){
        const raw=nativeGet.call(root.localStorage,k);
        if(!raw)continue;
        const c=compactCheckpointValue(k,raw);
        if(c.changed){
          try{
            nativeSet.call(root.localStorage,k,c.value);
            n++;
          }catch(e){
            console.warn('V2.2.5 checkpoint migration',e);
          }
        }
      }
    }catch(e){
      console.warn('V2.2.5 checkpoint scan',e);
    }
    compactedCount+=n;
    if(n)lastCompactAtMs=Date.now();
    return n;
  }

  async function preExportSave(){
    try{
      // Blur an active field so input/change handlers commit their current value.
      const a=document.activeElement;
      if(a&&['INPUT','TEXTAREA','SELECT'].includes(a.tagName))a.blur();
    }catch(_){}
    await new Promise(r=>setTimeout(r,0));

    try{
      if(typeof root.sagsV22SaveNow==='function')root.sagsV22SaveNow('pre-export-signature');
      else if(typeof root.persist==='function')root.persist();
    }catch(e){
      if(!isQuota(e))console.warn('V2.2.5 pre-export save',e);
    }
    await new Promise(r=>setTimeout(r,20));
  }

  async function inExportWindow(fn,args,self){
    exportDepth++;
    try{
      await preExportSave();
      return await fn.apply(self,args);
    }finally{
      // Keep the guard alive only long enough for export modal preparation.
      setTimeout(()=>{exportDepth=Math.max(0,exportDepth-1)},350);
    }
  }

  function patchOpenExport(){
    const base=root.openExportChoiceMenu;
    if(typeof base!=='function'||base.__v225SignatureExport)return false;

    const wrapped=async function(){
      return inExportWindow(base,arguments,this);
    };
    wrapped.__v225SignatureExport=1;
    wrapped.__v225Base=base;
    root.openExportChoiceMenu=wrapped;
    try{openExportChoiceMenu=wrapped}catch(_){}
    return true;
  }

  function patchV310Export(){
    const base=root.v310ExportAssignment;
    if(typeof base!=='function'||base.__v225SignatureExport)return false;

    const wrapped=async function(aid){
      aid=S(aid);
      const meta=activeMeta();
      const activeAid=S(meta?.rosterAssignmentId);

      // The old function closes the cockpit and switchFlightSession(meta.id)
      // even when the operator is already on that exact form. Right after signing
      // this unnecessary reload can read an older local copy. For the same active
      // assignment, export directly from current in-memory state instead.
      if(aid&&activeAid&&aid===activeAid&&typeof root.openExportChoiceMenu==='function'){
        return inExportWindow(root.openExportChoiceMenu,[/* no args */],this);
      }

      return inExportWindow(base,arguments,this);
    };
    wrapped.__v225SignatureExport=1;
    wrapped.__v225Base=base;
    root.v310ExportAssignment=wrapped;
    try{v310ExportAssignment=wrapped}catch(_){}
    return true;
  }

  function install(){
    patchOpenExport();
    patchV310Export();
  }

  // Migrate old duplicate full checkpoints once after startup; not on every export.
  setTimeout(()=>migrateExistingCheckpoints(),350);
  install();
  setTimeout(install,250);
  setTimeout(install,700);
  setTimeout(install,1500);
  setTimeout(install,3000);
  window.addEventListener('pageshow',()=>setTimeout(install,60),{passive:true});
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)setTimeout(install,60)},{passive:true});

  root.sagsV225StorageDiagnostics=function(){
    let checkpointCount=0,checkpointBytes=0,primaryBytes=0;
    try{
      for(let i=0;i<root.localStorage.length;i++){
        const k=root.localStorage.key(i),v=nativeGet.call(root.localStorage,k)||'';
        if(isCheckpointKey(k)){checkpointCount++;checkpointBytes+=v.length}
      }
      const pk=primaryKeyForSession(activeMeta()?.id);
      if(pk)primaryBytes=(nativeGet.call(root.localStorage,pk)||'').length;
    }catch(_){}
    return {
      build:BUILD,
      checkpointCount,checkpointBytes,activePrimaryBytes:primaryBytes,
      compactedCount,hydratedReads,ignoredQuotaWrites,lastQuotaKey,lastQuotaAtMs,lastCompactAtMs,
      exportDepth,
      openExportPatched:!!root.openExportChoiceMenu?.__v225SignatureExport,
      v310ExportPatched:!!root.v310ExportAssignment?.__v225SignatureExport
    };
  };
})(typeof window!=='undefined'?window:globalThis);
