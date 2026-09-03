(() => {
  "use strict";

  const TRIAL_MS = 5 * 60 * 1000;
  const PREFIX = "DG12";
  const PUBLIC_KEY_DER_B64 =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHbkF3spSsePMGGCV1ccOxIE7lhYe5LfUK0wnTarf48icE9SR9L4KsKRMmSw3/KQ5Pgt0JhQBPCYyKAE0oGGuXQ==";

  const K_INSTALL = "tk_web_install_id_v1";
  const K_TRIAL = "tk_web_trial_used_ms_v1";
  const K_LICENSE = "tk_web_license_v1";

  let deviceCode = "ĐANG TẠO...";
  let trialUsed = Math.max(0, Number(localStorage.getItem(K_TRIAL) || "0"));
  let licensed = false;
  let expiryDay = 0;
  let usageActive = false;
  let lastTick = performance.now();

  let modal, statusEl, codeEl, inputEl, noteEl, keyButton;
  const enc = new TextEncoder();

  function b64ToBytes(s) {
    const bin = atob(s.replace(/\s+/g, ""));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function b64uToBytes(s) {
    s = s.replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4) s += "=";
    return b64ToBytes(s);
  }

  function readDerLen(b, st) {
    let n = b[st.i++];
    if ((n & 0x80) === 0) return n;
    const count = n & 0x7f;
    if (count < 1 || count > 4) throw new Error("DER length invalid");
    n = 0;
    for (let k = 0; k < count; k++) n = (n << 8) | b[st.i++];
    return n;
  }

  function derToRaw(sig) {
    const b = sig instanceof Uint8Array ? sig : new Uint8Array(sig);
    const st = { i: 0 };
    if (b[st.i++] !== 0x30) throw new Error("ECDSA DER invalid");
    readDerLen(b, st);

    function readInt() {
      if (b[st.i++] !== 0x02) throw new Error("ECDSA INTEGER invalid");
      const len = readDerLen(b, st);
      let v = b.slice(st.i, st.i + len);
      st.i += len;
      while (v.length > 1 && v[0] === 0) v = v.slice(1);
      if (v.length > 32) throw new Error("ECDSA INTEGER too long");
      const out = new Uint8Array(32);
      out.set(v, 32 - v.length);
      return out;
    }

    const r = readInt();
    const s = readInt();
    const raw = new Uint8Array(64);
    raw.set(r, 0);
    raw.set(s, 32);
    return raw;
  }

  function randomId() {
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    return Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
  }

  async function buildDeviceCode() {
    let installId = localStorage.getItem(K_INSTALL);
    if (!installId) {
      installId = crypto.randomUUID ? crypto.randomUUID() : randomId();
      localStorage.setItem(K_INSTALL, installId);
    }

    const digest = new Uint8Array(
      await crypto.subtle.digest(
        "SHA-256",
        enc.encode(`${installId}|TRUNGKIEN-WEB-LICENSE`)
      )
    );

    const hex = Array.from(
      digest.slice(0, 8),
      x => x.toString(16).padStart(2, "0").toUpperCase()
    ).join("");

    return hex.match(/.{4}/g).join("-");
  }

  function todayDay() {
    return Math.floor(Date.now() / 86400000);
  }

  function remaining() {
    return licensed ? Infinity : Math.max(0, TRIAL_MS - trialUsed);
  }

  function fmtMs(ms) {
    if (!Number.isFinite(ms)) return "ĐÃ KÍCH HOẠT";
    const sec = Math.max(0, Math.ceil(ms / 1000));
    return `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
  }

  function fmtDate(day) {
    if (!day) return "VĨNH VIỄN";
    return new Date(day * 86400000).toLocaleDateString("vi-VN", {
      timeZone: "UTC",
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    });
  }

  async function verify(raw) {
    try {
      const key = String(raw || "").trim().replace(/[\r\n]/g, "");
      const parts = key.split(".");
      if (parts.length !== 2) return null;

      const payload = b64uToBytes(parts[0]);
      const sigDer = b64uToBytes(parts[1]);
      const fields = new TextDecoder().decode(payload).split("|");

      if (fields.length !== 4) return null;
      if (fields[0] !== PREFIX) return null;
      if (fields[1] !== deviceCode) return null;

      const exp = Number(fields[2]);
      if (!Number.isFinite(exp) || exp < 0) return null;
      if (exp > 0 && todayDay() > exp) return null;

      const pub = await crypto.subtle.importKey(
        "spki",
        b64ToBytes(PUBLIC_KEY_DER_B64),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"]
      );

      const ok = await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" },
        pub,
        derToRaw(sigDer),
        payload
      );

      return ok ? { raw: key, expiry: exp } : null;
    } catch (e) {
      console.warn("TRUNGKIEN license verify:", e);
      return null;
    }
  }

  async function loadStoredKey() {
    const raw = localStorage.getItem(K_LICENSE);
    if (!raw) return;
    const v = await verify(raw);
    if (!v) {
      localStorage.removeItem(K_LICENSE);
      return;
    }
    licensed = true;
    expiryDay = v.expiry;
  }

  function stopProtectedUse() {
    usageActive = false;

    const video = document.getElementById("camera");
    try {
      if (video && video.srcObject) {
        video.srcObject.getTracks().forEach(t => t.stop());
      }
      if (video) video.srcObject = null;
    } catch (_) {}

    const start = document.getElementById("startButton");
    if (start) {
      start.disabled = false;
      start.textContent = "NHẬP KEY";
    }

    const center = document.getElementById("centerButton");
    if (center) center.disabled = true;

    const sys = document.getElementById("systemState");
    if (sys) {
      sys.textContent = "HẾT DÙNG THỬ";
      sys.className = "pill bad";
    }

    const msg = document.getElementById("mainMessage");
    const sub = document.getElementById("subMessage");
    if (msg) msg.textContent = "HẾT 5 PHÚT DÙNG THỬ";
    if (sub) sub.textContent = "Nhập key Admin để tiếp tục camera/AI.";
  }

  function accumulate() {
    const now = performance.now();
    const delta = Math.max(0, Math.min(15000, now - lastTick));
    lastTick = now;

    if (!usageActive || licensed || document.visibilityState !== "visible") return;

    trialUsed = Math.min(TRIAL_MS, trialUsed + delta);
    localStorage.setItem(K_TRIAL, String(Math.round(trialUsed)));

    if (remaining() <= 0) {
      stopProtectedUse();
      render();
      show();
    }
  }

  function injectUi() {
    const style = document.createElement("style");
    style.textContent = `
      .tk-key-btn{
        width:100%;margin-top:12px;min-height:44px;border-radius:11px;
        border:1px solid rgba(255,255,255,.18);background:rgba(20,112,221,.9);
        color:#fff;font-weight:900;font-size:11px
      }
      .tk-key-bg{
        position:fixed;inset:0;z-index:99999;display:none;align-items:center;justify-content:center;
        padding:calc(env(safe-area-inset-top,0px) + 16px) 14px calc(env(safe-area-inset-bottom,0px) + 16px);
        background:rgba(0,0,0,.78);backdrop-filter:blur(12px)
      }
      .tk-key-bg.show{display:flex}
      .tk-key-card{
        width:min(94vw,480px);max-height:90vh;overflow:auto;padding:18px;border-radius:18px;
        background:#0b1119;border:1px solid rgba(255,255,255,.16);color:#fff
      }
      .tk-key-card h2{margin:0 0 4px;font-size:22px}
      .tk-key-sub{font-size:11px;opacity:.65;margin-bottom:14px}
      .tk-key-status{
        padding:11px 12px;border-radius:12px;background:rgba(255,255,255,.07);
        font-size:13px;line-height:1.45
      }
      .tk-device{
        margin-top:12px;padding:12px;border-radius:12px;background:#070b10;
        border:1px solid rgba(255,255,255,.12)
      }
      .tk-device small{display:block;font-size:9px;opacity:.6;letter-spacing:.8px}
      .tk-device strong{
        display:block;margin-top:5px;font-size:21px;letter-spacing:1px;user-select:all
      }
      .tk-copy{
        width:100%;margin-top:10px;min-height:44px;border-radius:11px;border:0;
        background:#1470dd;color:#fff;font-weight:900
      }
      .tk-key-card textarea{
        width:100%;min-height:90px;margin-top:12px;padding:11px;border-radius:12px;
        border:1px solid rgba(255,255,255,.16);background:#070b10;color:#fff;font-size:12px
      }
      .tk-actions{
        display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px
      }
      .tk-actions button{
        min-height:45px;border-radius:11px;border:1px solid rgba(255,255,255,.14);
        background:#1b2736;color:#fff;font-weight:800
      }
      .tk-actions .primary{background:#1470dd}
      .tk-note{min-height:18px;margin-top:8px;color:#ffcc66;font-size:11px}
      .tk-help{font-size:10px;opacity:.55;line-height:1.4}
    `;
    document.head.appendChild(style);

    modal = document.createElement("div");
    modal.className = "tk-key-bg";
    modal.innerHTML = `
      <div class="tk-key-card">
        <h2>TRUNGKIEN WEB KEY</h2>
        <div class="tk-key-sub">Safari / iPhone · dùng chung TRUNGKIEN ADMIN KEY</div>
        <div id="tkKeyStatus" class="tk-key-status"></div>
        <div class="tk-device">
          <small>MÃ THIẾT BỊ WEB</small>
          <strong id="tkDeviceCode">ĐANG TẠO...</strong>
          <button id="tkCopyDevice" class="tk-copy">SAO CHÉP MÃ THIẾT BỊ</button>
        </div>
        <textarea id="tkKeyInput" placeholder="DÁN KEY ADMIN VÀO ĐÂY"
          autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
        <div id="tkKeyNote" class="tk-note"></div>
        <div class="tk-actions">
          <button id="tkActivate" class="primary">KÍCH HOẠT KEY</button>
          <button id="tkClose">ĐÓNG</button>
          <button id="tkClear">XÓA KEY</button>
          <button id="tkCopyAgain">SAO CHÉP MÃ</button>
        </div>
        <p class="tk-help">
          Cập nhật phiên bản không đổi mã thiết bị. Nếu xóa dữ liệu Safari/dữ liệu website,
          mã có thể thay đổi và cần cấp key mới.
        </p>
      </div>
    `;
    document.body.appendChild(modal);

    statusEl = document.getElementById("tkKeyStatus");
    codeEl = document.getElementById("tkDeviceCode");
    inputEl = document.getElementById("tkKeyInput");
    noteEl = document.getElementById("tkKeyNote");

    const copyCode = async () => {
      try {
        await navigator.clipboard.writeText(deviceCode);
        noteEl.textContent = "Đã sao chép mã thiết bị. Gửi cho Admin cấp key.";
      } catch (_) {
        noteEl.textContent = "Giữ tay vào mã thiết bị để sao chép.";
      }
    };

    document.getElementById("tkCopyDevice").onclick = copyCode;
    document.getElementById("tkCopyAgain").onclick = copyCode;
    document.getElementById("tkClose").onclick = () => modal.classList.remove("show");

    document.getElementById("tkClear").onclick = () => {
      localStorage.removeItem(K_LICENSE);
      licensed = false;
      expiryDay = 0;
      noteEl.textContent = "Đã xóa key.";
      render();
    };

    document.getElementById("tkActivate").onclick = async () => {
      noteEl.textContent = "Đang kiểm tra key...";
      const v = await verify(inputEl.value);

      if (!v) {
        noteEl.textContent = "Key không hợp lệ, hết hạn hoặc không đúng mã thiết bị.";
        return;
      }

      localStorage.setItem(K_LICENSE, v.raw);
      licensed = true;
      expiryDay = v.expiry;
      usageActive = false;
      render();

      const start = document.getElementById("startButton");
      if (start) {
        start.disabled = false;
        start.textContent = "BẮT ĐẦU";
      }

      noteEl.textContent = "Kích hoạt thành công.";
      setTimeout(() => modal.classList.remove("show"), 500);
    };

    const settings = document.getElementById("settingsPanel");
    if (settings) {
      keyButton = document.createElement("button");
      keyButton.className = "tk-key-btn";
      keyButton.textContent = "KEY / MÃ THIẾT BỊ";
      keyButton.onclick = show;
      settings.appendChild(keyButton);
    }

    const sys = document.getElementById("systemState");
    if (sys) {
      sys.style.cursor = "pointer";
      sys.addEventListener("click", show);
    }
  }

  function render() {
    if (!statusEl) return;

    codeEl.textContent = deviceCode;

    if (licensed) {
      statusEl.innerHTML =
        `<strong>ĐÃ KÍCH HOẠT</strong><br>Hạn: ${fmtDate(expiryDay)}`;
      if (keyButton) keyButton.textContent = "KEY: ĐÃ KÍCH HOẠT";
      return;
    }

    if (remaining() > 0) {
      statusEl.innerHTML =
        `<strong>DÙNG THỬ 5 PHÚT</strong><br>Còn ${fmtMs(remaining())} thời gian camera/AI`;
      if (keyButton) keyButton.textContent = `KEY · TRIAL ${fmtMs(remaining())}`;
      return;
    }

    statusEl.innerHTML =
      "<strong>HẾT THỜI GIAN DÙNG THỬ</strong><br>Nhập key Admin để tiếp tục.";
    if (keyButton) keyButton.textContent = "NHẬP KEY ĐỂ TIẾP TỤC";
  }

  function show() {
    if (!modal) return;
    render();
    noteEl.textContent = "";
    modal.classList.add("show");
  }

  function protectStartClick(e) {
    const btn = e.target.closest && e.target.closest("#startButton");
    if (!btn) return;

    if (!licensed && remaining() <= 0) {
      e.preventDefault();
      e.stopImmediatePropagation();
      show();
      return;
    }

    usageActive = true;
    lastTick = performance.now();
  }

  async function init() {
    injectUi();
    deviceCode = await buildDeviceCode();
    await loadStoredKey();
    render();

    document.addEventListener("click", protectStartClick, true);

    setInterval(() => {
      accumulate();
      if (modal.classList.contains("show")) render();
    }, 1000);

    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") lastTick = performance.now();
      else accumulate();
    });

    if (!licensed && remaining() <= 0) {
      setTimeout(show, 250);
    }
  }

  init();
})();