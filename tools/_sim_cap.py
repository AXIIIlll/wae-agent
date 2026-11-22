import io
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()
# 1) 只保留內在獎勵：環境本身不給「有變化就加分」（那會讓 Agent 無限反覆開關）
s = s.replace("""            reward = 0.02 if info["changed"] else 0.0""",
"""            # 環境不給外在獎勵：唯一訊號是「無效動作」的小懲罰
            reward = 0.0""")
# 2) 計算機顯示長度上限（開放式狀態會讓好奇心永遠不飽和）
s = s.replace("""            if node.label.isdigit():
                self.calc_display = node.label if self.calc_display in ("0", "結果") else self.calc_display + node.label""",
"""            if node.label.isdigit():
                if self.calc_display in ("0", "結果"):
                    self.calc_display = node.label
                elif len(self.calc_display) < 4:
                    self.calc_display += node.label""")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("env rewards/state capped")
