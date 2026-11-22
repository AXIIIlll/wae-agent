import io
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()
# 首頁搜尋列位置修正（不要壓到 app bar）
s = s.replace("""        nodes.append(Node(10, "text", "content", "搜尋",
                          Rect(16, self.body_top - 40 if self.body_top > 40 else 8,
                               w - 32, 40)))""",
"""        nodes.append(Node(10, "text", "input", "搜尋",
                          Rect(16, self.body_top, w - 32, 40)))""")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("home search fixed")
