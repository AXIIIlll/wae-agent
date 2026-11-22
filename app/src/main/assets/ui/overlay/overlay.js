/* ============================================================
   WAE 懸浮窗 · 互動（極簡白底 v3）
   橋接：window.WaeBridge（Android WebView 注入）；無橋接時用 mock。
   ============================================================ */
(function () {
  'use strict';

  var bridge = window.WaeBridge || null;
  var isMock = !bridge;

  var el = {
    panel: document.getElementById('panel'),
    bubble: document.getElementById('bubble'),
    bubbleDot: document.getElementById('bubbleDot'),
    busyDot: document.getElementById('busyDot'),
    statusText: document.getElementById('statusText'),
    progressBar: document.getElementById('progressBar'),
    metaContext: document.getElementById('metaContext'),
    metaElapsed: document.getElementById('metaElapsed'),
    stepList: document.getElementById('stepList'),
    taskEmpty: document.getElementById('taskEmpty'),
    cmdInput: document.getElementById('cmdInput'),
    composer: document.getElementById('composer'),
    btnSend: document.getElementById('btnSend'),
    btnStop: document.getElementById('btnStop'),
    btnCollapse: document.getElementById('btnCollapse'),
    btnClose: document.getElementById('btnClose')
  };

  var state = { busy: false, status: '待命', pkg: '', posture: '', phase: 'IDLE' };
  var steps = [];
  var lastStatus = '';
  var startedAt = 0;
  var mock = { busy: false, status: '待命', pkg: 'com.google.android.apps.maps', posture: 'SEARCH_RESULTS' };

  function nowTime() {
    var d = new Date();
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }
  function elapsed() {
    return ((Date.now() - startedAt) / 1000).toFixed(1) + 's';
  }

  function render() {
    var busy = !!state.busy;
    el.busyDot.className = 'wae-dot' + (busy ? ' on' : '');
    el.statusText.textContent = state.status || '待命';
    var ctx = state.pkg || '';
    if (state.posture) ctx += ' · ' + state.posture;
    el.metaContext.textContent = ctx || '—';
    el.metaElapsed.textContent = busy ? elapsed() : '';
    el.progressBar.style.width = busy ? '60%' : '0%';
    el.bubbleDot.className = 'wae-bubble-dot' + (busy ? ' on' : '');
    el.btnSend.disabled = busy;
    el.btnSend.hidden = busy;
    el.btnStop.hidden = !busy;
  }

  function renderSteps() {
    if (!steps.length) {
      el.stepList.innerHTML = '';
      var empty = document.createElement('div');
      empty.className = 'wae-empty';
      empty.textContent = '輸入指令開始';
      el.stepList.appendChild(empty);
      return;
    }
    var html = '';
    for (var i = 0; i < steps.length; i++) {
      var s = steps[i];
      var mark = s.kind === 'ok' ? '✓' : (s.kind === 'fail' ? '✕' : '·');
      html += '<div class="wae-step ' + s.kind + '">' +
        '<span class="wae-step-dot">' + mark + '</span>' +
        '<span class="wae-step-title">' + s.text + '</span>' +
        '<span class="wae-step-time">' + s.at + '</span>' +
        '</div>';
    }
    el.stepList.innerHTML = html;
    el.stepList.scrollTop = el.stepList.scrollHeight;
  }

  function classify(text) {
    if (/^[✓✅]/.test(text)) return 'ok';
    if (/^[⚠❌⛔]/.test(text) || /失敗|FAIL/.test(text)) return 'fail';
    return 'run';
  }

  function addStep(text, kind) {
    if (!text) return;
    var last = steps[steps.length - 1];
    if (last && last.text === text) return;
    // 只保留純文字：移除常見狀態符號，保持列表乾淨
    var clean = text.replace(/^[✓✅⚠❌⛔⏳🔄⏹▶▸\s]+/, '').trim();
    if (!clean) return;
    steps.push({ text: clean, kind: kind || 'run', at: nowTime() });
    if (steps.length > 12) steps.shift();
    renderSteps();
  }

  function readState() {
    if (isMock) {
      state.busy = mock.busy;
      state.status = mock.status;
      state.pkg = mock.pkg;
      state.posture = mock.posture;
      return;
    }
    try {
      var raw = bridge.stateJson();
      if (!raw) return;
      var o = JSON.parse(raw);
      state.busy = !!o.busy;
      state.status = o.status || state.status;
      state.pkg = o.pkg || '';
      state.posture = o.posture || '';
    } catch (e) { /* native 尚未就緒 */ }
  }

  function tick() {
    var wasBusy = state.busy;
    readState();
    if (state.busy && !wasBusy) {
      startedAt = Date.now();
      steps = [];
    }
    if (!state.busy && wasBusy) {
      addStep(state.status || '結束', classify(state.status));
    }
    if (state.status && state.status !== lastStatus) {
      lastStatus = state.status;
      // 待命類狀態只顯示在標題列，不佔步驟列表（列表只放真的做過/正在做的事）
      var kind = classify(state.status);
      if (state.busy || kind !== 'run') addStep(state.status, kind);
    }
    render();
  }

  function submit() {
    var text = (el.cmdInput.value || '').trim();
    if (!text) return;
    if (text === '停止') { doCancel(); el.cmdInput.value = ''; return; }
    addStep(text, 'run');
    if (isMock) {
      mock.busy = true;
      mock.status = '執行中';
      setTimeout(function () { mock.busy = false; mock.status = '完成'; }, 1500);
    } else {
      try { bridge.execute(text); } catch (e) { /* ignore */ }
    }
    el.cmdInput.value = '';
    el.cmdInput.blur();
  }

  function doCancel() {
    try { if (bridge) bridge.cancel(); } catch (e) { /* ignore */ }
  }

  el.composer.addEventListener('submit', function (e) { e.preventDefault(); submit(); });
  el.btnStop.addEventListener('click', doCancel);
  el.cmdInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); submit(); }
  });
  el.btnCollapse.addEventListener('click', function () {
    el.panel.hidden = true;
    el.bubble.hidden = false;
    try { if (bridge) bridge.setCollapsed(true); } catch (e) { /* ignore */ }
  });
  window.__waeExpand = function () { expand(false); };

  function expand(withFocus) {
    el.bubble.hidden = true;
    el.panel.hidden = false;
    try { if (bridge) bridge.setCollapsed(false); } catch (e) { /* ignore */ }
    if (withFocus) {
      setTimeout(function () { try { el.cmdInput.focus(); } catch (e) {} }, 90);
    }
  }

  // 泡泡：拖曳移動；長按（500ms）＝展開並直接輸入；點一下＝展開看狀態
  var bTouch = null;
  el.bubble.addEventListener('touchstart', function (e) {
    var t = e.touches[0];
    bTouch = { sx: t.clientX, sy: t.clientY, moved: false, at: Date.now(), timer: null };
    bTouch.timer = setTimeout(function () {
      if (bTouch && !bTouch.moved) { bTouch.longPressed = true; expand(true); }
    }, 500);
  }, { passive: true });

  el.bubble.addEventListener('touchmove', function (e) {
    if (!bTouch) return;
    var t = e.touches[0];
    if (!bTouch.moved && Math.abs(t.clientX - bTouch.sx) + Math.abs(t.clientY - bTouch.sy) > 6) {
      bTouch.moved = true;
      if (bTouch.timer) clearTimeout(bTouch.timer);
      try { if (bridge) bridge.dragStart(); } catch (err) {}
    }
    if (bTouch.moved) {
      e.preventDefault();
      try { if (bridge) bridge.dragBy(t.clientX - bTouch.sx, t.clientY - bTouch.sy); } catch (err) {}
    }
  }, { passive: false });

  el.bubble.addEventListener('touchend', function () {
    if (!bTouch) return;
    if (bTouch.timer) clearTimeout(bTouch.timer);
    var quick = (Date.now() - bTouch.at) < 500;
    if (!bTouch.moved && !bTouch.longPressed && quick) expand(false);
    bTouch = null;
  });
  el.bubble.addEventListener('touchcancel', function () {
    if (bTouch && bTouch.timer) clearTimeout(bTouch.timer);
    bTouch = null;
  });
  el.btnClose.addEventListener('click', function () {
    try { if (bridge) bridge.close(); } catch (e) { /* ignore */ }
  });

  // 標題列拖曳 → 原生移動視窗
  var dragZone = document.getElementById('dragZone');
  var touch = null;
  dragZone.addEventListener('touchstart', function (e) {
    var t = e.touches[0];
    touch = { x: t.clientX, y: t.clientY, sx: t.clientX, sy: t.clientY, moved: false };
  }, { passive: true });
  dragZone.addEventListener('touchmove', function (e) {
    if (!touch) return;
    var t = e.touches[0];
    if (!touch.moved && Math.abs(t.clientX - touch.x) + Math.abs(t.clientY - touch.y) > 6) {
      touch.moved = true;
      touch.sx = t.clientX;
      touch.sy = t.clientY;
      try { if (bridge) bridge.dragStart(); } catch (err) { /* ignore */ }
    }
    if (touch.moved) {
      e.preventDefault();
      try { if (bridge) bridge.dragBy(t.clientX - touch.sx, t.clientY - touch.sy); } catch (err) { /* ignore */ }
    }
  }, { passive: false });
  dragZone.addEventListener('touchend', function () { touch = null; });
  dragZone.addEventListener('touchcancel', function () { touch = null; });

  // 初始化
  render();
  tick();
  setInterval(tick, 700);

  // 預設就是小幽靈（面板收合）
  el.panel.hidden = true;
  el.bubble.hidden = false;
  try { if (bridge) bridge.setCollapsed(true); } catch (e) { /* ignore */ }
  if (location.hash === '#busy') {
    mock.busy = true;
    mock.status = '正在點選「路線」';
    readState();
    startedAt = Date.now() - 2600;
    steps = [];
    addStep('打開地圖', 'ok');
    addStep('完成 Google 地圖', 'ok');
    addStep('搜尋臺北101', 'ok');
    addStep('送出搜尋', 'ok');
    addStep('正在點選「路線」', 'run');
    render();
  }
})();
