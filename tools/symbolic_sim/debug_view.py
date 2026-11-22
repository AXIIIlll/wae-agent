"""可視化 v3：把符號環境畫成**真實比例的 Android 手機**（Material 3 外觀）。

- `render_ansi`：終端機即時觀看
- `build_html`：離線 HTML 播放器（384×832dp 手機框 + 狀態列/app bar/手勢列 + 播放控制）
- `build_storyboard`：PNG 分鏡（Pillow，真實位置與比例）

所有畫面內容都是**合成資料**（張三/測試訊息 1/會議 10:00…），與真機無關。
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List

from phone_env import (ACTION_BACK, ACTION_HOME, ACTION_SCROLL_DOWN, ACTION_SCROLL_UP,
                       ACTION_SHADE, ACTION_TAP_BASE, Node, Screen)

ANSI_RESET = "\x1b[0m"
ANSI_GREEN = "\x1b[32m"
ANSI_DIM = "\x1b[2m"


def action_name(action: int, screen: Screen | None = None) -> str:
    if action == ACTION_BACK:
        return "BACK（返回）"
    if action == ACTION_SCROLL_DOWN:
        return "SCROLL_DOWN（往上滑）"
    if action == ACTION_SCROLL_UP:
        return "SCROLL_UP（往下滑）"
    if action == ACTION_HOME:
        return "HOME（回首頁）"
    if action == ACTION_SHADE:
        return "SHADE（下拉快速設定）"
    slot = action - ACTION_TAP_BASE
    if screen:
        node = next((n for n in screen.nodes if n.slot == slot), None)
        if node:
            return f"TAP[{slot}] {node.label}"
    return f"TAP[{slot}]"


def _glyph(node: Node) -> str:
    return {"switch": "◉" if node.checked else "○", "slider": "▭", "icon": "▣",
            "row": "›", "list_item": "▤", "button": "▢", "text": "¶", "tile": "▦"}.get(node.family, "•")


def render_ansi(screen: Screen, highlight_slot: int = -1, footer: List[str] | None = None) -> str:
    lines = [f"┌─ {screen.screen_id}  {screen.chrome.app_title}".ljust(46) + "┐"]
    if screen.scroll_max:
        lines.append(f"│ 捲動 {screen.scroll_dp}/{screen.scroll_max} dp".ljust(46) + "│")
    for n in screen.visible_nodes():
        mark = "▶" if n.slot == highlight_slot else " "
        extra = ("（開）" if n.checked else "（關）") if n.family == "switch" else ""
        if n.family == "slider":
            extra = "  " + "▮" * (n.value + 1) + "▯" * (4 - n.value)
        lines.append("│ " + f"{mark}{_glyph(n)} [{n.slot}] {n.label}{extra}".ljust(42) + " │")
    lines.append("└" + "─" * 44 + "┘")
    for f in footer or []:
        lines.append(f)
    return "\n".join(lines)


def screen_payload(screen: Screen, device: dict | None = None) -> Dict[str, Any]:
    return {
        "id": screen.screen_id,
        "scroll": screen.scroll_dp,
        "scrollMax": screen.scroll_max,
        "chrome": {
            "time": screen.chrome.status_time,
            "title": screen.chrome.app_title,
            "back": screen.chrome.back,
            "nav": screen.chrome.nav_items,
            "battery": screen.chrome.battery,
            "shade": screen.chrome.shade,
        },
        "device": device or {"w_dp": 384, "h_dp": 832, "status_bar_dp": 24,
                             "app_bar_dp": 56, "gesture_bar_dp": 24},
        "nodes": [
            {"slot": n.slot, "family": n.family, "role": n.role, "label": n.label,
             "rect": n.rect.to_list(), "checked": n.checked, "value": n.value,
             "subtitle": n.subtitle}
            for n in screen.visible_nodes()
        ],
    }


# ── HTML 播放器（Material 3 外觀）────────────────────────────────────
HTML_TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-Hant"><head><meta charset="utf-8">
<title>WAE 符號模擬器 — 手機介面回放</title>
<style>
  :root{
    --m3-bg:#f7f9fc; --m3-surface:#ffffff; --m3-on:#1f1f1f; --m3-onvar:#49454f;
    --m3-primary:#386a20; --m3-primary-container:#b9f09a; --m3-outline:#c9c8cf;
    --m3-surface-2:#eef0f4; --m3-track-on:#386a20; --m3-track-off:#c9c8cf;
  }
  body{margin:0;background:#e9ecf2;font-family:"Segoe UI","Microsoft JhengHei",system-ui,sans-serif;color:var(--m3-on)}
  .wrap{display:flex;gap:20px;padding:20px;align-items:flex-start;flex-wrap:wrap}
  .phone{width:384px;height:832px;background:#0b0b0d;border-radius:44px;padding:10px;
         box-shadow:0 18px 40px rgba(0,0,0,.22);position:relative;flex:0 0 auto}
  .glass{position:relative;width:384px;height:832px;background:var(--m3-bg);
         border-radius:36px;overflow:hidden}
  .status{position:absolute;left:0;top:0;width:100%;height:24px;display:flex;
          align-items:center;justify-content:space-between;padding:0 16px;
          box-sizing:border-box;font-size:11px;color:var(--m3-onvar);background:var(--m3-bg);z-index:5}
  .appbar{position:absolute;left:0;top:24px;width:100%;height:56px;display:flex;
          align-items:center;padding:0 16px;box-sizing:border-box;background:var(--m3-bg);
          font-size:20px;font-weight:600;z-index:4}
  .appbar .back{margin-right:12px;font-size:20px;cursor:default}
  .content{position:absolute;left:0;top:80px;width:100%;bottom:24px;overflow:hidden}
  .gesture{position:absolute;left:0;bottom:0;width:100%;height:24px;display:flex;
           align-items:center;justify-content:center;z-index:6}
  .pill{width:112px;height:4px;border-radius:2px;background:#1f1f1f;opacity:.55}
  .node{position:absolute;box-sizing:border-box}
  .row{display:flex;align-items:center;justify-content:space-between;height:100%;
       padding:0 0 0 0;border-bottom:1px solid var(--m3-outline);background:transparent}
  .row .label{font-size:16px;padding-left:0}
  .row .chev{color:var(--m3-onvar);font-size:20px;padding-right:4px}
  .listitem{height:100%;display:flex;flex-direction:column;justify-content:center;
            border-bottom:1px solid var(--m3-outline);padding:0 0}
  .listitem .t{font-size:16px} .listitem .s{font-size:13px;color:var(--m3-onvar);margin-top:2px}
  .switchrow{height:100%;display:flex;align-items:center;justify-content:space-between;
             border-bottom:1px solid var(--m3-outline)}
  .sw{width:52px;height:32px;border-radius:16px;position:relative;background:var(--m3-track-off);flex:0 0 auto}
  .sw.on{background:var(--m3-track-on)}
  .sw .knob{position:absolute;top:4px;left:4px;width:24px;height:24px;border-radius:12px;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.3)}
  .sw.on .knob{left:24px}
  .sliderrow{height:100%;display:flex;flex-direction:column;justify-content:center;gap:8px}
  .track{height:8px;border-radius:4px;background:var(--m3-surface-2);position:relative}
  .track .fill{position:absolute;left:0;top:0;bottom:0;border-radius:4px;background:var(--m3-primary)}
  .track .knob{position:absolute;top:-6px;width:20px;height:20px;border-radius:10px;background:var(--m3-primary)}
  .icon{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px}
  .icon .ic{width:56px;height:56px;border-radius:18px;background:var(--m3-surface);
            border:1px solid var(--m3-outline);display:flex;align-items:center;justify-content:center;
            font-size:22px}
  .icon .cap{font-size:11px;color:var(--m3-onvar)}
  .button{height:100%;display:flex;align-items:center;justify-content:center;border-radius:20px;
          background:var(--m3-surface);border:1px solid var(--m3-outline);font-size:20px}
  .tile{height:100%;border-radius:16px;background:var(--m3-surface);border:1px solid var(--m3-outline);
        display:flex;align-items:center;justify-content:center;font-size:13px}
  .tile.on{background:var(--m3-primary-container)}
  .text{height:100%;display:flex;align-items:center;font-size:15px;color:var(--m3-onvar);white-space:pre-line}
  .chosen{outline:3px solid #22c55e;outline-offset:-3px;background:rgba(34,197,94,.12);border-radius:10px}
  .panel{flex:1;min-width:360px;max-width:640px;background:#fff;border-radius:16px;padding:16px;
         box-shadow:0 4px 18px rgba(0,0,0,.06)}
  h2{font-size:14px;margin:14px 0 8px;color:#374151}
  h2:first-child{margin-top:0}
  .kv{font-size:13px;line-height:1.8}
  .badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px}
  .ok{background:#dcfce7;color:#166534}.bad{background:#fee2e2;color:#991b1b}.warn{background:#fef3c7;color:#92400e}
  .log{max-height:220px;overflow:auto;font:12px/1.6 ui-monospace,Consolas,monospace;background:#0b1020;
       color:#d1d5db;border-radius:10px;padding:10px}
  .log .hi{color:#86efac}
  button{font:inherit;padding:7px 12px;border-radius:10px;border:1px solid #d1d5db;background:#fff;cursor:pointer}
  button.primary{background:#111827;color:#fff;border-color:#111827}
  input[type=range]{width:100%}
  .muted{color:#6b7280;font-size:12px}
</style></head><body>
<div class="wrap">
  <div>
    <div class="phone"><div class="glass">
      <div class="status"><span id="stTime">9:41</span><span id="stIcons">▮▮▮ 87%</span></div>
      <div class="appbar"><span class="back" id="backArrow">←</span><span id="appTitle"></span></div>
      <div class="content" id="content"></div>
      <div class="gesture"><div class="pill"></div></div>
    </div></div>
    <div style="margin-top:12px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <button id="play" class="primary">▶ 播放</button>
      <button id="prev">◀ 上一步</button><button id="next">下一步 ▶</button>
      <span class="muted" id="speedLabel">速度 1×</span>
      <button id="speed">切換速度</button>
    </div>
    <div style="margin-top:8px"><input id="seek" type="range" min="0" max="0" value="0"></div>
  </div>
  <div class="panel">
    <h2>這一步 Agent 做了什麼</h2><div class="kv" id="action"></div>
    <h2>好奇心 / 學習訊號</h2><div class="kv" id="reward"></div>
    <h2>行動日誌</h2><div class="log" id="log"></div>
    <h2>學習曲線</h2><div id="curve"></div>
    <div class="muted" style="margin-top:8px">畫面內容全為合成資料（張三／測試訊息 1／會議 10:00…），與真機無關。</div>
  </div>
</div>
<script>
const DATA = __DATA__;
let i = 0, timer = null, speedMs = 700;
const $ = id => document.getElementById(id);

function nodeHtml(n){
  const r = n.rect || [0,0,0,0];
  const style = `left:${r[0]}px;top:${(r[1]-80)}px;width:${r[2]}px;height:${r[3]}px;`;
  const fam = n.family, label = n.label || '';
  if (fam === 'row') return `<div class="node" style="${style}"><div class="row"><span class="label">${label}</span><span class="chev">›</span></div></div>`;
  if (fam === 'list_item') return `<div class="node" style="${style}"><div class="listitem"><span class="t">${label}</span><span class="s">${n.subtitle||''}</span></div></div>`;
  if (fam === 'switch') return `<div class="node" style="${style}"><div class="switchrow"><span>${label}</span><span class="sw ${n.checked?'on':''}"><span class="knob"></span></span></div></div>`;
  if (fam === 'slider') { const v = Math.max(0, Math.min(4, n.value||0)); const pct = (v+1)/5*100;
    return `<div class="node" style="${style}"><div class="sliderrow"><span>${label}</span><div class="track"><div class="fill" style="width:${pct}%"></div><div class="knob" style="left:calc(${pct}% - 10px)"></div></div></div></div>`; }
  if (fam === 'icon') return `<div class="node" style="${style}"><div class="icon"><div class="ic">${label.slice(0,1)}</div><span class="cap">${label}</span></div></div>`;
  if (fam === 'button') return `<div class="node" style="${style}"><div class="button">${label}</div></div>`;
  if (fam === 'tile') return `<div class="node" style="${style}"><div class="tile ${n.checked?'on':''}">${label}</div></div>`;
  return `<div class="node" style="${style}"><div class="text">${label}</div></div>`;
}

function render(){
  const st = DATA.steps[i]; const sc = st.screen;
  $('stTime').textContent = sc.chrome.time;
  $('stIcons').textContent = '▮▮▮ ' + sc.chrome.battery + '%';
  $('appTitle').textContent = sc.chrome.title || '';
  $('backArrow').style.visibility = sc.chrome.back ? 'visible' : 'hidden';
  const c = $('content'); c.innerHTML = '';
  for (const n of sc.nodes){
    const d = document.createElement('div');
    d.innerHTML = nodeHtml(n);
    const el = d.firstElementChild;
    if (n.slot === st.chosenSlot) el.classList.add('chosen');
    c.appendChild(el);
  }
  $('action').innerHTML = `動作：<b>${st.actionName}</b><br>` +
    `結果：${st.changed?'<span class="badge ok">畫面有變化</span>':'<span class="badge warn">畫面沒變</span>'}` +
    (st.invalid?' <span class="badge bad">無效動作</span>':'') +
    `<br>環境獎勵：<b>${st.envReward.toFixed(2)}</b>　總獎勵：<b>${st.totalReward.toFixed(3)}</b>`;
  $('reward').innerHTML = `新奇度 novelty：<b>${st.novelty.toFixed(3)}</b> ` +
    (st.newObs?'<span class="badge ok">首次看到</span>':'<span class="muted">看過</span>') +
    `<br><span class="muted">已探索畫面 ${st.screens}/${st.screensTotal}　Q 狀態 ${st.qStates}</span>`;
  const log = $('log'); log.innerHTML='';
  for (let k = Math.max(0,i-16); k<=i; k++){
    const s2 = DATA.steps[k]; const div = document.createElement('div');
    if (k===i) div.className='hi';
    div.textContent = `#${k} ${s2.actionName}  r=${s2.totalReward.toFixed(2)}`;
    log.appendChild(div);
  }
  log.scrollTop = log.scrollHeight; $('seek').value = i;
}
function stop(){ if(timer){clearInterval(timer);timer=null;} $('play').textContent='▶ 播放'; }
function play(){ if(timer){stop();return;} $('play').textContent='⏸ 暫停';
  timer=setInterval(()=>{ if(i>=DATA.steps.length-1){stop();return;} i++; render(); }, speedMs); }
$('play').onclick=play;
$('prev').onclick=()=>{stop(); i=Math.max(0,i-1); render();};
$('next').onclick=()=>{stop(); i=Math.min(DATA.steps.length-1,i+1); render();};
$('speed').onclick=()=>{ const o=[700,350,150,60]; const c=o.indexOf(speedMs); speedMs=o[(c+1)%o.length];
  $('speedLabel').textContent='速度 '+(700/speedMs).toFixed(0)+'×'; if(timer){stop();play();} };
$('seek').max = DATA.steps.length-1;
$('seek').oninput = e=>{ stop(); i=parseInt(e.target.value); render(); };
window.addEventListener('keydown', e=>{ if(e.key===' '){e.preventDefault();play();}
  if(e.key==='ArrowRight'){stop();i=Math.min(DATA.steps.length-1,i+1);render();}
  if(e.key==='ArrowLeft'){stop();i=Math.max(0,i-1);render();} });
(function(){ const c=DATA.curve; if(!c||!c.length) return;
  const w=520,h=120,pad=26;
  const pts=c.map((p,k)=>{const x=pad+(w-pad*2)*k/Math.max(1,c.length-1);
    const y=h-pad-(h-pad*2)*(p.screens/19);return x.toFixed(1)+','+y.toFixed(1);}).join(' ');
  const nov=c.map((p,k)=>{const x=pad+(w-pad*2)*k/Math.max(1,c.length-1);
    const y=h-pad-(h-pad*2)*Math.min(1,p.new_obs_rate/0.08);return x.toFixed(1)+','+y.toFixed(1);}).join(' ');
  $('curve').innerHTML=`<svg width="${w}" height="${h}">
    <polyline points="${pts}" fill="none" stroke="#16a34a" stroke-width="2"/>
    <polyline points="${nov}" fill="none" stroke="#f59e0b" stroke-width="1.5" stroke-dasharray="4 3"/>
    <text x="${pad}" y="13" font-size="10" fill="#16a34a">已探索畫面數</text>
    <text x="${pad+96}" y="13" font-size="10" fill="#f59e0b">新奇度（越低越熟）</text></svg>`; })();
render();
</script></body></html>
"""


