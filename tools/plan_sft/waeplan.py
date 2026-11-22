"""WAE planner 的 prompt 渲染與計畫驗證（與 App 推論端逐字一致）。

唯一真相來源：
  app/src/main/assets/agent/prompts/plan_system.txt
  app/src/main/assets/agent/prompts/plan_user.txt
  app/src/main/java/com/wae/agent/llm/PromptBuilder.kt  (wrapChat)

任何一端改了模板，這裡會自動跟著變（直接讀 assets）。
"""
from __future__ import annotations

import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
# 可用 WAE_PROMPT_DIR 指向別的模板目錄（A/B 舊 prompt 用）
PROMPT_DIR = os.environ.get("WAE_PROMPT_DIR") or os.path.join(
    ROOT, "app", "src", "main", "assets", "agent", "prompts"
)


def _read(name: str) -> str:
    with open(os.path.join(PROMPT_DIR, name), "r", encoding="utf-8") as f:
        return f.read()


SYSTEM = _read("plan_system.txt").rstrip("\n")
USER_TMPL = _read("plan_user.txt").rstrip("\n")


def render_user(goal: str, selected_app: str | None, installed_apps: list[str]) -> str:
    """完全對齊 PromptBuilder.buildChatPlanPrompt。"""
    return (
        USER_TMPL.replace("{goal}", goal)
        .replace("{selectedApp}", selected_app if selected_app else "（無）")
        .replace("{installedApps}", ", ".join(installed_apps[:30]))
    )


def render_chatml(goal: str, selected_app: str | None, installed_apps: list[str]) -> str:
    """完全對齊 PromptBuilder.wrapChat(system, user)。"""
    user = render_user(goal, selected_app, installed_apps)
    return (
        "<|im_start|>system\n" + SYSTEM + "<|im_end|>\n"
        "<|im_start|>user\n" + user + "<|im_end|>\n"
        "<|im_start|>assistant\n"
    )


ASSISTANT_SUFFIX = "<|im_end|>\n"


# ---------------------------------------------------------------- 計畫驗證

def validate_plan(obj) -> tuple[bool, str]:
    """回傳 (是否合法, 原因)。規則與 App 端 PlanParser + plan.schema.json 一致。"""
    if not isinstance(obj, dict):
        return False, "not_object"
    if obj.get("version") != 1:
        return False, "version"
    for k in ("goalKind", "summary", "steps"):
        if k not in obj:
            return False, f"missing_{k}"
    steps = obj["steps"]
    if not isinstance(steps, list) or not (1 <= len(steps) <= 8):
        return False, "steps_len"
    for i, s in enumerate(steps):
        if not isinstance(s, dict):
            return False, f"step{i}_not_object"
        if not str(s.get("do", "")).strip():
            return False, f"step{i}_do"
        if not str(s.get("verify", "")).strip():
            return False, f"step{i}_verify"
        if "options" in s and not isinstance(s["options"], list):
            return False, f"step{i}_options"
    return True, "ok"


def load_jsonl(path: str):
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield json.loads(line)
