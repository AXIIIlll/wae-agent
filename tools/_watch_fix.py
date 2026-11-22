import io
p = "tools/symbolic_sim/watch.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace('''def main() -> int:
    ap = argparse.ArgumentParser()''',
'''def _setup_console() -> None:
    """Windows 主控台預設 cp950，畫不出方框/圖示 → 改成 UTF-8。"""
    try:
        import os as _os
        _os.system("chcp 65001 > nul")
    except Exception:
        pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def main() -> int:
    _setup_console()
    ap = argparse.ArgumentParser()''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("console encoding fixed")
