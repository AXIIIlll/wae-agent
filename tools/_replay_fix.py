import io
# 1) debug_view: 分鏡圖改成「整段回放的均勻取樣」，才看得到多樣畫面
p = "tools/symbolic_sim/debug_view.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""    picks = steps[: cols * rows]""",
"""    total_cells = cols * rows
    if len(steps) <= total_cells:
        picks = steps
    else:
        # 均勻取樣：讓分鏡圖涵蓋整段回放（而不是只看前 12 步）
        picks = [steps[int(round(i * (len(steps) - 1) / (total_cells - 1)))]
                 for i in range(total_cells)]""")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# 2) make_replay：回放時用「混合策略」（epsilon 0.30）→ 畫面多樣、看得出探索行為
p2 = "tools/symbolic_sim/make_replay.py"
s2 = io.open(p2, encoding="utf-8").read()
s2 = s2.replace("""    # 回放：用訓練後的策略（低 epsilon）跑幾回合
    replay_env = SymbolicPhoneEnv(seed=args.seed + 1, max_steps=args.replay_steps)""",
"""    # 回放：刻意用「混合策略」（epsilon=0.30）→ 看得出探索與利用的差異，畫面也更豐富
    agent.eps_start = 0.30
    agent.eps_end = 0.30
    agent.eps_decay_steps = 1
    agent.steps = 0

    # 回放：用訓練後的策略跑幾回合
    replay_env = SymbolicPhoneEnv(seed=args.seed + 1, max_steps=args.replay_steps)""")
s2 = s2.replace("""    html_path = os.path.join(OUT, "replay.html")""",
"""    # 每回合結尾補一筆「回到 home」的結尾畫面（讓分鏡有段落感）
    html_path = os.path.join(OUT, "replay.html")""")
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("replay variety patched")
