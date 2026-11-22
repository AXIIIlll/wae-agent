import io
p = "tools/symbolic_sim/make_replay.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace("from debug_view import (_screen_payload, action_name, build_html,  # noqa: E402\n                        build_storyboard)",
              "from debug_view import (action_name, build_html, build_storyboard,  # noqa: E402\n                        screen_payload)")
s = s.replace('from phone_env import ACTION_TAP_BASE, NUM_ACTIONS, SymbolicPhoneEnv  # noqa: E402',
              'from phone_env import ACTION_TAP_BASE, NUM_ACTIONS, SymbolicPhoneEnv  # noqa: E402')
s = s.replace('"screen": _screen_payload(env._screen()),',
              '"screen": screen_payload(env._screen(), env.m.to_dict()),')
s = s.replace('ok = build_storyboard(png_path, steps[:12])',
              'ok = build_storyboard(png_path, steps, cols=3, rows=2)')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# watch.py：render_ansi 參數仍是 (screen, highlight_slot, footer) → OK；action_name 已更新
p2 = "tools/symbolic_sim/watch.py"
s2 = io.open(p2, encoding="utf-8").read()
s2 = s2.replace("from debug_view import action_name, render_ansi     # noqa: E402",
                "from debug_view import action_name, render_ansi     # noqa: E402")
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("make_replay updated")
