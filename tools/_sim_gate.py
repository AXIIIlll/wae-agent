import io
p = "tools/symbolic_sim/train.py"
s = io.open(p, encoding="utf-8").read()

# 門檻改為「相對下降」：早期 vs 後期的新觀測率比值（開放式畫面如計算機永遠有新觀測）
s = s.replace('''    "novelty_rate_tail_max": 0.02,    # 後期新觀測率要趨近 0（代表環境已摸熟）''',
'''    "novelty_ratio_max": 0.35,        # 後期新觀測率 ≤ 早期的 35%（代表已摸熟大部分環境）''')

s = s.replace('''def evaluate_gate(stats: dict, novelty_rate_tail: float) -> tuple[bool, dict]:''',
'''def evaluate_gate(stats: dict, novelty_rate_tail: float, novelty_rate_early: float) -> tuple[bool, dict]:''')
s = s.replace('''        "novelty_saturated": novelty_rate_tail <= GATE["novelty_rate_tail_max"],''',
'''        "novelty_saturated": (
            novelty_rate_tail <= max(0.02, novelty_rate_early * GATE["novelty_ratio_max"])
        ),''')
s = s.replace('''        "novelty_rate_tail": round(novelty_rate_tail, 4),''',
'''        "novelty_rate_early": round(novelty_rate_early, 4),
        "novelty_rate_tail": round(novelty_rate_tail, 4),''')

s = s.replace('''    curve = []
    new_obs_window = []''',
'''    curve = []
    new_obs_window = []
    early_window = []''')
s = s.replace('''        new_obs_window.append(ep_new / max(1, args.max_steps))''',
'''        rate = ep_new / max(1, args.max_steps)
        new_obs_window.append(rate)
        if ep <= max(1, args.episodes // 10):
            early_window.append(rate)''')
s = s.replace('''    stats = env.stats()
    novelty_tail = sum(new_obs_window[-100:]) / max(1, len(new_obs_window[-100:]))
    passed, detail = evaluate_gate(stats, novelty_tail)''',
'''    stats = env.stats()
    novelty_tail = sum(new_obs_window[-100:]) / max(1, len(new_obs_window[-100:]))
    novelty_early = sum(early_window) / max(1, len(early_window))
    passed, detail = evaluate_gate(stats, novelty_tail, novelty_early)''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("gate updated to relative novelty decay")
