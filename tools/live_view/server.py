"""本機即時儀表板：看整晚學習中的 AI 正在做什麼。

用法：
  python tools/live_view/server.py
  然後開 http://127.0.0.1:8765

原理：
  - 訓練程式每 0.4 秒寫一份 out/overnight/live.json（目前畫面 + 動作 + 統計）
  - 這個小伺服器只讀檔、回傳 JSON，不干擾訓練
  - 儀表板每 0.5 秒輪詢 /data 並重畫手機畫面
"""

from __future__ import annotations

import http.server
import json
import os
import socketserver
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OVERNIGHT = os.path.join(ROOT, "tools", "symbolic_sim", "out", "overnight")
PORT = 8765


def read_json(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def read_tail(path: str, n: int = 6) -> list:
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().strip().splitlines()
        return [json.loads(x) for x in lines[-n:] if x.strip()]
    except Exception:
        return []


def read_text(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):  # 安靜
        pass

    def _send(self, body: bytes, ctype: str = "application/json; charset=utf-8") -> None:
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path.startswith("/data"):
            payload = {
                "live": read_json(os.path.join(OVERNIGHT, "live.json")),
                "progress": read_tail(os.path.join(OVERNIGHT, "progress.jsonl"), 8),
                "running": os.path.exists(os.path.join(OVERNIGHT, "live.json")),
            }
            self._send(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        elif self.path.startswith("/report"):
            self._send(read_text(os.path.join(OVERNIGHT, "report.md")).encode("utf-8"),
                       "text/plain; charset=utf-8")
        else:
            html = DASHBOARD.encode("utf-8")
            self._send(html, "text/html; charset=utf-8")


DASHBOARD = r"""<!DOCTYPE html>
<html lang="zh-Hant"><head><meta charset="utf-8">
<title>WAE 整晚學習 — 即時監看</title>
<style>
 :root{--bg:#eef1f6;--card:#fff;--line:#d9dde5;--on:#111827;--sub:#6b7280;--green:#16a34a}
 body{margin:0;background:var(--bg);font-family:"Segoe UI","Microsoft JhengHei",system-ui,sans-serif;color:var(--on)}
 .top{display:flex;align-items:center;gap:14px;padding:14px 20px;background:#fff;border-bottom:1px solid var(--line)}
 .dot{width:10px;height:10px;border-radius:5px;background:#9ca3af}
 .dot.on{background:var(--green);box-shadow:0 0 0 4px #dcfce7}
 .wrap{display:flex;gap:18px;padding:18px;align-items:flex-start;flex-wrap:wrap}
 .phone{width:320px;height:694px;background:#0b0b0d;border-radius:38px;padding:9px;flex:0 0 auto;
        box-shadow:0 16px 36px rgba(0,0,0,.22)}
 .glass{position:relative;width:320px;height:694px;background:#f7f9fc;border-radius:31px;overflow:hidden}
 .status{position:absolute;top:0;left:0;width:100%;height:20px;display:flex;align-items:center;
         justify-content:space-between;padding:0 14px;box-sizing:border-box;font-size:10px;color:#49454f;z-index:5}
 .appbar{position:absolute;top:20px;left:0;width:100%;height:47px;display:flex;align-items:center;
         padding:0 14px;box-sizing:border-box;font-size:17px;font-weight:600;z-index:4}
 .content{position:absolute;top:67px;left:0;width:100%;bottom:20px;overflow:hidden}
 .gesture{position:absolute;bottom:0;left:0;width:100%;height:20px;display:flex;align-items:center;justify-content:center}
 .pill{width:94px;height:4px;border-radius:2px;background:#1f1f1f;opacity:.55}
 .n{position:absolute;box-sizing:border-box}
 .row{height:100%;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);font-size:13px}
 .row .chev{color:var(--sub)}
 .li{height:100%;display:flex;flex-direction:column;justify-content:center;border-bottom:1px solid var(--line)}
 .li .t{font-size:13px}.li .s{font-size:11px;color:var(--sub)}
 .sw-row{height:100%;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);font-size:13px}
 .sw{width:44px;height:26px;border-radius:13px;background:#c9c8cf;position:relative}
 .sw.on{background:#386a20}
 .sw .k{position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:10px;background:#fff}
 .sw.on .k{left:21px}
 .sl{height:100%;display:flex;flex-direction:column;justify-content:center;gap:6px;font-size:13px}
 .tr{height:7px;border-radius:4px;background:#eef0f4;position:relative}
 .tr .f{position:absolute;left:0;top:0;bottom:0;border-radius:4px;background:#386a20}
 .tr .k{position:absolute;top:-5px;width:17px;height:17px;border-radius:9px;background:#386a20}
 .ic{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;font-size:10px;color:var(--sub)}
 .ic .b{width:46px;height:46px;border-radius:15px;background:#fff;border:1px solid var(--line);
        display:flex;align-items:center;justify-content:center;font-size:17px;color:var(--on)}
 .btn{height:100%;display:flex;align-items:center;justify-content:center;border-radius:16px;background:#fff;
      border:1px solid var(--line);font-size:16px}
 .tile{height:100%;display:flex;align-items:center;justify-content:center;border-radius:13px;background:#fff;
       border:1px solid var(--line);font-size:11px}
 .tile.on{background:#b9f09a}
 .txt{height:100%;display:flex;align-items:center;font-size:12px;color:#49454f;white-space:pre-line;overflow:hidden}
 .chosen{outline:3px solid #22c55e;outline-offset:-3px;background:rgba(34,197,94,.13);border-radius:8px}
 .panel{flex:1;min-width:340px;max-width:720px;display:flex;flex-direction:column;gap:14px}
 .card{background:var(--card);border-radius:14px;padding:14px;box-shadow:0 4px 14px rgba(0,0,0,.05)}
 h2{font-size:13px;margin:0 0 8px;color:#374151}
 .kv{font-size:13px;line-height:1.8}
 .big{font-size:22px;font-weight:700}
 .muted{color:var(--sub);font-size:12px}
 table{width:100%;border-collapse:collapse;font-size:12px}
 td,th{padding:3px 6px;border-bottom:1px solid #f1f2f5;text-align:left}
 .log{font:12px/1.6 ui-monospace,Consolas,monospace;background:#0b1020;color:#d1d5db;border-radius:10px;padding:10px;max-height:150px;overflow:auto}
 .log .c{color:#86efac}
 .bar{height:8px;background:#e5e7eb;border-radius:5px;overflow:hidden;margin-top:5px}
 .bar>i{display:block;height:100%;background:var(--green)}
 .goals{display:grid;grid-template-columns:1fr 1fr;gap:4px 14px;font-size:12px}
</style></head><body>
<div class="top">
  <span class="dot" id="dot"></span>
  <b>WAE 整晚學習</b>
  <span class="muted" id="stamp">等待資料…</span>
  <span class="muted" id="phase"></span>
  <span style="flex:1"></span>
  <label class="muted"><input type="checkbox" id="pause"> 暫停更新</label>
</div>
<div class="wrap">
  <div class="phone"><div class="glass">
    <div class="status"><span id="tm">9:41</span><span id="bt">87%</span></div>
    <div class="appbar"><span id="back" style="margin-right:8px">←</span><span id="title"></span></div>
    <div class="content" id="content"></div>
    <div class="gesture"><div class="pill"></div></div>
  </div></div>
  <div class="panel">
    <div class="card">
      <h2>它現在在做什麼</h2>
      <div class="kv">
        <span class="big" id="action">—</span>
        <span id="changeBadge" class="muted"></span><br>
        <span class="muted" id="goal"></span>
      </div>
    </div>
    <div class="card">
      <h2>學習統計</h2>
      <div class="kv">
        步數 <b id="steps">0</b>　回合 <b id="eps2">0</b>　速度 <b id="speed">0</b> 步/秒<br>
        探索畫面 <b id="screens">0</b>/<b id="screensTotal">19</b>
        <div class="bar"><i id="screenBar" style="width:0%"></i></div>
        Q 狀態 <b id="q">0</b>　ε <b id="eps3">1.0</b>
      </div>
    </div>
    <div class="card">
      <h2>最近動作</h2>
      <div class="log" id="log"></div>
    </div>
    <div class="card">
      <h2>目標成功率（C2）</h2>
      <div class="goals" id="goals"></div>
    </div>
    <div class="card">
      <h2>每 10 分鐘進度</h2>
      <table><thead><tr><th>時間</th><th>分鐘</th><th>階段</th><th>回合</th><th>步數</th><th>畫面</th></tr></thead>
      <tbody id="prog"></tbody></table>
    </div>
  </div>
</div>
<script>
const $=id=>document.getElementById(id);
function esc(s){return (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
function nodeHtml(n){
  const r=n.rect||[0,0,0,0];
  const st=`left:${r[0]*320/384}px;top:${(r[1]-80)*320/384}px;width:${r[2]*320/384}px;height:${r[3]*320/384}px;`;
  const l=esc(n.label);
  if(n.family==='row')return `<div class="n" style="${st}"><div class="row"><span>${l}</span><span class="chev">›</span></div></div>`;
  if(n.family==='list_item')return `<div class="n" style="${st}"><div class="li"><span class="t">${l}</span><span class="s">${esc(n.subtitle)}</span></div></div>`;
  if(n.family==='switch')return `<div class="n" style="${st}"><div class="sw-row"><span>${l}</span><span class="sw ${n.checked?'on':''}"><span class="k"></span></span></div></div>`;
  if(n.family==='slider'){const v=Math.max(0,Math.min(4,n.value||0)),p=(v+1)/5*100;
    return `<div class="n" style="${st}"><div class="sl"><span>${l}</span><div class="tr"><div class="f" style="width:${p}%"></div><div class="k" style="left:calc(${p}% - 8px)"></div></div></div></div>`}
  if(n.family==='icon')return `<div class="n" style="${st}"><div class="ic"><div class="b">${l.slice(0,1)}</div><span>${l}</span></div></div>`;
  if(n.family==='button')return `<div class="n" style="${st}"><div class="btn">${l}</div></div>`;
  if(n.family==='tile')return `<div class="n" style="${st}"><div class="tile ${n.checked?'on':''}">${l}</div></div>`;
  return `<div class="n" style="${st}"><div class="txt">${l}</div></div>`;
}
async function tick(){
  if($('pause').checked) return;
  try{
    const r=await fetch('/data',{cache:'no-store'}); const d=await r.json();
    const L=d.live||{}; $('dot').className='dot'+(L.ts?' on':'');
    $('stamp').textContent=L.at?('更新 '+L.at):'等待訓練寫入 live.json…';
    $('phase').textContent=L.phase?('階段 '+L.phase):'';
    const sc=L.screen||{chrome:{},nodes:[]}, ch=sc.chrome||{};
    $('tm').textContent=ch.time||'9:41'; $('bt').textContent=(ch.battery||87)+'%';
    $('title').textContent=ch.title||''; $('back').style.visibility=ch.back?'visible':'hidden';
    const c=$('content'); c.innerHTML='';
    (sc.nodes||[]).forEach(n=>{const w=document.createElement('div');w.innerHTML=nodeHtml(n);
      const el=w.firstElementChild; if(n.slot===L.chosenSlot)el.classList.add('chosen'); c.appendChild(el);});
    $('action').textContent=L.actionName||'—';
    $('changeBadge').textContent=L.changed?'（畫面有變化）':'（畫面沒變）';
    $('goal').textContent=L.goal?('目標：'+L.goal):'';
    $('steps').textContent=(L.steps||0).toLocaleString();
    $('eps2').textContent=(L.episodes||0).toLocaleString();
    $('speed').textContent=(L.stepsPerSec||0).toLocaleString();
    $('screens').textContent=L.screens||0; $('screensTotal').textContent=L.screensTotal||19;
    $('screenBar').style.width=((L.screens||0)/Math.max(1,(L.screensTotal||19))*100)+'%';
    $('q').textContent=(L.q||0).toLocaleString(); $('eps3').textContent=L.eps??'—';
    const log=$('log'); log.innerHTML='';
    (L.recent||[]).forEach(a=>{const div=document.createElement('div');
      div.className=a.c?'c':''; div.textContent=a.a; log.appendChild(div);});
    log.scrollTop=log.scrollHeight;
    const g=$('goals'); g.innerHTML='';
    const gs=L.goalStats||{}; const names=Object.keys(gs);
    if(!names.length){g.innerHTML='<span class="muted">尚未進入 C2（或還沒有資料）</span>'}
    names.forEach(n=>{const v=gs[n];const t=v.tries||0,s=v.success||0;
      const pct=t?Math.round(s*100/t):0;const div=document.createElement('div');
      div.innerHTML=`${esc(n)} <b>${pct}%</b> <span class="muted">(${s}/${t}${v.her?', HER '+v.her:''})</span>`;
      g.appendChild(div);});
    const pb=$('prog'); pb.innerHTML='';
    (d.progress||[]).forEach(p=>{const tr=document.createElement('tr');
      tr.innerHTML=`<td>${p.at}</td><td>${p.min}</td><td>${p.phase}</td><td>${p.ep}</td><td>${(p.steps||0).toLocaleString()}</td><td>${p.screens}</td>`;
      pb.appendChild(tr);});
  }catch(e){$('stamp').textContent='（連線中斷，重試…）';}
}
setInterval(tick,500); tick();
</script></body></html>
"""


def main() -> int:
    os.makedirs(OVERNIGHT, exist_ok=True)
    print(f"即時儀表板：http://127.0.0.1:{PORT}")
    print(f"資料來源：{OVERNIGHT}")
    print("按 Ctrl+C 結束（不會影響訓練）")
    with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n已停止")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
