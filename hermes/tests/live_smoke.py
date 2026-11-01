#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""狂人日记 Hermes 插件 - 生产环境只读冒烟测试。

用法（PowerShell 5.1）：
  <项目>/backend/.venv/Scripts/python.exe -X utf8 hermes/tests/live_smoke.py

需要环境变量：
  KUANGREN_API_URL   生产基址，例如 http://159.75.236.90/kuangren-api
  KUANGREN_TOKEN     与后端 .env 中 KUANGREN_TOKEN 一致

只调用只读工具（get_panel / list_tasks / get_stats / list_pending_requests），
不写任何数据；输出不包含 token。任何一项失败 exit 1。
"""
import json
import os
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "plugin"

# --- stub tools.registry，与 hermes 运行时返回类型一致 ---
def _tool_result(data=None, **kwargs):
    payload = {"ok": True, "data": data if data is not None else {}}
    payload.update(kwargs)
    return json.dumps(payload, ensure_ascii=False)

def _tool_error(message, **extra):
    payload = {"ok": False, "error": str(message)}
    payload.update(extra)
    return json.dumps(payload, ensure_ascii=False)

tools_pkg = types.ModuleType("tools")
registry_mod = types.ModuleType("tools.registry")
registry_mod.tool_result = _tool_result
registry_mod.tool_error = _tool_error
tools_pkg.registry = registry_mod
sys.modules.setdefault("tools", tools_pkg)
sys.modules.setdefault("tools.registry", registry_mod)

# --- 加载插件 ---
sys.path.insert(0, str(PLUGIN_DIR))
import importlib.util

spec = importlib.util.spec_from_file_location("kuangren_diary_plugin", str(PLUGIN_DIR / "__init__.py"))
plugin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plugin)

class CaptureCtx:
    def __init__(self):
        self.tools = {}
    def register_tool(self, name, toolset, schema, handler, **kwargs):
        self.tools[name] = handler

ctx = CaptureCtx()
plugin.register(ctx)

failures = []
def run(name, handler, args):
    try:
        raw = handler(args)
    except Exception as exc:  # noqa: BLE001
        failures.append(f"{name}: 异常 {type(exc).__name__}: {exc}")
        print(f"FAIL {name}: {exc}")
        return None
    try:
        payload = json.loads(raw)
    except Exception:
        failures.append(f"{name}: 返回不是 JSON")
        print(f"FAIL {name}: 返回不是 JSON")
        return None
    if not payload.get("ok"):
        failures.append(f"{name}: {payload.get('error')}")
        print(f"FAIL {name}: {payload.get('error')}")
        return None
    data = payload.get("data") or {}
    print(f"PASS {name}")
    return data

if not os.environ.get("KUANGREN_TOKEN"):
    print("SKIP 未设置 KUANGREN_TOKEN，跳过生产冒烟")
    sys.exit(0)

panel = run("get_panel", ctx.tools["get_panel"], {})
if panel:
    quota = panel.get("quota") or {}
    print("  date=%s remaining=%s used=%s locked=%s" % (
        panel.get("date"), quota.get("remaining_minutes"), quota.get("used_minutes"), quota.get("locked")))
    profile = panel.get("profile") or {}
    print("  level=%s coins=%s streak=%s exam_days=%s" % (
        profile.get("level"), profile.get("coins"), profile.get("streak"), (panel.get("exam") or {}).get("days")))

tasks = run("list_tasks", ctx.tools["list_tasks"], {"status": "pending", "limit": 5})
if tasks:
    print("  pending=%s" % tasks.get("count"))

stats = run("get_stats", ctx.tools["get_stats"], {"range": "7d"})
if stats:
    print("  week_study_minutes=%s week_tasks_done=%s streak=%s" % (
        stats.get("study_minutes"), stats.get("tasks_done"), stats.get("streak")))

reqs = run("list_pending_requests", ctx.tools["list_pending_requests"], {"limit": 5})
if reqs:
    print("  pending_requests=%s" % len(reqs.get("requests") or reqs.get("items") or []))

if failures:
    print()
    print("失败 %d 项：" % len(failures))
    for f in failures:
        print(" -", f)
    sys.exit(1)
print()
print("ALL LIVE SMOKE CHECKS PASSED")
