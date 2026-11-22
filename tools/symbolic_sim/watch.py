"""即時觀看：在終端機看 Agent 一步步操作（符號模擬器）。"""

from __future__ import annotations

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from curiosity_agent import CuriosityAgent          # noqa: E402
from debug_view import action_name, render_ansi     # noqa: E402
from phone_env import ACTION_TAP_BASE, NUM_ACTIONS, SymbolicPhoneEnv  # noqa: E402


def _setup_console() -> None:
    """Windows 主控台預設 cp950，畫不出方框/圖示 → 改成 UTF-8。"""
    try:
        import os as _os
        _os.system("chcp 65001 > nul")
    except Exception:
        pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def main() -> int:
    _setup_console()
    ap = argparse.ArgumentParser()
    ap.add_argument("--episodes", type=int, default=2)
    ap.add_argument("--steps", type=int, default=40)
    ap.add_argument("--delay", type=float, default=0.25)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--pretrain", type=int, default=600, help="先靜默訓練幾個回合再開始看")
    args = ap.parse_args()

    env = SymbolicPhoneEnv(seed=args.seed, max_steps=args.steps)
    agent = CuriosityAgent(NUM_ACTIONS, seed=args.seed, epsilon_decay_steps=40_000,
                           epsilon_start=1.0, epsilon_end=0.05)

    print(f"先靜默訓練 {args.pretrain} 回合（讓它有一點基礎）…")
    for _ in range(args.pretrain):
        obs = env.reset()
        done = False
        while not done:
            mask = env.action_mask()
            a = agent.act(obs, mask)
            nobs, r, done, _ = env.step(a)
            agent.observe(obs, a, r, nobs, env.action_mask(), done)
            obs = nobs

    for ep in range(1, args.episodes + 1):
        env.reset()
        obs = env.observe()
        print(f"\n=== 第 {ep} 回合（好奇心驅動，無任務目標）===")
        time.sleep(0.4)
        done = False
        while not done:
            mask = env.action_mask()
            a = agent.act(obs, mask)
            screen = env._screen()
            slot = a - ACTION_TAP_BASE if a >= ACTION_TAP_BASE else -1
            nobs, r, done, info = env.step(a)
            total, is_new = agent.observe(obs, a, r, nobs, env.action_mask(), done)
            stats = env.stats()
            footer = [
                f"動作：{action_name(a, screen)}   結果：{info['kind']}"
                + ("（畫面有變化）" if info["changed"] else ""),
                f"新奇度 +{agent.novelty(obs):.3f}  總獎勵 {total:.3f}"
                + ("   ★首次看到此畫面" if is_new else ""),
                f"已探索畫面 {stats['screens_discovered']}/{stats['screens_total']}  "
                f"開關 {stats['toggle_flips']}  捲動 {stats['effective_scrolls']}  "
                f"Q狀態 {len(agent.q)}",
            ]
            sys.stdout.write("\x1b[2J\x1b[H")
            print(render_ansi(screen, highlight_slot=slot, footer=footer))
            sys.stdout.flush()
            time.sleep(args.delay)
            obs = nobs
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
