import io
p = "tools/symbolic_sim/run_overnight.py"
s = io.open(p, encoding="utf-8").read()

# 1) import 可視化 payload / 動作名稱
s = s.replace("from curiosity_agent import CuriosityAgent            # noqa: E402",
"""from curiosity_agent import CuriosityAgent            # noqa: E402
from debug_view import action_name, screen_payload     # noqa: E402""")

# 2) 參數：live 更新頻率
s = s.replace('''    ap.add_argument("--c1-minutes", type=float, default=4.0,''',
'''    ap.add_argument("--live-every-sec", type=float, default=0.4,
                    help="即時儀表板更新頻率（秒）")
    ap.add_argument("--c1-minutes", type=float, default=4.0,''')

# 3) live 寫入函式（原子寫檔）
s = s.replace('''def evaluate(agent: CuriosityAgent, episodes: int = 20, seed: int = 999) -> dict:''',
'''def write_live(path: str, env, agent: CuriosityAgent, phase: str, goal_name: str,
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


def evaluate(agent: CuriosityAgent, episodes: int = 20, seed: int = 999) -> dict:''')

# 4) 主迴圈：初始化 recent/live 路徑與計時
s = s.replace('''    rng_goal = __import__("random").Random(args.seed + 1)''',
'''    live_path = os.path.join(OUT, "live.json")
    recent: list = []
    last_live = 0.0
    rng_goal = __import__("random").Random(args.seed + 1)''')

# 5) C1 loop：每次 step 後節流寫 live
s = s.replace('''            if phase == "C1":
                while not done:
                    mask = env.action_mask()
                    a = agent.act(obs, mask)
                    nobs, r, done, _ = env.step(a)
                    _, is_new = agent.observe(obs, a, r, nobs, env.action_mask(), done)
                    if is_new:
                        ep_new += 1
                    obs = nobs''',
'''            if phase == "C1":
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
                        last_live = tn''')

# 6) C2 loop：同樣節流寫 live（帶目標名稱）
s = s.replace('''                while not done:
                    qobs = goal_key(goal_name, obs)
                    mask = env.action_mask()
                    a = agent.act(qobs, mask)
                    nobs, r, done, _ = env.step(a)
                    if goal_fn(env):''',
'''                while not done:
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
                    if goal_fn(env):''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("live.json writer added")
