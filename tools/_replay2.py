import io
p = "tools/symbolic_sim/make_replay.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace('''    ap.add_argument("--seed", type=int, default=7)''',
'''    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--replay-epsilon", type=float, default=0.95,
                    help="回放時的策略：1.0=純探索（畫面多樣）、0.05=幾乎只利用已學策略")''')
s = s.replace('''    # 回放：刻意用「混合策略」（epsilon=0.30）→ 看得出探索與利用的差異，畫面也更豐富
    agent.eps_start = 0.30
    agent.eps_end = 0.30''',
'''    # 回放策略由參數決定：預設 0.95（好奇探索）→ 分鏡圖才會出現各種頁面
    agent.eps_start = args.replay_epsilon
    agent.eps_end = args.replay_epsilon''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("replay epsilon flag added")
