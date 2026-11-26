import re
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core\WaeV26PipelineOrchestrator.kt")
src = P.read_text(encoding="utf-8")

pat = re.compile(
    r"CuaDecisionResult\(\s*\n"
    r"\s*frameId = optionSet\.frameId,\s*\n"
    r"\s*windowIdx = optionSet\.windowIdx,\s*\n"
    r"\s*outcomeType = DecisionOutcomeType\.REJECT,\s*\n"
    r"\s*selectedOption = null,\s*\n"
    r"\s*top1Prob = p1,\s*\n"
    r"\s*top2Prob = p2,\s*\n"
    r"\s*margin = margin,\s*\n"
    r'\s*reason = ("[^\n]*?")\s*\n'
    r"\s*\)",
    re.M,
)


def repl(m):
    reason = m.group(1)
    return (
        "rejectDecision(\n"
        "                    optionSet = optionSet,\n"
        "                    p1 = p1,\n"
        "                    p2 = p2,\n"
        "                    margin = margin,\n"
        f"                    reason = {reason}\n"
        "                )"
    )


src2, n = pat.subn(repl, src)
P.write_text(src2, encoding="utf-8")
print("replaced:", n)
print("remaining REJECT constructions:", src2.count("outcomeType = DecisionOutcomeType.REJECT"))
