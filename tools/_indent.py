import io
p = "tools/symbolic_sim/run_overnight.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace("        except KeyboardInterrupt:\n        print(\"[stop] 收到中斷，儲存檢查點…\")",
              "    except KeyboardInterrupt:\n        print(\"[stop] 收到中斷，儲存檢查點…\")")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("indent fixed")
