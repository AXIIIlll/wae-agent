"""好奇心預訓練：讓 Agent 自己亂點，學會基本手機操作（無任務、無資料）。

用法：
  python tools/symbolic_sim/train.py --episodes 4000 --seed 7

輸出：
  - 學習曲線（每 N 回合統計）
  - 能力門檻判定（PASS/FAIL）
  - out/report.json、out/curve.csv
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from curiosity_agent import CuriosityAgent          # noqa: E402
from phone_env import SymbolicPhoneEnv, NUM_ACTIONS  # noqa: E402

# 能力門檻：達到才代表「有基本行動能力」，可以進入任務階段（C2）
GATE = {
    "screens_discovered_min": 10,     # 至少要逛到 10/12 個畫面
    "toggle_flips_min": 30,           # 至少要成功切換開關 30 次
    "effective_scrolls_min": 30,      # 至少有效捲動 30 次
    "tap_change_rate_min": 0.55,      # 點擊造成變化的比例
    "invalid_action_rate_max": 0.20,  # 無效動作比例上限
    "novelty_ratio_max": 0.35,        # 後期新觀測率 ≤ 早期的 35%（代表已摸熟大部分環境）
}


def evaluate_gate(stats: dict, novelty_rate_tail: float, novelty_rate_early: float) -> tuple[bool, dict]:
    tap_rate = (stats["taps_with_change"] / stats["taps_total"]) if stats["taps_total"] else 0.0
    total_actions = stats["valid_actions"] + stats["invalid_actions"]
    invalid_rate = (stats["invalid_actions"] / total_actions) if total_actions else 1.0
    checks = {
        "screens": stats["screens_discovered"] >= GATE["screens_discovered_min"],
        "toggles": stats["toggle_flips"] >= GATE["toggle_flips_min"],
        "scrolls": stats["effective_scrolls"] >= GATE["effective_scrolls_min"],
        "tap_change": tap_rate >= GATE["tap_change_rate_min"],
        "invalid_rate": invalid_rate <= GATE["invalid_action_rate_max"],
        "novelty_saturated": (
            novelty_rate_tail <= max(0.02, novelty_rate_early * GATE["novelty_ratio_max"])
        ),
    }
    detail = {
        "screens": f"{stats['screens_discovered']}/{stats['screens_total']}",
        "toggle_flips": stats["toggle_flips"],
        "effective_scrolls": stats["effective_scrolls"],
        "tap_change_rate": round(tap_rate, 3),
        "invalid_action_rate": round(invalid_rate, 3),
        "novelty_rate_early": round(novelty_rate_early, 4),
        "novelty_rate_tail": round(novelty_rate_tail, 4),
    }
    return all(checks.values()), {"checks": checks, "detail": detail}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--episodes", type=int, default=4000)
    ap.add_argument("--max-steps", type=int, default=120)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", type=str, default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    env = SymbolicPhoneEnv(seed=args.seed, max_steps=args.max_steps)
    agent = CuriosityAgent(NUM_ACTIONS, seed=args.seed, epsilon_decay_steps=max(1000, args.episodes * args.max_steps // 2))

    curve = []
    new_obs_window = []
    early_window = []
    for ep in range(1, args.episodes + 1):
        obs = env.reset()
        ep_reward = 0.0
        ep_new = 0
        done = False
        while not done:
            mask = env.action_mask()
            action = agent.act(obs, mask)
            next_obs, env_reward, done, info = env.step(action)
            total, is_new = agent.observe(obs, action, env_reward, next_obs, env.action_mask(), done)
            ep_reward += total
            if is_new:
                ep_new += 1
            obs = next_obs
        rate = ep_new / max(1, args.max_steps)
        new_obs_window.append(rate)
        if ep <= max(1, args.episodes // 10):
            early_window.append(rate)
        if len(new_obs_window) > 200:
            new_obs_window.pop(0)
        if ep % max(1, args.episodes // 20) == 0 or ep == 1:
            stats = env.stats()
            novelty_tail = sum(new_obs_window[-100:]) / max(1, len(new_obs_window[-100:]))
            curve.append({
                "episode": ep,
                "epsilon": round(agent.epsilon(), 3),
                "screens": stats["screens_discovered"],
                "toggle_flips": stats["toggle_flips"],
                "scrolls": stats["effective_scrolls"],
                "new_obs_rate": round(novelty_tail, 4),
                "q_states": len(agent.q),
                "avg_reward": round(ep_reward, 2),
            })
            print(
                f"ep={ep:5d} eps={agent.epsilon():.2f} screens={stats['screens_discovered']:2d}/{stats['screens_total']} "
                f"toggles={stats['toggle_flips']:4d} scrolls={stats['effective_scrolls']:4d} "
                f"newObs={novelty_tail:.3f} q={len(agent.q)}"
            )

    stats = env.stats()
    novelty_tail = sum(new_obs_window[-100:]) / max(1, len(new_obs_window[-100:]))
    novelty_early = sum(early_window) / max(1, len(early_window))
    passed, detail = evaluate_gate(stats, novelty_tail, novelty_early)

    report = {
        "config": vars(args),
        "env": stats,
        "agent": {"q_states": len(agent.q), "pairs_seen": len(agent.pair_seen),
                  "obs_counts": len(agent.obs_counts)},
        "gate": detail,
        "gate_passed": passed,
        "curve": curve,
    }
    with open(os.path.join(args.out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    with open(os.path.join(args.out, "curve.csv"), "w", encoding="utf-8") as f:
        f.write("episode,epsilon,screens,toggle_flips,scrolls,new_obs_rate,q_states,avg_reward\n")
        for row in curve:
            f.write(",".join(str(row[k]) for k in
                            ("episode", "epsilon", "screens", "toggle_flips", "scrolls",
                             "new_obs_rate", "q_states", "avg_reward")) + "\n")

    print()
    print("=== 能力門檻（C1 → C2 的閘門）===")
    for k, v in detail["detail"].items():
        print(f"  {k:20s} = {v}")
    print(f"  => {'PASS（可以進入任務階段）' if passed else 'FAIL（還在學基本操作）'}")
    print(f"report: {os.path.join(args.out, 'report.json')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
