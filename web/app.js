(() => {
"use strict";
const MW=800,MH=288,ROWS=18,LANES=4,GRID=200,NOLANE=200;
const ANCH=[121,131,141,150,160,170,180,189,199,209,219,228,238,248,258,267,277,287];
const $=id=>document.getElementById(id),video=$("camera"),overlay=$("overlay"),ctx=overlay.getContext("2d");
const mc=$("modelCanvas"),mctx=mc.getContext("2d",{willReadFrequently:true});
const start=$("startButton"),center=$("centerButton"),sound=$("soundButton"),settings=$("settingsButton"),panel=$("settingsPanel");
const roi=$("roiSlider"),smooth=$("smoothSlider"),roiVal=$("roiValue"),smoothVal=$("smoothValue");
const modelState=$("modelState"),laneState=$("laneState"),aiState=$("aiState"),sys=$("systemState"),msg=$("mainMessage"),sub=$("subMessage");
let stream=null,session=null,tensor=null,running=false,busy=false,lastEnd=0,lastGood=0,lastState=null,leftS=null,rightS=null,rawOffset=0;
let neutral=Number(localStorage.getItem("tk_lane_neutral")||"0"),soundOn=localStorage.getItem("tk_sound")!=="0",lastSpeak=0,departFrames=0,wakeLock=null;
const input=new Float32Array(3*MW*MH),mean=[.485,.456,.406],std=[.229,.224,.225];
roi.value=localStorage.getItem("tk_roi_center")||"60"; smooth.value=localStorage.getItem("tk_smooth")||"65";
function labels(){roiVal.textContent=roi.value+"%";smoothVal.textContent=smooth.value+"%";sound.textContent="ÂM THANH: "+(soundOn?"BẬT":"TẮT")} labels();
function status(t,m="ok"){sys.textContent=t;sys.className="pill"+(m==="warn"?" warn":m==="bad"?" bad":"")}
function ios(){return /iPhone|iPad|iPod/i.test(navigator.userAgent)||(navigator.platform==="MacIntel"&&navigator.maxTouchPoints>1)}
async function wake(){try{if("wakeLock"in navigator)wakeLock=await navigator.wakeLock.request("screen")}catch(_){}}
async function camera(){
  if(stream)return;
  stream=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:"environment"},width:{ideal:1920},height:{ideal:1080},frameRate:{ideal:30,max:30}}});
  video.srcObject=stream;await video.play();
  if(!video.videoWidth)await new Promise(r=>video.addEventListener("loadedmetadata",r,{once:true}));
}
async function loadModel(){
  if(session)return;
  modelState.textContent="TẢI...";status("ĐANG TẢI MODEL","warn");msg.textContent="Đang tải UFLD CULane";sub.textContent="Model khoảng 170 MB. Giữ Safari mở trong lần tải đầu.";
  ort.env.wasm.numThreads=1;ort.env.wasm.simd=true;ort.env.wasm.wasmPaths="https://cdn.jsdelivr.net/npm/onnxruntime-web@1.29.0/dist/";
  session=await ort.InferenceSession.create("./models/lane_core.onnx",{executionProviders:["wasm"],graphOptimizationLevel:"all"});
  tensor=new ort.Tensor("float32",input,[1,3,MH,MW]);modelState.textContent="UFLD";
}
function resize(){
  const d=Math.min(devicePixelRatio||1,2),w=Math.max(1,Math.floor(innerWidth*d)),h=Math.max(1,Math.floor(innerHeight*d));
  if(overlay.width!==w||overlay.height!==h){overlay.width=w;overlay.height=h;overlay.style.width=innerWidth+"px";overlay.style.height=innerHeight+"px"}
  ctx.setTransform(d,0,0,d,0,0);
}
function crop(){
  const vw=video.videoWidth,vh=video.videoHeight,ta=MW/MH;let cw=vw,ch=cw/ta;if(ch>vh){ch=vh;cw=ch*ta}
  let cx=(vw-cw)/2,cy=Number(roi.value)/100*vh-ch/2;cx=Math.max(0,Math.min(vw-cw,cx));cy=Math.max(0,Math.min(vh-ch,cy));
  return{cx,cy,cw,ch,vw,vh};
}
function prep(){
  const c=crop();mctx.drawImage(video,c.cx,c.cy,c.cw,c.ch,0,0,MW,MH);const rgba=mctx.getImageData(0,0,MW,MH).data,p=MW*MH;
  for(let i=0,j=0;i<p;i++,j+=4){input[i]=(rgba[j]/255-mean[0])/std[0];input[p+i]=(rgba[j+1]/255-mean[1])/std[1];input[2*p+i]=(rgba[j+2]/255-mean[2])/std[2]}
  return c;
}
const ix=(g,r,l)=>((g*ROWS)+r)*LANES+l;
function decode(a){
  const lanes=Array.from({length:LANES},()=>Array(ROWS).fill(null)),conf=new Float32Array(LANES),cw=(MW-1)/(GRID-1);
  for(let l=0;l<LANES;l++){let valid=0;
    for(let pn=0;pn<ROWS;pn++){const sr=ROWS-1-pn;let ac=0,al=-Infinity;
      for(let g=0;g<=NOLANE;g++){const v=a[ix(g,sr,l)];if(v>al){al=v;ac=g}}
      if(ac===NOLANE)continue;
      let mx=-Infinity;for(let g=0;g<GRID;g++)mx=Math.max(mx,a[ix(g,sr,l)]);
      let den=0,wt=0;for(let g=0;g<GRID;g++){const e=Math.exp(a[ix(g,sr,l)]-mx);den+=e;wt+=(g+1)*e}
      if(!(den>1e-12))continue;
      const loc=wt/den,xp=Math.max(0,Math.min(MW-1,loc*cw-1)),x=xp/(MW-1),ai=ROWS-1-pn,y=ANCH[ai]/(MH-1);
      lanes[l][pn]={x,y};valid++;
    }conf[l]=valid>2?valid/ROWS:0;
  }return{lanes,conf};
}
function fit(ps){
  if(ps.length<5)return null;let s0=0,s1=0,s2=0,s3=0,s4=0,t0=0,t1=0,t2=0;
  for(const p of ps){const y=p.y,x=p.x,y2=y*y;s0++;s1+=y;s2+=y2;s3+=y2*y;s4+=y2*y2;t0+=x;t1+=x*y;t2+=x*y2}
  const m=[[s4,s3,s2,t2],[s3,s2,s1,t1],[s2,s1,s0,t0]];
  for(let c=0;c<3;c++){let q=c;for(let r=c+1;r<3;r++)if(Math.abs(m[r][c])>Math.abs(m[q][c]))q=r;if(Math.abs(m[q][c])<1e-9)return null;[m[c],m[q]]=[m[q],m[c]];
    const d=m[c][c];for(let j=c;j<4;j++)m[c][j]/=d;for(let r=0;r<3;r++)if(r!==c){const f=m[r][c];for(let j=c;j<4;j++)m[r][j]-=f*m[c][j]}}
  return{a:m[0][3],b:m[1][3],c:m[2][3]};
}
const xat=(q,y)=>q.a*y*y+q.b*y+q.c;
function geom(l,r){for(const y of [.55,.70,.82,.94]){const w=xat(r,y)-xat(l,y);if(w<.08||w>.90)return false}return xat(l,.9)<.64&&xat(r,.9)>.36}
function blend(o,n,a){if(!o)return n;const k=1-a;return{a:o.a*k+n.a*a,b:o.b*k+n.b*a,c:o.c*k+n.c*a}}
function interpret(d,c){
  const lp=d.lanes[1].filter(Boolean),rp=d.lanes[2].filter(Boolean),cf=Math.min(d.conf[1],d.conf[2]),l=fit(lp),r=fit(rp),now=performance.now();
  if(l&&r&&cf>=.18&&geom(l,r)){const sp=Number(smooth.value)/100,a=Math.max(.12,Math.min(.65,1-sp+cf*.18));leftS=blend(leftS,l,a);rightS=blend(rightS,r,a);lastGood=now}
  else if(now-lastGood>900){leftS=null;rightS=null}
  if(!leftS||!rightS||!geom(leftS,rightS))return{ok:false,conf:0,crop:c};
  const age=Math.max(0,Math.min(1,1-(now-lastGood)/900)),sc=Math.max(0,Math.min(1,cf*age)),y=.82,lx=xat(leftS,y),rx=xat(rightS,y),w=Math.max(.05,rx-lx);
  rawOffset=Math.max(-2,Math.min(2,(.5-(lx+rx)*.5)/(w*.5)));const off=Math.max(-2,Math.min(2,rawOffset-neutral));
  return{ok:true,left:leftS,right:rightS,conf:sc,offset:off,crop:c};
}
function toScreen(sx,sy,c){const s=Math.max(innerWidth/c.vw,innerHeight/c.vh),rw=c.vw*s,rh=c.vh*s,ox=(innerWidth-rw)/2,oy=(innerHeight-rh)/2;return{x:ox+sx*s,y:oy+sy*s}}
function mp(x,y,c){return toScreen(c.cx+x*c.cw,c.cy+y*c.ch,c)}
function curve(q,c){
  ctx.beginPath();let f=true;for(let y=.44;y<=.99;y+=.025){const x=xat(q,y);if(!Number.isFinite(x)||x<-.1||x>1.1)continue;const p=mp(x,y,c);if(f){ctx.moveTo(p.x,p.y);f=false}else ctx.lineTo(p.x,p.y)}ctx.stroke();
}
function draw(s){
  if(!s?.ok)return;const L=[],R=[];for(let y=.48;y<=.98;y+=.035){const lx=xat(s.left,y),rx=xat(s.right,y);if(Number.isFinite(lx)&&Number.isFinite(rx)){L.push(mp(lx,y,s.crop));R.push(mp(rx,y,s.crop))}}
  if(L.length<3)return;const danger=Math.abs(s.offset)>=.32&&s.conf>=.30;ctx.save();ctx.fillStyle=danger?"rgba(255,73,73,.18)":"rgba(31,210,129,.13)";
  ctx.beginPath();ctx.moveTo(L[0].x,L[0].y);for(let i=1;i<L.length;i++)ctx.lineTo(L[i].x,L[i].y);for(let i=R.length-1;i>=0;i--)ctx.lineTo(R[i].x,R[i].y);ctx.closePath();ctx.fill();
  ctx.strokeStyle=danger?"rgba(255,80,80,.96)":"rgba(39,242,142,.96)";ctx.lineWidth=5;ctx.lineCap="round";ctx.lineJoin="round";ctx.shadowColor="rgba(0,0,0,.75)";ctx.shadowBlur=5;curve(s.left,s.crop);curve(s.right,s.crop);ctx.restore();
}
function drawCrop(c){if(panel.classList.contains("hidden")||!c)return;const p1=toScreen(c.cx,c.cy,c),p2=toScreen(c.cx+c.cw,c.cy+c.ch,c);ctx.save();ctx.strokeStyle="rgba(55,170,255,.8)";ctx.lineWidth=2;ctx.setLineDash([8,6]);ctx.strokeRect(p1.x,p1.y,p2.x-p1.x,p2.y-p1.y);ctx.restore()}
function speak(side){
  if(!soundOn||performance.now()-lastSpeak<4200)return;lastSpeak=performance.now();try{speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(side==="left"?"Cảnh báo lệch làn bên trái.":"Cảnh báo lệch làn bên phải.");u.lang="vi-VN";u.rate=1.04;u.pitch=1.05;
    const vs=speechSynthesis.getVoices().filter(v=>/^vi/i.test(v.lang||"")),pv=vs.find(v=>/linh|mai|female|nữ/i.test(v.name||""))||vs[0];if(pv)u.voice=pv;speechSynthesis.speak(u)}catch(_){}
}
function ui(s){
  if(!s?.ok||s.conf<.22){laneState.textContent="MẤT";msg.textContent="Chưa khóa được hai vạch làn";sub.textContent="Bấm CHỈNH nếu cần đổi vùng đường.";departFrames=0;return}
  const pc=Math.round(s.conf*100);laneState.textContent=pc+"%";center.disabled=false;
  if(Math.abs(s.offset)>=.32&&s.conf>=.30){departFrames++;const side=s.offset>=0?"right":"left";msg.textContent=side==="left"?"⚠ LỆCH LÀN BÊN TRÁI":"⚠ LỆCH LÀN BÊN PHẢI";sub.textContent=`Offset ${s.offset.toFixed(2)} · UFLD ${pc}%`;if(departFrames>=2)speak(side)}
  else{departFrames=0;msg.textContent="ĐANG GIỮ LÀN";sub.textContent=`Offset ${s.offset.toFixed(2)} · UFLD ${pc}%`}
}
async function infer(){
  if(!running||busy||!session||video.readyState<2)return;busy=true;try{const c=prep(),st=performance.now(),feeds={};feeds[session.inputNames[0]]=tensor;const res=await session.run(feeds),ms=performance.now()-st;lastEnd=performance.now();aiState.textContent=Math.round(ms)+" ms";const out=res[session.outputNames[0]],d=decode(out.data);lastState=interpret(d,c);ui(lastState)}
  catch(e){console.error(e);status("LỖI AI","bad");msg.textContent="UFLD gặp lỗi";sub.textContent=String(e?.message||e);running=false}finally{busy=false}
}
function loop(){resize();ctx.clearRect(0,0,innerWidth,innerHeight);draw(lastState);drawCrop(lastState?.crop||(video.videoWidth?crop():null));if(running&&session&&!busy&&performance.now()-lastEnd>=(ios()?110:70))infer();requestAnimationFrame(loop)}
async function begin(){
  if(running)return;start.disabled=true;start.textContent="ĐANG MỞ...";status("ĐANG KHỞI ĐỘNG","warn");
  try{await camera();await wake();start.textContent="ĐANG TẢI AI";await loadModel();running=true;status("UFLD ĐANG CHẠY");msg.textContent="UFLD CULane đã sẵn sàng";sub.textContent="Xe đang giữa làn thì bấm CÂN GIỮA một lần.";start.textContent="ĐANG CHẠY"}
  catch(e){console.error(e);status("KHÔNG KHỞI ĐỘNG","bad");msg.textContent="Không mở được camera/model";sub.textContent=String(e?.message||e);start.disabled=false;start.textContent="THỬ LẠI"}
}
start.addEventListener("click",begin);
center.addEventListener("click",()=>{if(!lastState?.ok)return;neutral=rawOffset;localStorage.setItem("tk_lane_neutral",String(neutral));msg.textContent="ĐÃ CÂN GIỮA LÀN";sub.textContent=`Neutral ${neutral.toFixed(3)} đã lưu.`});
sound.addEventListener("click",()=>{soundOn=!soundOn;localStorage.setItem("tk_sound",soundOn?"1":"0");labels();if(soundOn)try{const u=new SpeechSynthesisUtterance("Âm thanh cảnh báo đã bật.");u.lang="vi-VN";speechSynthesis.speak(u)}catch(_){}});
settings.addEventListener("click",()=>panel.classList.toggle("hidden"));
roi.addEventListener("input",()=>{localStorage.setItem("tk_roi_center",roi.value);labels()});smooth.addEventListener("input",()=>{localStorage.setItem("tk_smooth",smooth.value);labels()});
addEventListener("resize",resize);addEventListener("orientationchange",()=>setTimeout(resize,250));document.addEventListener("visibilitychange",()=>{if(document.visibilityState==="visible"&&running)wake()});
if("serviceWorker"in navigator)addEventListener("load",()=>navigator.serviceWorker.register("./sw.js").catch(()=>{}));
if(!navigator.mediaDevices?.getUserMedia){status("SAFARI KHÔNG HỖ TRỢ","bad");msg.textContent="Trình duyệt không mở được camera";sub.textContent="Hãy mở bằng Safari trên iPhone qua HTTPS.";start.disabled=true}
loop();
})();