def build_html(path: str, steps: List[Dict[str, Any]], curve: List[Dict[str, Any]],
               meta: Dict[str, Any]) -> None:
    data = {"meta": meta, "steps": steps, "curve": curve}
    with open(path, "w", encoding="utf-8") as f:
        f.write(HTML_TEMPLATE.replace("__DATA__", json.dumps(data, ensure_ascii=False)))


# ── PNG 分鏡圖（真實位置與比例）────────────────────────────────────
def build_storyboard(path: str, steps: List[Dict[str, Any]], cols: int = 3, rows: int = 2) -> bool:
    try:
        from PIL import Image, ImageDraw, ImageFont
    except Exception:
        return False

    def font(size: int):
        for p in (r"C:\Windows\Fonts\msjh.ttc", r"C:\Windows\Fonts\msyh.ttc"):
            if os.path.exists(p):
                try:
                    return ImageFont.truetype(p, size)
                except Exception:
                    pass
        return ImageFont.load_default()

    dp = 0.62  # dp → px（縮小以放進分鏡）
    dev = {"w_dp": 384, "h_dp": 832, "status": 24, "appbar": 56, "gesture": 24}
    pw, ph = int(dev["w_dp"] * dp), int(dev["h_dp"] * dp)
    cell_w, cell_h = pw + 20, ph + 34
    img = Image.new("RGB", (cols * cell_w + 10, rows * cell_h + 10), (233, 236, 242))
    d = ImageDraw.Draw(img)
    f_title = font(13); f_node = font(11); f_small = font(10)

    total = cols * rows
    picks = steps if len(steps) <= total else [
        steps[int(round(i * (len(steps) - 1) / (total - 1)))] for i in range(total)
    ]

    for k, st in enumerate(picks):
        if k >= total:
            break
        ox = 10 + (k % cols) * cell_w
        oy = 10 + (k // cols) * cell_h
        # 手機外框
        d.rounded_rectangle([ox - 4, oy - 4, ox + pw + 4, oy + ph + 4], radius=int(30 * dp),
                            fill=(11, 11, 13))
        d.rounded_rectangle([ox, oy, ox + pw, oy + ph], radius=int(28 * dp), fill=(247, 249, 252))
        sc = st["screen"]; ch = sc.get("chrome", {})
        # 狀態列
        d.text((ox + 6, oy + 4), ch.get("time", "9:41"), font=f_small, fill=(73, 69, 79))
        bt = f'{ch.get("battery", 87)}%'
        d.text((ox + pw - 34, oy + 4), bt, font=f_small, fill=(73, 69, 79))
        # app bar
        if ch.get("back"):
            d.text((ox + 8, oy + 24 * dp + 4), "←", font=f_node, fill=(31, 31, 31))
        d.text((ox + (24 if ch.get("back") else 10), oy + 24 * dp + 4),
               ch.get("title", ""), font=f_title, fill=(31, 31, 31))
        # 內容節點
        for n in sc.get("nodes", []):
            r = n.get("rect") or [0, 0, 0, 0]
            x = ox + r[0] * dp
            y = oy + r[1] * dp
            w = max(1.0, r[2] * dp)
            h = max(1.0, r[3] * dp)
            if y + h < oy + 24 * dp or y > oy + ph - 24 * dp:
                continue
            chosen = n["slot"] == st.get("chosenSlot", -1)
            fam = n["family"]; label = n.get("label", "")
            fill = (255, 255, 255)
            outline = (201, 200, 207)
            if fam == "switch":
                fill = (185, 240, 154) if n.get("checked") else (201, 200, 207)
            if fam == "tile":
                fill = (185, 240, 154) if n.get("checked") else (255, 255, 255)
            if fam == "button":
                fill = (255, 255, 255)
            if fam == "icon":
                fill = (255, 255, 255)
            if chosen:
                fill = (220, 252, 231); outline = (34, 197, 94)
            d.rounded_rectangle([x, y, x + w, y + h], radius=max(2, int(8 * dp)),
                                fill=fill, outline=outline)
            if fam == "switch":
                sy = y + h / 2 - 16 * dp
                d.rounded_rectangle([x + w - 62 * dp, sy, x + w - 10 * dp, sy + 32 * dp],
                                    radius=int(16 * dp),
                                    fill=(56, 106, 32) if n.get("checked") else (201, 200, 207))
                kx = x + w - 34 * dp if n.get("checked") else x + w - 58 * dp
                d.ellipse([kx, sy + 4 * dp, kx + 24 * dp, sy + 28 * dp], fill=(255, 255, 255))
            elif fam == "slider":
                ty = y + h / 2 - 4 * dp
                d.rounded_rectangle([x + 6, ty, x + w - 6, ty + 8 * dp], radius=4, fill=(238, 240, 244))
                pct = ((n.get("value", 0) + 1) / 5.0)
                d.rounded_rectangle([x + 6, ty, x + 6 + (w - 12) * pct, ty + 8 * dp],
                                    radius=4, fill=(56, 106, 32))
            if fam in ("icon", "button"):
                d.text((x + w / 2 - len(label) * 5, y + h / 2 - 8), label[:3], font=f_node,
                       fill=(31, 31, 31))
            elif fam != "slider":
                d.text((x + 10, y + h / 2 - 8), label[:12], font=f_node, fill=(31, 31, 31))
        # 手勢列
        d.rounded_rectangle([ox + pw / 2 - 34 * dp, oy + ph - 12 * dp,
                             ox + pw / 2 + 34 * dp, oy + ph - 8 * dp], radius=2, fill=(31, 31, 31))
        d.text((ox - 2, oy + ph + 8), f'{sc.get("id","")} · {st.get("actionName","")[:18]}',
               font=f_small, fill=(55, 65, 81))
    img.save(path)
    return True
