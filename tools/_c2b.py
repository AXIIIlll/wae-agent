import io
p = "tools/symbolic_sim/run_overnight.py"
s = io.open(p, encoding="utf-8").read()

# 1) 參數：C1 先跑幾分鐘再切 C2
s = s.replace('''    ap.add_argument("--max-q", type=int, default=300_000)''',
'''    ap.add_argument("--max-q", type=int, default=300_000)
    ap.add_argument("--c1-minutes", type=float, default=4.0,
                    help="好奇心探索(C1)先跑幾分鐘，之後自動切目標階段(C2)")''')

# 2) 狀態初始化加入 phase / goal_stats
s = s.replace('''    state = {
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "elapsed_min": 0.0, "episodes": 0, "screens": 0, "screens_total": 19,
        "steps_per_sec": 0, "curve": [],
    }''',
'''    state = {
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "elapsed_min": 0.0, "episodes": 0, "screens": 0, "screens_total": 19,
        "steps_per_sec": 0, "curve": [], "phase": "C1", "goal_stats": {},
    }''')

# 3) 主迴圈：C1 → C2（含 HER-lite）
start = s.index("    try:\n        while time.time() - t0 < args.hours * 3600:")
end = s.index("    except KeyboardInterrupt:")
new_loop = '''    rng_goal = __import__("random").Random(args.seed + 1)
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
                    nobs, r, done, _ = env.step(a)
                    _, is_new = agent.observe(obs, a, r, nobs, env.action_mask(), done)
                    if is_new:
                        ep_new += 1
                    obs = nobs
            else:
                goal_name, goal_fn = sample_goal(env, rng_goal)
                achieved = False
                while not done:
                    qobs = goal_key(goal_name, obs)
                    mask = env.action_mask()
                    a = agent.act(qobs, mask)
                    nobs, r, done, _ = env.step(a)
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
                    f.write(json.dumps(line, ensure_ascii=False) + "\\n")
                print(f"[progress] {line}")
                last_report = now

            if now - last_eval >= args.eval_every_sec:
                last_eval_result = evaluate(agent)
                last_eval = now
                passed = gate_pass(last_eval_result, state.get("screens", 0),
                                   state.get("scroll_positions", 0))
                print(f"[eval] {last_eval_result} gate={'PASS' if passed else 'FAIL'}")
    '''
s = s[:start] + new_loop + s[end:]

# 4) gate 呼叫處補上 train_scroll
s = s.replace('''                  f"| **基本操作門檻** | **{'PASS' if gate_pass(last_eval, state.get('screens', 0)) else 'FAIL'}** |",''',
'''                  f"| **基本操作門檻** | **{'PASS' if gate_pass(last_eval, state.get('screens', 0), state.get('scroll_positions', 0)) else 'FAIL'}** |",''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("C2 phase loop installed")
