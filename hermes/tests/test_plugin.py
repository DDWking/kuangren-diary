# -*- coding: utf-8 -*-
"""狂人日记 Hermes 插件本地自测（不依赖 Hermes 运行时）。

做法：
  - 用 stub 的 tools.registry 提供 tool_error / tool_result；
  - 起一个本地 mock HTTP 后端（http.server），按 docs/api.md 形状返回；
  - 加载 hermes/plugin/__init__.py，逐个调用 12 个工具，校验请求方法/路径/请求体/鉴权头。

运行（Windows，项目自带 backend venv 里的 Python）：
  backend\.venv\Scripts\python.exe hermes\tests\test_plugin.py
或以任意 Python 3.9+ 运行。
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import threading
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN_DIR = os.path.normpath(os.path.join(HERE, "..", "plugin"))
PLUGIN_FILE = os.path.join(PLUGIN_DIR, "__init__.py")
MANIFEST_FILE = os.path.join(PLUGIN_DIR, "plugin.yaml")

REQUESTS = []
CHECK_TOTAL = 0
CHECK_FAILED = 0
FAILURES = []


def check(name, condition, detail=""):
    global CHECK_TOTAL, CHECK_FAILED
    CHECK_TOTAL += 1
    if condition:
        print("PASS  " + name)
    else:
        CHECK_FAILED += 1
        FAILURES.append(name + ((" :: " + str(detail)) if detail else ""))
        print("FAIL  " + name + ((" :: " + str(detail)) if detail else ""))


# ---------------------------------------------------------------------------
# 1) stub tools.registry
# ---------------------------------------------------------------------------


def _tool_error(message, **extra):
    data = {"error": str(message)}
    data.update(extra)
    return json.dumps(data, ensure_ascii=False)


def _tool_result(data=None, **kwargs):
    if data is not None:
        return json.dumps(data, ensure_ascii=False)
    return json.dumps(kwargs, ensure_ascii=False)


def install_stub():
    tools_pkg = types.ModuleType("tools")
    registry_mod = types.ModuleType("tools.registry")
    registry_mod.tool_error = _tool_error
    registry_mod.tool_result = _tool_result
    tools_pkg.registry = registry_mod
    sys.modules["tools"] = tools_pkg
    sys.modules["tools.registry"] = registry_mod


def load_plugin():
    spec = importlib.util.spec_from_file_location("kuangren_plugin", PLUGIN_FILE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ---------------------------------------------------------------------------
# 2) mock 后端
# ---------------------------------------------------------------------------


TASK = {
    "id": "task-1",
    "title": "政治：肖八选择题一节",
    "description": None,
    "category": "study",
    "difficulty": 3,
    "attribute": "intelligence",
    "due_date": "2026-11-02",
    "estimated_minutes": 45,
    "status": "pending",
    "repeat_rule": "none",
    "created_by": "user",
    "exp_reward": 40,
    "coin_reward": 15,
}
QUOTA = {
    "date": "2026-11-02",
    "base_minutes": 180,
    "bonus_minutes": 30,
    "used_minutes": 35,
    "remaining_minutes": 175,
    "locked": False,
    "lock_reason": None,
    "lock_until": None,
    "policy": {"curfew_start": "01:00", "curfew_end": "07:00"},
    "extensions": [],
    "violations_today": 0,
    "forced_tighten": False,
    "tighten_reason": None,
}
PROFILE = {
    "id": "default",
    "name": "宿主",
    "title": "初醒者",
    "level": 2,
    "exp": 20,
    "exp_next": 150,
    "exp_progress": 0.13,
    "coins": 55,
    "streak": 3,
    "attributes": {"intelligence": 6, "stamina": 0, "willpower": 0, "focus": 0, "order": 0},
    "exam_date": "2026-12-19",
    "exam_days": 47,
    "daily_study_target_minutes": 300,
    "total_study_minutes": 100,
    "total_tasks_done": 1,
}
POLICY = {
    "date": "2026-11-02",
    "daily_base_minutes": 180,
    "curfew_start": "01:00",
    "curfew_end": "07:00",
    "session_max_minutes": 60,
    "blacklist": ["steam.exe"],
    "notes": "学习完成再玩",
    "source": "hermes",
    "updated_by": "hermes",
    "updated_at": "2026-11-02T10:00:00+08:00",
}


def ok(data):
    return 200, {"ok": True, "data": data, "server_time": "2026-11-02T10:00:00+08:00"}


def fail(status, code, message):
    return status, {"ok": False, "error": {"code": code, "message": message}, "server_time": "2026-11-02T10:00:00+08:00"}


def route(method, path, query, body, headers):
    if path == "/kuangren-api/api/v1/panel":
        return ok(
            {
                "date": query.get("date", ["2026-11-02"])[0],
                "profile": PROFILE,
                "quota": QUOTA,
                "tasks": {"today": [TASK], "overdue": []},
                "diary": {"date": "2026-11-02", "mood": 3, "content": "今天背了 50 个单词", "ai_comment": None, "tags": []},
                "weight": {"latest": {"date": "2026-11-02", "weight_kg": 104.7}, "change_7d": -0.5},
                "stats": {"week_study_minutes": 420, "week_tasks_done": 12, "streak": 3},
                "notifications": [{"id": "n1", "title": "提醒", "body": "今天还没学习"}],
                "exam": {"date": "2026-12-19", "days": 47},
            }
        )
    if path == "/kuangren-api/api/v1/tasks" and method == "GET":
        return ok([TASK])
    if path == "/kuangren-api/api/v1/tasks" and method == "POST":
        created = dict(TASK)
        created.update(body or {})
        created["id"] = "task-new"
        return ok(created)
    if path.startswith("/kuangren-api/api/v1/tasks/") and path.endswith("/complete"):
        task = dict(TASK)
        task["status"] = "done"
        return ok(
            {
                "task": task,
                "rewards": {"exp": 40, "coins": 15, "attribute": "intelligence", "attribute_delta": 6, "streak_multiplier": 1.1},
                "profile": PROFILE,
                "quota": QUOTA,
                "achievements_unlocked": [{"id": "first_task", "title": "第一次睁眼"}],
            }
        )
    if path.startswith("/kuangren-api/api/v1/weight/"):
        entry = {"date": path.rsplit("/", 1)[-1], "source": "hermes"}
        entry.update(body or {})
        return ok(entry)
    if path.startswith("/kuangren-api/api/v1/diary/") and path.endswith("/comment"):
        return ok({"date": path.split("/")[-2], "ai_comment": (body or {}).get("ai_comment")})
    if path == "/kuangren-api/api/v1/policy" and method == "PUT":
        curfew_start = (body or {}).get("curfew_start")
        if curfew_start and curfew_start > "03:00":
            return fail(422, "policy_violation", "宵禁开始不得晚于 03:00（硬底线）")
        policy = dict(POLICY)
        policy.update(body or {})
        return ok(policy)
    if path == "/kuangren-api/api/v1/quota/grant":
        quota = dict(QUOTA)
        quota["bonus_minutes"] = QUOTA["bonus_minutes"] + int((body or {}).get("minutes", 0))
        return ok(quota)
    if path == "/kuangren-api/api/v1/stats/summary":
        return ok(
            {
                "range": query.get("range", ["7d"])[0],
                "study_minutes": 420,
                "tasks_done": 12,
                "completion_rate": 0.75,
                "quota_used_minutes": 300,
                "weight_change": -0.5,
                "streak": 3,
                "daily": [{"date": "2026-11-02", "study_minutes": 120, "tasks_done": 3, "quota_used": 45}],
                "attributes": {"intelligence": 12},
                "heatmap": [{"date": "2026-11-02", "value": 120}],
            }
        )
    if path == "/kuangren-api/api/v1/quota/requests" and method == "GET":
        return ok([{"id": "req-1", "date": "2026-11-02", "minutes": 30, "reason": "想打完这一把", "device_id": "pc-main", "status": "pending", "created_at": "x"}])
    if path.startswith("/kuangren-api/api/v1/quota/requests/") and path.endswith("/decide"):
        request = {"id": "req-1", "status": "approved" if (body or {}).get("approve") else "rejected", "minutes": 30, "decision_note": (body or {}).get("note"), "decided_by": "hermes"}
        return ok({"request": request, "quota": QUOTA})
    if path == "/kuangren-api/api/v1/reports":
        return ok({"id": "report-1", "title": (body or {}).get("title"), "content": (body or {}).get("content"), "kind": (body or {}).get("kind", "system"), "created_at": "2026-11-02T07:30:00+08:00"})
    return fail(404, "not_found", "没有这个接口：" + method + " " + path)


class MockHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _handle(self, method):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        try:
            body = json.loads(raw) if raw.strip() else None
        except ValueError:
            body = None
        headers = {k.lower(): v for k, v in self.headers.items()}
        REQUESTS.append({"method": method, "path": parsed.path, "query": query, "body": body, "headers": headers})
        status, payload = route(method, parsed.path, query, body, headers)
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def log_message(self, fmt, *args):
        pass


def last_request():
    return REQUESTS[-1] if REQUESTS else {}


def parse(out):
    try:
        return json.loads(out)
    except ValueError:
        return {"_raw": out}


def main():
    install_stub()
    with open(MANIFEST_FILE, "r", encoding="utf-8") as handle:
        manifest_text = handle.read()
    plugin = load_plugin()

    check("插件可导入，暴露 register()", callable(getattr(plugin, "register", None)))
    check("插件版本 1.0.0", plugin.VERSION == "1.0.0", plugin.VERSION)

    # ---- register + schema ----
    class FakeCtx:
        def __init__(self):
            self.tools = []

        def register_tool(self, **kwargs):
            self.tools.append(kwargs)

    ctx = FakeCtx()
    plugin.register(ctx)
    names = [tool["name"] for tool in ctx.tools]
    expected = [
        "get_panel", "list_tasks", "create_task", "complete_task", "log_weight", "write_diary_comment",
        "set_daily_policy", "grant_quota", "get_stats", "list_pending_requests", "decide_request", "write_report",
    ]
    check("注册 12 个工具", len(ctx.tools) == 12, names)
    check("工具名与 task-6 要求一致", names == expected, names)
    check("全部在 kuangren toolset", all(tool["toolset"] == "kuangren" for tool in ctx.tools))
    check("全部绑定 check_fn", all(callable(tool["check_fn"]) for tool in ctx.tools))
    check("schema 都是中文描述", all(tool["schema"]["description"].strip() for tool in ctx.tools) and any("额度是动态" in tool["schema"]["description"] for tool in ctx.tools if tool["name"] == "get_panel"))
    check("硬底线写进 set_daily_policy 描述", "03:00-07:00" in plugin._SCHEMAS["set_daily_policy"]["description"] and "policy_violation" in plugin._SCHEMAS["set_daily_policy"]["description"])
    check("硬底线写进 grant_quota 描述", "连续 3 个自然日" in plugin._SCHEMAS["grant_quota"]["description"])
    check("manifest provides_tools 含全部 12 个", all(("- " + name) in manifest_text for name in expected))
    check("manifest 声明环境变量", "KUANGREN_API_URL" in manifest_text and "KUANGREN_TOKEN" in manifest_text)

    # ---- 环境变量 / check_fn ----
    os.environ["KUANGREN_TOKEN"] = "test-token-123"
    os.environ["KUANGREN_API_URL"] = ""
    check("有 token 时 check_fn=True", plugin._check_available() is True)
    del os.environ["KUANGREN_TOKEN"]
    check("无 token 时 check_fn=False", plugin._check_available() is False)
    os.environ["KUANGREN_TOKEN"] = "test-token-123"

    check("_api_root 默认补 /api/v1", plugin._api_root() == "http://159.75.236.90/kuangren-api/api/v1", plugin._api_root())
    os.environ["KUANGREN_API_URL"] = "http://example.test/kuangren-api/"
    check("_api_root 去尾斜杠", plugin._api_root() == "http://example.test/kuangren-api/api/v1", plugin._api_root())
    os.environ["KUANGREN_API_URL"] = "http://example.test/base/api/v1"
    check("_api_root 已带 /api/v1 不重复", plugin._api_root() == "http://example.test/base/api/v1", plugin._api_root())

    # ---- 起 mock 后端 ----
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    os.environ["KUANGREN_API_URL"] = "http://127.0.0.1:" + str(port) + "/kuangren-api"
    check("_api_root 指向 mock", plugin._api_root() == "http://127.0.0.1:" + str(port) + "/kuangren-api/api/v1", plugin._api_root())

    try:
        # get_panel
        out = plugin._handle_get_panel({"date": "2026-11-02"})
        data = parse(out)
        check("get_panel 返回面板聚合", data.get("date") == "2026-11-02" and data.get("quota", {}).get("remaining_minutes") == 175 and len(data.get("tasks", {}).get("today", [])) == 1, data)
        req = last_request()
        check("get_panel 请求 GET /panel?date=", req.get("method") == "GET" and req.get("path") == "/kuangren-api/api/v1/panel" and req.get("query", {}).get("date") == ["2026-11-02"], req)
        check("鉴权头 Bearer token + X-Actor: hermes", req.get("headers", {}).get("authorization") == "Bearer test-token-123" and req.get("headers", {}).get("x-actor") == "hermes", req.get("headers"))

        # list_tasks
        out = plugin._handle_list_tasks({"status": "pending", "limit": 10})
        data = parse(out)
        check("list_tasks 返回 count+tasks", data.get("count") == 1 and data.get("tasks", [{}])[0].get("id") == "task-1", data)
        req = last_request()
        check("list_tasks 请求查询参数", req.get("query", {}).get("status") == ["pending"] and req.get("query", {}).get("limit") == ["10"], req.get("query"))

        # create_task
        out = plugin._handle_create_task({"title": "英语：阅读两篇", "category": "study", "difficulty": 4, "estimated_minutes": 30})
        data = parse(out)
        check("create_task 返回新任务", data.get("task", {}).get("title") == "英语：阅读两篇", data)
        req = last_request()
        body = req.get("body") or {}
        check("create_task POST 体字段齐全", req.get("method") == "POST" and body.get("category") == "study" and body.get("difficulty") == 4 and body.get("attribute") == "intelligence" and body.get("created_by") == "hermes" and len(body.get("due_date", "")) == 10, body)
        check("create_task 拒绝缺标题", "invalid_argument" in plugin._handle_create_task({}), plugin._handle_create_task({}))

        # complete_task
        out = plugin._handle_complete_task({"task_id": "task-1", "actual_minutes": 50, "note": "做完了"})
        data = parse(out)
        check("complete_task 返回奖励+等级", data.get("rewards", {}).get("exp") == 40 and data.get("profile", {}).get("level") == 2 and data.get("quota", {}).get("remaining_minutes") == 175, data)
        check("complete_task 解锁成就标题", data.get("achievements_unlocked") == ["第一次睁眼"], data.get("achievements_unlocked"))
        req = last_request()
        check("complete_task 带 Idempotency-Key", bool(req.get("headers", {}).get("idempotency-key")), req.get("headers"))
        check("complete_task 请求体含 actual_minutes", (req.get("body") or {}).get("actual_minutes") == 50 and (req.get("body") or {}).get("note") == "做完了", req.get("body"))

        # log_weight
        out = plugin._handle_log_weight({"date": "2026-11-02", "weight_kg": 104.7, "body_fat": 28.4, "note": "晨起空腹"})
        data = parse(out)
        check("log_weight 返回体重记录", data.get("weight_kg") == 104.7 and data.get("source") == "hermes" and data.get("body_fat") == 28.4, data)
        req = last_request()
        check("log_weight PUT /weight/{date}", req.get("method") == "PUT" and req.get("path") == "/kuangren-api/api/v1/weight/2026-11-02", req.get("path"))
        check("log_weight 拒绝缺 weight_kg", "invalid_argument" in plugin._handle_log_weight({}), plugin._handle_log_weight({}))

        # write_diary_comment
        out = plugin._handle_write_diary_comment({"date": "2026-11-02", "ai_comment": "今天很稳，明天把 408 补上。"})
        data = parse(out)
        check("write_diary_comment 返回点评", data.get("ai_comment") == "今天很稳，明天把 408 补上。", data)
        req = last_request()
        check("write_diary_comment 请求体字段 ai_comment", req.get("method") == "POST" and req.get("path") == "/kuangren-api/api/v1/diary/2026-11-02/comment" and (req.get("body") or {}).get("ai_comment"), req)

        # set_daily_policy 正常
        out = plugin._handle_set_daily_policy({"curfew_start": "2:00", "curfew_end": "07:00", "daily_base_minutes": 240, "reason": "周日放松"})
        data = parse(out)
        check("set_daily_policy 成功", data.get("daily_base_minutes") == 240, data)
        req = last_request()
        check("set_daily_policy 归一化 02:00 并 PUT /policy", req.get("method") == "PUT" and (req.get("body") or {}).get("curfew_start") == "02:00", req.get("body"))
        check("set_daily_policy 越界立即拒绝", "invalid_argument" in plugin._handle_set_daily_policy({"daily_base_minutes": 600}), plugin._handle_set_daily_policy({"daily_base_minutes": 600}))

        # 硬底线：后端 422
        out = plugin._handle_set_daily_policy({"curfew_start": "04:00"})
        data = parse(out)
        check("触碰硬底线返回 policy_violation", data.get("code") == "policy_violation", data)

        # grant_quota
        out = plugin._handle_grant_quota({"minutes": 30, "reason": "完成今日学习"})
        data = parse(out)
        check("grant_quota 返回剩余额度", data.get("granted_minutes") == 30 and data.get("quota", {}).get("remaining_minutes") == 175, data)
        req = last_request()
        check("grant_quota 带 X-Actor/idempotency", req.get("headers", {}).get("x-actor") == "hermes" and bool(req.get("headers", {}).get("idempotency-key")), req.get("headers"))

        # get_stats
        out = plugin._handle_get_stats({"range": "7d"})
        data = parse(out)
        check("get_stats 返回统计字段", data.get("study_minutes") == 420 and data.get("tasks_done") == 12, data)
        check("get_stats 传 range=7d", last_request().get("query", {}).get("range") == ["7d"], last_request().get("query"))

        # list_pending_requests
        out = plugin._handle_list_pending_requests({})
        data = parse(out)
        check("list_pending_requests 返回申请", data.get("count") == 1 and data.get("requests", [{}])[0].get("id") == "req-1", data)

        # decide_request
        out = plugin._handle_decide_request({"request_id": "req-1", "approve": True, "minutes": 20, "note": "学习达标，给 20 分钟"})
        data = parse(out)
        check("decide_request 批准返回状态", data.get("request", {}).get("status") == "approved" and data.get("quota", {}).get("remaining_minutes") == 175, data)
        req = last_request()
        check("decide_request 请求体 approve/minutes/note", req.get("body") == {"approve": True, "minutes": 20, "note": "学习达标，给 20 分钟"}, req.get("body"))
        check("decide_request 缺 approve 拒绝", "invalid_argument" in plugin._handle_decide_request({"request_id": "req-1"}), plugin._handle_decide_request({"request_id": "req-1"}))

        # write_report
        out = plugin._handle_write_report({"title": "今日早报", "content": "昨天完成 3 个任务。", "kind": "morning"})
        data = parse(out)
        check("write_report 返回报告", data.get("kind") == "morning" and data.get("id") == "report-1", data)
        req = last_request()
        check("write_report POST /reports 体字段", req.get("method") == "POST" and (req.get("body") or {}).get("kind") == "morning" and (req.get("body") or {}).get("title") == "今日早报", req.get("body"))

        # Hermes 工具执行器把模型参数作为 kwargs 传入（真实环境曾导致 task_id 报错）
        panel_tool = next(tool for tool in ctx.tools if tool["name"] == "get_panel")
        out = panel_tool["handler"](date="2026-11-02", task_id="ctx-task-1")
        data = parse(out)
        check("Hermes kwargs 调用兼容", data.get("quota", {}).get("remaining_minutes") == 175, data)

        # 网络错误
        os.environ["KUANGREN_API_URL"] = "http://127.0.0.1:1/kuangren-api"
        out = plugin._handle_get_panel({})
        data = parse(out)
        check("连不上后端返回 network_error", data.get("code") == "network_error", data)
    finally:
        server.shutdown()
        server.server_close()
        os.environ["KUANGREN_API_URL"] = "http://127.0.0.1:" + str(port) + "/kuangren-api"

    print("")
    print("共 " + str(CHECK_TOTAL) + " 项检查，失败 " + str(CHECK_FAILED) + " 项")
    if CHECK_FAILED:
        print("失败明细：")
        for item in FAILURES:
            print("  - " + item)
        return 1
    print("ALL PLUGIN TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
