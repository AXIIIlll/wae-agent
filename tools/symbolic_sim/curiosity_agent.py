"""純好奇心 Agent（第一階段：沒有任務目標）。

內在獎勵 = 新穎度（count-based novelty）
         + 新 (觀測, 動作) 配對的探索獎金
         - 重複同一動作的懲罰
（後續階段才會加入 learning-progress 與 empowerment，見 proposal 附錄 A.3）

策略用**表格 Q-learning**：符號環境狀態數有限，表格法比神經網路更適合當第一個基線，
而且完全可重現、可審計（每個決定的 Q 值都查得到）。
"""

from __future__ import annotations

import math
import random
from collections import defaultdict
from typing import Dict, List, Tuple


class CuriosityAgent:
    def __init__(
        self,
        num_actions: int,
        seed: int = 0,
        alpha: float = 0.20,
        gamma: float = 0.95,
        epsilon_start: float = 1.0,
        epsilon_end: float = 0.05,
        epsilon_decay_steps: int = 60_000,
        novelty_weight: float = 1.0,
        new_pair_bonus: float = 0.30,
        repeat_penalty: float = 0.05,
    ):
        self.n = num_actions
        self.rng = random.Random(seed)
        self.alpha, self.gamma = alpha, gamma
        self.eps_start, self.eps_end = epsilon_start, epsilon_end
        self.eps_decay_steps = epsilon_decay_steps
        self.novelty_weight = novelty_weight
        self.new_pair_bonus = new_pair_bonus
        self.repeat_penalty = repeat_penalty

        self.q: Dict[str, List[float]] = {}
        self.obs_counts: Dict[str, int] = defaultdict(int)
        self.pair_seen: set = set()
        self.steps = 0
        self.last_obs = ""
        self.last_action = -1
        self.last_repeat = False

    # ── 表徵 / 新穎度 ────────────────────────────────────────────────
    def novelty(self, obs: str) -> float:
        self.obs_counts[obs] += 1
        return 1.0 / math.sqrt(self.obs_counts[obs])

    def q_values(self, obs: str) -> List[float]:
        if obs not in self.q:
            self.q[obs] = [0.0] * self.n
        return self.q[obs]

    def epsilon(self) -> float:
        if self.steps >= self.eps_decay_steps:
            return self.eps_end
        ratio = self.steps / self.eps_decay_steps
        return self.eps_start + (self.eps_end - self.eps_start) * ratio

    # ── 決策 ────────────────────────────────────────────────────────
    def act(self, obs: str, mask: List[bool]) -> int:
        self.steps += 1
        valid = [i for i, ok in enumerate(mask) if ok]
        if not valid:
            return 0
        if self.rng.random() < self.epsilon():
            return self.rng.choice(valid)
        q = self.q_values(obs)
        best_val = max(q[i] for i in valid)
        best = [i for i in valid if q[i] == best_val]
        return self.rng.choice(best)

    # ── 學習 ────────────────────────────────────────────────────────
    def observe(
        self,
        prev_obs: str,
        action: int,
        reward: float,
        obs: str,
        mask: List[bool],
        done: bool,
    ) -> Tuple[float, bool]:
        """回傳 (含內在獎勵的總獎勵, 是否為新觀測)。"""
        is_new = self.obs_counts[obs] == 0
        intrinsic = self.novelty_weight * self.novelty(obs)
        pair = (obs, action)
        if pair not in self.pair_seen:
            self.pair_seen.add(pair)
            intrinsic += self.new_pair_bonus
        if self.last_repeat:
            intrinsic -= self.repeat_penalty
        total = reward + max(0.0, intrinsic)

        q = self.q_values(prev_obs)
        target = total
        if not done:
            nq = self.q_values(obs)
            target += self.gamma * max(nq[i] for i, ok in enumerate(mask) if ok) if any(mask) else 0.0
        q[action] += self.alpha * (target - q[action])

        self.last_repeat = action == self.last_action
        self.last_action = action
        return total, is_new
