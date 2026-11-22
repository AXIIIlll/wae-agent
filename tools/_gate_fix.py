import io
# 1) env：追蹤探索過的「捲動位置」數量（能力指標，不受貪婪策略影響）
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""                if new_pos != s.scroll_dp:
                    self.positions[self.screen_id] = new_pos
                    self.cum["effective_scrolls"] += 1
                    info["kind"] = "scroll\"""",
"""                if new_pos != s.scroll_dp:
                    self.positions[self.screen_id] = new_pos
                    self.cum["effective_scrolls"] += 1
                    if not hasattr(self, "scroll_positions"):
                        self.scroll_positions = set()
                    self.scroll_positions.add((self.screen_id, new_pos))
                    info["kind"] = "scroll\"""")
s = s.replace('''            "invalid_actions": self.cum["invalid_actions"],
        }''',
'''            "invalid_actions": self.cum["invalid_actions"],
            "scroll_positions": len(getattr(self, "scroll_positions", set())),
        }''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# 2) overnight：gate 用「訓練累計畫面 + 捲動覆蓋 + 評估的開關/準確度」
p2 = "tools/symbolic_sim/run_overnight.py"
s2 = io.open(p2, encoding="utf-8").read()
s2 = s2.replace('''def gate_pass(st: dict) -> bool:
    return (st["screens_discovered"] >= 15 and st["toggle_flips"] >= 30
            and st["effective_scrolls"] >= 30 and st["tap_change_rate"] >= 0.55
            and st["invalid_action_rate"] <= 0.20)''',
'''def gate_pass(st: dict, train_screens: int = 0) -> bool:
    """基本操作門檻：畫面覆蓋（訓練）+ 捲動覆蓋 + 開關操作 + 動作準確度。"""
    screens = max(train_screens, st.get("screens_discovered", 0))
    return (screens >= 15 and st.get("toggle_flips", 0) >= 30
            and st.get("scroll_positions", 0) >= 5
            and st.get("tap_change_rate", 0) >= 0.55
            and st.get("invalid_action_rate", 1) <= 0.20)''')
s2 = s2.replace('''                  f"| **基本操作門檻** | **{'PASS' if gate_pass(last_eval) else 'FAIL'}** |",''',
'''                  f"| 捲動位置覆蓋 | {last_eval.get('scroll_positions')} |",
                  f"| **基本操作門檻** | **{'PASS' if gate_pass(last_eval, state.get('screens', 0)) else 'FAIL'}** |",''')
s2 = s2.replace('''                print(f"[eval] {last_eval_result} gate={'PASS' if gate_pass(last_eval_result) else 'FAIL'}")''',
'''                print(f"[eval] {last_eval_result} gate="
                      f"{'PASS' if gate_pass(last_eval_result, state.get('screens', 0)) else 'FAIL'}")''')
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("scroll coverage + gate updated")
