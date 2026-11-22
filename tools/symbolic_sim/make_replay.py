"""產生 HTML 回放 + PNG 分鏡圖（給人看的「AI 實際做了什麼」）。"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from curiosity_agent import CuriosityAgent                        # noqa: E402
from debug_view import (action_name, build_html, build_storyboard,  # noqa: E402
                        screen_payload)
from phone_env import ACTION_TAP_BASE, NUM_ACTIONS, SymbolicPhoneEnv  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def record_episode(env: SymbolicPhoneEnv, agent: CuriosityAgent, max_steps: int,
                   step_counter: list) -> list:
    """跑一個回合並記錄每一步的可視化資料。"""
    obs = env.reset()
    steps = []
    done = False
    while not done:
        mask = env.action_mask()
        action = agent.act(obs, mask)
        screen_before = env._screen()
        slot = action - ACTION_TAP_BASE if action >= ACTION_TAP_BASE else -1
        action_name_str = action_name(action, screen_before)
        nobs, env_reward, done, info = env.step(action)
        novelty = agent.novelty(obs)
        new_obs = agent.obs_counts[obs] == 1
        total, _ = agent.observe(obs, action, env_reward, nobs, env.action_mask(), done)
        stats = env.stats()
        steps.append({
            "index": step_counter[0],
            "screen": screen_payload(env._screen(), env.m.to_dict()),
            "chosenSlot": slot,
            "actionName": action_name_str,
            "changed": info["changed"],
            "invalid": info["kind"] == "invalid",
            "envReward": env_reward,
            "totalReward": total,
            "novelty": novelty,
            "newObs": new_obs,
            "screens": stats["screens_discovered"],
            "screensTotal": stats["screens_total"],
            "qStates": len(agent.q),
        })
        step_counter[0] += 1
        obs = nobs
    return steps


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--episodes", type=int, default=1500)
    ap.add_argument("--max-steps", type=int, default=120)
    ap.add_argument("--replay", type=int, default=1, help="記錄幾個回合作為回放")
    ap.add_argument("--replay-steps", type=int, default=60)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--replay-epsilon", type=float, default=0.95,
                    help="回放時的策略：1.0=純探索（畫面多樣）、0.05=幾乎只利用已學策略")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    env = SymbolicPhoneEnv(seed=args.seed, max_steps=args.max_steps)
    agent = CuriosityAgent(NUM_ACTIONS, seed=args.seed,
                           epsilon_decay_steps=max(1000, args.episodes * args.max_steps // 2))

    curve = []
    new_obs_window = []
    for ep in range(1, args.episodes + 1):
        obs = env.reset()
        ep_new = 0
        done = False
        while not done:
            mask = env.action_mask()
            a = agent.act(obs, mask)
            nobs, r, done, _ = env.step(a)
            _, is_new = agent.observe(obs, a, r, nobs, env.action_mask(), done)
            if is_new:
                ep_new += 1
            obs = nobs
        rate = ep_new / max(1, args.max_steps)
        new_obs_window.append(rate)
        if ep % max(1, args.episodes // 20) == 0:
            st = env.stats()
            curve.append({
                "episode": ep,
                "screens": st["screens_discovered"],
                "new_obs_rate": round(sum(new_obs_window[-100:]) / len(new_obs_window[-100:]), 4),
                "toggle_flips": st["toggle_flips"],
                "scrolls": st["effective_scrolls"],
            })

    # 回放策略由參數決定：預設 0.95（好奇探索）→ 分鏡圖才會出現各種頁面
    agent.eps_start = args.replay_epsilon
    agent.eps_end = args.replay_epsilon
    agent.eps_decay_steps = 1
    agent.steps = 0

    # 回放：用訓練後的策略跑幾回合
    replay_env = SymbolicPhoneEnv(seed=args.seed + 1, max_steps=args.replay_steps)
    counter = [0]
    steps: list = []
    for _ in range(args.replay):
        steps += record_episode(replay_env, agent, args.replay_steps, counter)

    # 每回合結尾補一筆「回到 home」的結尾畫面（讓分鏡有段落感）
    html_path = os.path.join(OUT, "replay.html")
    build_html(html_path, steps, curve, {"episodes": args.episodes, "seed": args.seed,
                                         "steps": len(steps)})
    png_path = os.path.join(OUT, "storyboard.png")
    ok = build_storyboard(png_path, steps, cols=3, rows=2)
    print(f"HTML: {html_path}")
    print(f"PNG : {png_path} ({'ok' if ok else 'Pillow 不可用'})")
    print(f"回放步數: {len(steps)}，學習曲線點: {len(curve)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
