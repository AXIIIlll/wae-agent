"""整晚學習跑者：好奇心探索（每回合隨機化）→ 定期檢查點 → 早上看報告。

用法（背景跑整晚）：
  python tools/symbolic_sim/run_overnight.py --hours 8

設計：
  - 每回合環境重新隨機化（內容/順序/初始狀態）→ 新奇度不會提早飽和
  - 每 3 分鐘存檢查點（Q 表 + 統計）；斷電/關機可 --resume 接續
  - 每 10 分鐘更新 progress.log 與 report.md；早上直接看報告
  - 每 30 分鐘做一次「能力評估」（20 回合、低探索）→ 看基本操作是否學成
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from curiosity_agent import CuriosityAgent            # noqa: E402
from debug_view import action_name, screen_payload     # noqa: E402
from phone_env import NUM_ACTIONS, SymbolicPhoneEnv    # noqa: E402

OUT = os.path.join(HERE, "out", "overnight")


def ensure_out() -> None:
    os.makedirs(OUT, exist_ok=True)


def save_checkpoint(path: str, agent: CuriosityAgent, state: dict) -> None:
    payload = {
        "schema": 1,
        "saved_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "q": agent.q,
        "obs_counts": dict(agent.obs_counts),
        "pair_seen": [list(p) for p in agent.pair_seen],
        "steps": agent.steps,
        "state": state,
    }
    tmp = path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)
    os.replace(tmp, path)


def load_checkpoint(path: str, agent: CuriosityAgent) -> dict | None:
    if not os.path.exists(path):
        return None
    try:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            data = json.load(f)
        agent.q = {k: list(v) for k, v in data.get("q", {}).items()}
        agent.obs_counts.update({k: int(v) for k, v in data.get("obs_counts", {}).items()})
        agent.pair_seen = {(p[0], int(p[1])) for p in data.get("pair_seen", [])}
        agent.steps = int(data.get("steps", 0))
        return data.get("state") or {}
    except Exception as e:  # noqa: BLE001
        print(f"[checkpoint] load failed: {e}")
        return None


def prune_agent(agent: CuriosityAgent, max_entries: int = 300_000) -> int:
    """Q 表太大時，丟掉「只看過一次」的狀態（保留學過的）。"""
    if len(agent.q) <= max_entries:
        return 0
    keep = {k: v for k, v in agent.q.items() if agent.obs_counts.get(k, 0) >= 2}
    if len(keep) > max_entries:
        keep = dict(list(keep.items())[:max_entries])
    removed = len(agent.q) - len(keep)
    agent.q = keep
    return removed


# ── C2：目標與 HER-lite ────────────────────────────────────────────
def goal_pool() -> list:
    """(名稱, 判定函式)。判定只看環境狀態，不依賴模型 → 可當 reward oracle。"""
    return [
        ("開啟 Wi-Fi", lambda e: e.switches["wifi"]), ("關閉 Wi-Fi", lambda e: not e.switches["wifi"]),
        ("開啟藍牙", lambda e: e.switches["bluetooth"]), ("關閉藍牙", lambda e: not e.switches["bluetooth"]),
        ("開啟飛航模式", lambda e: e.switches["airplane"]), ("關閉飛航模式", lambda e: not e.switches["airplane"]),
        ("開啟深色主題", lambda e: e.switches["dark"]), ("關閉深色主題", lambda e: not e.switches["dark"]),
        ("開啟勿擾模式", lambda e: e.switches["dnd"]),
        ("亮度調到最高", lambda e: e.sliders["brightness"] == 4),
        ("音量調到最高", lambda e: e.sliders["media_volume"] == 4),
        ("打開計算機", lambda e: e.screen_id == "calculator"),
        ("打開設定", lambda e: e.screen_id == "settings_main"),
        ("回到首頁", lambda e: e.screen_id == "home"),
        ("捲動到最底", lambda e: e._screen().scroll_max > 0 and e._screen().scroll_dp >= e._screen().scroll_max),
    ]


def sample_goal(env, rng):
    pool = [(n, f) for n, f in goal_pool() if not f(env)]
    return rng.choice(pool) if pool else goal_pool()[0]


def goal_key(goal_name: str, obs: str) -> str:
    """目標條件化的 Q 鍵（表格法：把目標接在觀測前面）。"""
    return f"{goal_name}||{obs}"


def write_live(path: str, env, agent: CuriosityAgent, phase: str, goal_name: str,
               action: int, info: dict, recent: list, state: dict) -> None:
    """寫一份「即時畫面」給本機儀表板讀取（原子替換，避免讀到半個檔）。"""
    try:
        screen = env._screen()
        payload = {
            "at": time.strftime("%H:%M:%S"),
            "ts": time.time(),
            "phase": phase,
            "goal": goal_name,
            "screen": screen_payload(screen, env.m.to_dict()),
            "chosenSlot": (action - 5) if action >= 5 else -1,
            "actionName": action_name(action, screen),
            "changed": bool(info.get("changed")),
            "kind": info.get("kind", ""),
            "steps": agent.steps,
            "episodes": state.get("episodes", 0),
            "screens": env.stats()["screens_discovered"],
            "screensTotal": env.stats()["screens_total"],
            "q": len(agent.q),
            "eps": round(agent.epsilon(), 3),
            "stepsPerSec": round(state.get("steps_per_sec", 0)),
            "goalStats": state.get("goal_stats", {}),
            "recent": recent[-12:],
        }
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        os.replace(tmp, path)
    except Exception:
        pass


def evaluate(agent: CuriosityAgent, episodes: int = 20, seed: int = 999) -> dict:
    """能力評估：低探索（ε=0.05）跑 20 回合，看基本操作是否學成。"""
    env = SymbolicPhoneEnv(seed=seed, max_steps=100, randomize=True)
    old_eps = (agent.eps_start, agent.eps_end)
    agent.eps_start = agent.eps_end = 0.05
    steps = 0
    try:
        for ep in range(episodes):
            obs = env.reset()
            done = False
            while not done:
                mask = env.action_mask()
                a = agent.act(obs, mask)
                nobs, r, done, _ = env.step(a)
                agent.observe(obs, a, r, nobs, env.action_mask(), done)
                obs = nobs
                steps += 1
    finally:
        agent.eps_start, agent.eps_end = old_eps
    st = env.stats()
    tap_rate = (st["taps_with_change"] / st["taps_total"]) if st["taps_total"] else 0.0
    total = st["valid_actions"] + st["invalid_actions"]
    st["tap_change_rate"] = round(tap_rate, 3)
    st["invalid_action_rate"] = round((st["invalid_actions"] / total) if total else 1.0, 3)
    st["eval_steps"] = steps
    return st


def gate_pass(st: dict, train_screens: int = 0, train_scroll: int = 0) -> bool:
    """基本操作門檻：畫面覆蓋（訓練）+ 捲動覆蓋 + 開關操作 + 動作準確度。"""
    screens = max(train_screens, st.get("screens_discovered", 0))
    scrolls = max(train_scroll, st.get("scroll_positions", 0))
    return (screens >= 15 and st.get("toggle_flips", 0) >= 30
            and scrolls >= 5
            and st.get("tap_change_rate", 0) >= 0.55
            and st.get("invalid_action_rate", 1) <= 0.20)


def write_report(path: str, state: dict, agent: CuriosityAgent, last_eval: dict | None) -> None:
    lines = ["# 整晚學習報告", "",
             f"- 開始：{state.get('started_at')}",
             f"- 已跑：{state.get('elapsed_min', 0):.0f} 分鐘",
             f"- 回合：{state.get('episodes', 0):,}　步數：{agent.steps:,}",
             f"- 吞吐：{state.get('steps_per_sec', 0):,.0f} 步/秒",
             f"- Q 狀態數：{len(agent.q):,}　觀測數：{len(agent.obs_counts):,}",
             f"- 探索畫面（訓練累計）：{state.get('screens', 0)} / {state.get('screens_total', 19)}",
             ""]
    if last_eval:
        lines += ["## 能力評估（ε=0.05，20 回合）", "",
                  "| 指標 | 數值 |", "|---|---|",
                  f"| 探索畫面 | {last_eval.get('screens_discovered')}/{last_eval.get('screens_total')} |",
                  f"| 開關切換 | {last_eval.get('toggle_flips')} |",
                  f"| 有效捲動 | {last_eval.get('effective_scrolls')} |",
                  f"| 點擊變化率 | {last_eval.get('tap_change_rate')} |",
                  f"| 無效動作率 | {last_eval.get('invalid_action_rate')} |",
                  f"| 捲動位置覆蓋 | {last_eval.get('scroll_positions')} |",
                  f"| **基本操作門檻** | **{'PASS' if gate_pass(last_eval, state.get('screens', 0), state.get('scroll_positions', 0)) else 'FAIL'}** |",
                  ""]
    lines += ["## 學習曲線（每 10 分鐘）", "",
              "| 分鐘 | 回合 | 步數 | 畫面 | ε |",
              "|---|---|---|---|---|"]
    for row in state.get("curve", [])[-24:]:
        lines.append(f"| {row['min']:.0f} | {row['ep']} | {row['steps']:,} | {row['screens']} | {row['eps']:.2f} |")
    lines += ["", "## 早上要做的事（建議）", "",
              "1. 看 `out/overnight/report.md` 的能力評估是否 PASS",
              "2. 若 PASS → 進入 C2（目標條件 + HER），讓 AI 從「會操作」變成「會完成任務」",
              "3. 若 FAIL → 看是哪一項沒過（通常是捲動或開關），調整環境或獎勵",
              ""]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=float, default=8.0)
    ap.add_argument("--max-steps", type=int, default=150)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--checkpoint-every-sec", type=int, default=180)
    ap.add_argument("--report-every-sec", type=int, default=600)
    ap.add_argument("--eval-every-sec", type=int, default=1800)
    ap.add_argument("--max-q", type=int, default=300_000)
    ap.add_argument("--live-every-sec", type=float, default=0.4,
                    help="即時儀表板更新頻率（秒）")
    ap.add_argument("--c1-minutes", type=float, default=4.0,
                    help="好奇心探索(C1)先跑幾分鐘，之後自動切目標階段(C2)")
    args = ap.parse_args()

    ensure_out()
    ckpt = os.path.join(OUT, "checkpoint.json.gz")
    progress = os.path.join(OUT, "progress.jsonl")
    report = os.path.join(OUT, "report.md")

    env = SymbolicPhoneEnv(seed=args.seed, max_steps=args.max_steps, randomize=True)
    agent = CuriosityAgent(NUM_ACTIONS, seed=args.seed,
                           epsilon_decay_steps=3_000_000, epsilon_start=1.0, epsilon_end=0.08)
    state = {
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "elapsed_min": 0.0, "episodes": 0, "screens": 0, "screens_total": 19,
        "steps_per_sec": 0, "curve": [], "phase": "C1", "goal_stats": {},
    }
    if args.resume:
        loaded = load_checkpoint(ckpt, agent)
        if loaded:
            state.update(loaded)
            print(f"[resume] episodes={state.get('episodes')} steps={agent.steps}")

    t0 = time.time()
    last_ckpt = last_report = last_eval = t0
    last_eval_result = None
    ep = int(state.get("episodes", 0))
    obs = env.reset()
    ep_start_obs_count = len(agent.obs_counts)
    new_obs_window: list = []
    print(f"[start] hours={args.hours} out={OUT}")
    live_path = os.path.join(OUT, "live.json")
    recent: list = []
    last_live = 0.0
    rng_goal = __import__("random").Random(args.seed + 1)
    goal_stats = state.setdefault("goal_stats", {})
    phase = state.get("phase", "C1")

    def bump(name: str, field: str, n: int = 1) -> None:
        g = goal_stats.setdefault(name, {"tries": 0, "success": 0, "her": 0})
        g[field] = g.get(field, 0) + n

    try:
        while time.time() - t0 < args.hours * 3600:
            now = time.time()
            elapsed = now - t0

            # C1 → C2 自動切換
            if phase == "C1" and elapsed >= args.c1_minutes * 60:
                phase = "C2"
                state["phase"] = phase
                print(f"[phase] C1 → C2（目標條件 + HER-lite）at {elapsed/60:.1f} min")

            obs = env.reset()
            done = False
            ep_new = 0

            if phase == "C1":
                while not done:
                    mask = env.action_mask()
                    a = agent.act(obs, mask)
                    nobs, r, done, info = env.step(a)
                    _, is_new = agent.observe(obs, a, r, nobs, env.action_mask(), done)
                    if is_new:
                        ep_new += 1
                    obs = nobs
                    recent.append({"a": action_name(a, env._screen()), "c": bool(info.get("changed"))})
                    if len(recent) > 20:
                        recent.pop(0)
                    tn = time.time()
                    if tn - last_live >= args.live_every_sec:
                        write_live(live_path, env, agent, phase, "", a, info, recent, state)
                        last_live = tn
            else:
                goal_name, goal_fn = sample_goal(env, rng_goal)
                achieved = False
                while not done:
                    qobs = goal_key(goal_name, obs)
                    mask = env.action_mask()
                    a = agent.act(qobs, mask)
                    nobs, r, done, info = env.step(a)
                    recent.append({"a": action_name(a, env._screen()), "c": bool(info.get("changed"))})
                    if len(recent) > 20:
                        recent.pop(0)
                    tn = time.time()
                    if tn - last_live >= args.live_every_sec:
                        write_live(live_path, env, agent, phase, goal_name, a, info, recent, state)
                        last_live = tn
                    if goal_fn(env):
                        achieved = True
                        r = r + 1.0          # 目標達成 → 外在獎勵
                        done = True
                    nqobs = goal_key(goal_name, nobs)
                    agent.observe(qobs, a, r, nqobs, env.action_mask(), done)
                    obs = nobs
                bump(goal_name, "tries")
                if achieved:
                    bump(goal_name, "success")
                else:
                    # HER-lite：雖然沒達成目標，但若達成了「別的目標」也算學到東西
                    for other_name, other_fn in goal_pool():
                        if other_name != goal_name and other_fn(env):
                            bump(other_name, "her")
                            break

            ep += 1
            rate = ep_new / max(1, args.max_steps)
            new_obs_window.append(rate)
            if len(new_obs_window) > 300:
                new_obs_window.pop(0)

            st = env.stats()
            state["episodes"] = ep
            state["elapsed_min"] = elapsed / 60.0
            state["screens"] = st["screens_discovered"]
            state["screens_total"] = st["screens_total"]
            state["scroll_positions"] = st.get("scroll_positions", 0)
            state["steps_per_sec"] = agent.steps / max(1.0, elapsed)
            state["curve"].append({
                "min": elapsed / 60.0, "ep": ep, "steps": agent.steps,
                "screens": st["screens_discovered"], "eps": round(agent.epsilon(), 2),
            })

            if now - last_ckpt >= args.checkpoint_every_sec:
                removed = prune_agent(agent, args.max_q)
                save_checkpoint(ckpt, agent, state)
                if removed:
                    print(f"[prune] removed {removed} states")
                last_ckpt = now

            if now - last_report >= args.report_every_sec:
                write_report(report, state, agent, last_eval_result)
                avg_new = sum(new_obs_window[-100:]) / max(1, len(new_obs_window[-100:]))
                line = {
                    "at": time.strftime("%H:%M:%S"), "min": round(elapsed / 60, 1),
                    "phase": phase, "ep": ep, "steps": agent.steps,
                    "screens": st["screens_discovered"], "q": len(agent.q),
                    "eps": round(agent.epsilon(), 3),
                    "new_obs_rate": round(avg_new, 4),
                    "steps_per_sec": round(state["steps_per_sec"]),
                }
                with open(progress, "a", encoding="utf-8") as f:
                    f.write(json.dumps(line, ensure_ascii=False) + "\n")
                print(f"[progress] {line}")
                last_report = now

            if now - last_eval >= args.eval_every_sec:
                last_eval_result = evaluate(agent)
                last_eval = now
                passed = gate_pass(last_eval_result, state.get("screens", 0),
                                   state.get("scroll_positions", 0))
                print(f"[eval] {last_eval_result} gate={'PASS' if passed else 'FAIL'}")
    except KeyboardInterrupt:
        print("[stop] 收到中斷，儲存檢查點…")
    except Exception:  # noqa: BLE001
        print("[error] " + traceback.format_exc())

    save_checkpoint(ckpt, agent, state)
    write_report(report, state, agent, last_eval_result)
    print(f"[done] episodes={state['episodes']} steps={agent.steps} report={report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
