# -*- coding: utf-8 -*-
"""狂人日记 Hermes 插件。

注册 12 个工具（toolset = kuangren），全部通过 docs/api.md 的 HTTP 接口
读写后端。只依赖 Python 标准库（urllib/json），不引入第三方包。

环境变量：
  KUANGREN_API_URL  后端基址（默认 http://159.75.236.90/kuangren-api）
  KUANGREN_TOKEN    Bearer Token（必需；也可用 KUANGREN_HERMES_TOKEN 兜底）
  KUANGREN_DEVICE_ID 可选，默认不发 X-Device-Id
  KUANGREN_TIMEOUT  可选，默认 15 秒

安装：把本目录复制到 ~/.hermes/plugins/kuangren-diary/，并在
~/.hermes/config.yaml 的 plugins.enabled 里加入 kuangren-diary（等价于
执行 hermes plugins enable kuangren-diary），然后重启 gateway。
详见 hermes/install.md。
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from tools.registry import tool_error, tool_result

VERSION = "1.0.0"
DEFAULT_API_URL = "http://159.75.236.90/kuangren-api"
TOOLSET = "kuangren"

try:  # Python 3.9+ 自带 zoneinfo；Windows 上若缺 tzdata 则退回固定 +08:00
    from zoneinfo import ZoneInfo

    _TZ = ZoneInfo("Asia/Shanghai")
except Exception:  # pragma: no cover - 兜底
    _TZ = timezone(timedelta(hours=8))


class KuangrenError(Exception):
    """调用后端失败时抛出，携带 docs/api.md 里的 error code。"""

    def __init__(self, code: str, message: str, status: int = 0) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------


def _env(name: str, default: Optional[str] = None) -> Optional[str]:
    value = os.environ.get(name)
    if value is None or not str(value).strip():
        return default
    return str(value).strip()


def _today() -> str:
    """Asia/Shanghai 的今天（YYYY-MM-DD）。"""
    return datetime.now(_TZ).date().isoformat()


def _api_root() -> str:
    base = _env("KUANGREN_API_URL", DEFAULT_API_URL) or DEFAULT_API_URL
    base = base.strip().rstrip("/")
    if base.endswith("/api/v1"):
        return base
    return base + "/api/v1"


def _token() -> Optional[str]:
    return _env("KUANGREN_TOKEN") or _env("KUANGREN_HERMES_TOKEN")


def _check_available() -> bool:
    """没配置 token 时把工具藏起来，避免模型调用一堆必然失败的接口。"""
    return bool(_token())


def _headers(actor: str = "hermes", idempotency_key: Optional[str] = None) -> Dict[str, str]:
    headers = {
        "Accept": "application/json",
        "User-Agent": "KuangrenHermes/" + VERSION,
        "X-Actor": actor,
    }
    token = _token()
    if token:
        headers["Authorization"] = "Bearer " + token
    device_id = _env("KUANGREN_DEVICE_ID")
    if device_id:
        headers["X-Device-Id"] = device_id
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    return headers


def _request(
    method: str,
    path: str,
    body: Optional[Dict[str, Any]] = None,
    query: Optional[Dict[str, Any]] = None,
    idempotency_key: Optional[str] = None,
    actor: str = "hermes",
    timeout: Optional[float] = None,
) -> Any:
    """调用后端，返回 data 字段；失败抛 KuangrenError。"""
    root = _api_root()
    url = root + path
    if query:
        clean = {k: v for k, v in query.items() if v is not None and v != ""}
        if clean:
            url = url + "?" + urllib.parse.urlencode(clean)

    data = None
    headers = _headers(actor=actor, idempotency_key=idempotency_key)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    if timeout is None:
        try:
            timeout = float(_env("KUANGREN_TIMEOUT", "15") or 15)
        except ValueError:
            timeout = 15.0
        timeout = max(3.0, min(timeout, 60.0))

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            status = int(getattr(response, "status", 200) or 200)
    except urllib.error.HTTPError as exc:
        raw = ""
        try:
            raw = exc.read().decode("utf-8", "replace")
        except Exception:
            raw = ""
        code, message = "http_error", ("HTTP %s" % getattr(exc, "code", "?"))
        try:
            payload = json.loads(raw) if raw.strip() else {}
            err = payload.get("error") or {}
            code = str(err.get("code") or code)
            message = str(err.get("message") or message)
        except Exception:
            if raw.strip():
                message = raw.strip()[:300]
        raise KuangrenError(code, message, status=int(getattr(exc, "code", 0) or 0))
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", exc)
        raise KuangrenError("network_error", "无法连接后端 " + root + "（" + str(reason) + "）")
    except OSError as exc:
        raise KuangrenError("network_error", "网络错误：" + str(exc))

    payload: Any = None
    if raw.strip():
        try:
            payload = json.loads(raw)
        except ValueError:
            raise KuangrenError("internal_error", "后端返回的不是合法 JSON：" + raw.strip()[:200])
    if not isinstance(payload, dict):
        raise KuangrenError("internal_error", "后端响应格式不符合契约（缺少 ok/data 外层）")
    if not payload.get("ok"):
        err = payload.get("error") or {}
        raise KuangrenError(
            str(err.get("code") or "internal_error"),
            str(err.get("message") or "后端返回失败"),
            status=status,
        )
    return payload.get("data")


def _wrap(handler):
    """把异常统一转成工具错误 JSON；工具 handler 一律返回字符串。"""

    def wrapper(args: Optional[Dict[str, Any]] = None, **kwargs: Any) -> str:
        # Hermes 的工具执行器可能把调用参数作为 kwargs 传入（例如 task_id），
        # handler 统一只接收一个 args 字典，因此这里合并而不是二次展开。
        merged: Dict[str, Any] = dict(args or {})
        merged.update(kwargs)
        try:
            return handler(merged)
        except KuangrenError as exc:
            extra: Dict[str, Any] = {"code": exc.code}
            if exc.status:
                extra["status"] = exc.status
            return tool_error(exc.message, **extra)
        except ValueError as exc:
            return tool_error(str(exc), code="invalid_argument")
        except Exception as exc:  # pragma: no cover - 防御
            return tool_error("插件内部错误：" + str(exc), code="plugin_error")

    wrapper.__name__ = getattr(handler, "__name__", "tool_handler")
    wrapper.__doc__ = getattr(handler, "__doc__", None)
    return wrapper


# ---------------------------------------------------------------------------
# 参数校验小工具
# ---------------------------------------------------------------------------


def _opt_str(args: Dict[str, Any], key: str, default: Optional[str] = None) -> Optional[str]:
    value = args.get(key)
    if value is None:
        return default
    text = str(value).strip()
    return text if text else default


def _req_str(args: Dict[str, Any], key: str, label: str) -> str:
    value = _opt_str(args, key)
    if not value:
        raise ValueError("缺少必填参数 " + key + "（" + label + "）")
    return value


def _opt_int(args: Dict[str, Any], key: str, default: Optional[int] = None) -> Optional[int]:
    value = args.get(key)
    if value is None or value == "":
        return default
    try:
        return int(float(value))
    except (TypeError, ValueError):
        raise ValueError("参数 " + key + " 必须是整数，收到：" + repr(value))


def _req_int(args: Dict[str, Any], key: str, label: str) -> int:
    value = _opt_int(args, key)
    if value is None:
        raise ValueError("缺少必填参数 " + key + "（" + label + "）")
    return value


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    text = str(value or "").strip().lower()
    return text in ("1", "true", "yes", "y", "on", "approve", "approved", "同意", "批准")


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def _str_list(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    if isinstance(value, (list, tuple, set)):
        return [str(item).strip() for item in value if str(item).strip()]
    raise ValueError("参数必须是字符串列表")


def _task_brief(task: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": task.get("id"),
        "title": task.get("title"),
        "category": task.get("category"),
        "difficulty": task.get("difficulty"),
        "attribute": task.get("attribute"),
        "status": task.get("status"),
        "due_date": task.get("due_date"),
        "estimated_minutes": task.get("estimated_minutes"),
    }


def _quota_brief(quota: Dict[str, Any]) -> Dict[str, Any]:
    policy = quota.get("policy") or {}
    return {
        "date": quota.get("date"),
        "base_minutes": quota.get("base_minutes"),
        "bonus_minutes": quota.get("bonus_minutes"),
        "used_minutes": quota.get("used_minutes"),
        "remaining_minutes": quota.get("remaining_minutes"),
        "locked": quota.get("locked"),
        "lock_reason": quota.get("lock_reason"),
        "lock_until": quota.get("lock_until"),
        "violations_today": quota.get("violations_today"),
        "forced_tighten": quota.get("forced_tighten"),
        "tighten_reason": quota.get("tighten_reason"),
        "curfew": [policy.get("curfew_start"), policy.get("curfew_end")],
    }


# ---------------------------------------------------------------------------
# 工具实现
# ---------------------------------------------------------------------------

_TIME_RE = ("%02d:%02d")
_ACTIVE_TIME_RE = None


def _norm_clock(value: str, label: str) -> str:
    """把 1:00 / 01:00 归一成 01:00，并校验 24 小时制。"""
    text = str(value).strip()
    parts = text.split(":")
    if len(parts) != 2:
        raise ValueError(label + " 必须是 HH:MM 格式，收到：" + text)
    try:
        hour = int(parts[0])
        minute = int(parts[1])
    except ValueError:
        raise ValueError(label + " 必须是 HH:MM 格式，收到：" + text)
    if hour < 0 or hour > 23 or minute < 0 or minute > 59:
        raise ValueError(label + " 超出 00:00-23:59，收到：" + text)
    return _TIME_RE % (hour, minute)


def _trim_stats(data: Dict[str, Any]) -> Dict[str, Any]:
    """控制返回体积：heatmap 太大时只留最近 31 天。"""
    result = dict(data)
    heatmap = result.get("heatmap")
    if isinstance(heatmap, list) and len(heatmap) > 31:
        result["heatmap"] = heatmap[-31:]
        result["heatmap_note"] = "已省略较早的热力图数据，完整数据请用 App 或 GET /stats/heatmap"
    daily = result.get("daily")
    if isinstance(daily, list) and len(daily) > 31:
        result["daily"] = daily[-31:]
    return result


@_wrap
def _handle_get_panel(args: Dict[str, Any]) -> str:
    date = _opt_str(args, "date")
    data = _request("GET", "/panel", query={"date": date}) or {}
    profile = data.get("profile") or {}
    tasks = data.get("tasks") or {}
    diary = data.get("diary") or None
    weight = data.get("weight") or {}
    notifications = data.get("notifications") or []
    result = {
        "date": data.get("date") or date or _today(),
        "profile": {
            "name": profile.get("name"),
            "title": profile.get("title"),
            "level": profile.get("level"),
            "exp": profile.get("exp"),
            "exp_next": profile.get("exp_next"),
            "coins": profile.get("coins"),
            "streak": profile.get("streak"),
            "exam_days": profile.get("exam_days"),
            "daily_study_target_minutes": profile.get("daily_study_target_minutes"),
        },
        "quota": _quota_brief(data.get("quota") or {}),
        "tasks": {
            "today": [_task_brief(t) for t in (tasks.get("today") or [])],
            "overdue": [_task_brief(t) for t in (tasks.get("overdue") or [])],
        },
        "diary": None
        if not diary
        else {
            "date": diary.get("date"),
            "mood": diary.get("mood"),
            "content": (diary.get("content") or "")[:300],
            "ai_comment": diary.get("ai_comment"),
        },
        "weight": {
            "latest": weight.get("latest"),
            "change_7d": weight.get("change_7d"),
        },
        "stats": data.get("stats"),
        "exam": data.get("exam"),
        "notifications": notifications[:5],
    }
    return tool_result(result)


@_wrap
def _handle_list_tasks(args: Dict[str, Any]) -> str:
    limit = _opt_int(args, "limit", 50) or 50
    limit = _clamp(limit, 1, 100)
    query = {
        "date": _opt_str(args, "date"),
        "from": _opt_str(args, "from"),
        "to": _opt_str(args, "to"),
        "status": _opt_str(args, "status"),
        "category": _opt_str(args, "category"),
        "limit": limit,
    }
    tasks = _request("GET", "/tasks", query=query) or []
    if not isinstance(tasks, list):
        tasks = []
    return tool_result({"count": len(tasks), "tasks": [_task_brief(t) for t in tasks]})


@_wrap
def _handle_create_task(args: Dict[str, Any]) -> str:
    title = _req_str(args, "title", "任务标题")
    category = _opt_str(args, "category", "study") or "study"
    if category not in ("study", "body", "life", "mind", "other"):
        raise ValueError("category 只能是 study/body/life/mind/other，收到：" + category)
    default_attribute = {
        "study": "intelligence",
        "body": "stamina",
        "life": "order",
        "mind": "willpower",
        "other": "order",
    }[category]
    attribute = _opt_str(args, "attribute", default_attribute) or default_attribute
    if attribute not in ("intelligence", "stamina", "willpower", "focus", "order"):
        raise ValueError("attribute 只能是 intelligence/stamina/willpower/focus/order，收到：" + attribute)
    difficulty = _opt_int(args, "difficulty", 3) or 3
    if difficulty < 1 or difficulty > 5:
        raise ValueError("difficulty 必须在 1-5 之间，收到：" + str(difficulty))
    estimated = _opt_int(args, "estimated_minutes", 45)
    if estimated is not None and (estimated < 0 or estimated > 480):
        raise ValueError("estimated_minutes 必须在 0-480 之间，收到：" + str(estimated))
    repeat_rule = _opt_str(args, "repeat_rule", "none") or "none"
    if repeat_rule not in ("none", "daily", "weekly"):
        raise ValueError("repeat_rule 只能是 none/daily/weekly，收到：" + repeat_rule)
    body = {
        "title": title,
        "description": _opt_str(args, "description"),
        "category": category,
        "difficulty": difficulty,
        "attribute": attribute,
        "due_date": _opt_str(args, "due_date", _today()) or _today(),
        "estimated_minutes": estimated if estimated is not None else 45,
        "repeat_rule": repeat_rule,
        "created_by": _opt_str(args, "created_by", "hermes") or "hermes",
    }
    task = _request("POST", "/tasks", body=body) or {}
    brief = _task_brief(task)
    brief.update(
        {
            "description": task.get("description"),
            "exp_reward": task.get("exp_reward"),
            "coin_reward": task.get("coin_reward"),
            "repeat_rule": task.get("repeat_rule"),
            "created_by": task.get("created_by"),
        }
    )
    return tool_result({"task": brief})


@_wrap
def _handle_complete_task(args: Dict[str, Any]) -> str:
    task_id = _req_str(args, "task_id", "任务 ID")
    body: Dict[str, Any] = {}
    note = _opt_str(args, "note")
    if note:
        body["note"] = note
    actual = _opt_int(args, "actual_minutes")
    if actual is not None:
        if actual < 0 or actual > 1440:
            raise ValueError("actual_minutes 必须在 0-1440 之间")
        body["actual_minutes"] = actual
    data = _request(
        "POST",
        "/tasks/" + urllib.parse.quote(task_id) + "/complete",
        body=body,
        idempotency_key=str(uuid.uuid4()),
    ) or {}
    task = data.get("task") or {}
    profile = data.get("profile") or {}
    quota = data.get("quota") or {}
    achievements = data.get("achievements_unlocked") or []
    return tool_result(
        {
            "task": _task_brief(task),
            "rewards": data.get("rewards"),
            "profile": {
                "level": profile.get("level"),
                "exp": profile.get("exp"),
                "exp_next": profile.get("exp_next"),
                "coins": profile.get("coins"),
                "streak": profile.get("streak"),
                "total_study_minutes": profile.get("total_study_minutes"),
                "total_tasks_done": profile.get("total_tasks_done"),
            },
            "quota": _quota_brief(quota),
            "achievements_unlocked": [a.get("title") for a in achievements if isinstance(a, dict)],
        }
    )


@_wrap
def _handle_log_weight(args: Dict[str, Any]) -> str:
    date = _opt_str(args, "date", _today()) or _today()
    raw_weight = args.get("weight_kg")
    if raw_weight is None or raw_weight == "":
        raise ValueError("缺少必填参数 weight_kg（体重，公斤）")
    try:
        weight = round(float(raw_weight), 1)
    except (TypeError, ValueError):
        raise ValueError("weight_kg 必须是数字，收到：" + repr(raw_weight))
    if weight < 20 or weight > 300:
        raise ValueError("weight_kg 看起来不合理（应在 20-300 公斤之间），收到：" + str(weight))
    body: Dict[str, Any] = {"weight_kg": weight, "source": _opt_str(args, "source", "hermes") or "hermes"}
    body_fat = args.get("body_fat")
    if body_fat is not None and body_fat != "":
        try:
            body["body_fat"] = round(float(body_fat), 1)
        except (TypeError, ValueError):
            raise ValueError("body_fat 必须是数字")
    waist = args.get("waist_cm")
    if waist is not None and waist != "":
        try:
            body["waist_cm"] = round(float(waist), 1)
        except (TypeError, ValueError):
            raise ValueError("waist_cm 必须是数字")
    note = _opt_str(args, "note")
    if note:
        body["note"] = note
    entry = _request("PUT", "/weight/" + urllib.parse.quote(date), body=body) or {}
    return tool_result(
        {
            "date": entry.get("date", date),
            "weight_kg": entry.get("weight_kg"),
            "body_fat": entry.get("body_fat"),
            "waist_cm": entry.get("waist_cm"),
            "note": entry.get("note"),
            "source": entry.get("source"),
        }
    )


@_wrap
def _handle_write_diary_comment(args: Dict[str, Any]) -> str:
    date = _opt_str(args, "date", _today()) or _today()
    comment = _req_str(args, "ai_comment", "AI 点评内容")
    entry = _request(
        "POST",
        "/diary/" + urllib.parse.quote(date) + "/comment",
        body={"ai_comment": comment},
    ) or {}
    return tool_result({"date": entry.get("date", date), "ai_comment": entry.get("ai_comment", comment)})


@_wrap
def _handle_set_daily_policy(args: Dict[str, Any]) -> str:
    body: Dict[str, Any] = {}
    date = _opt_str(args, "date")
    if date:
        body["date"] = date
    if args.get("daily_base_minutes") is not None:
        minutes = _req_int(args, "daily_base_minutes", "每日基础额度")
        if minutes < 0 or minutes > 480:
            raise ValueError("daily_base_minutes 允许 0-480（后端硬限制），收到：" + str(minutes))
        body["daily_base_minutes"] = minutes
    if args.get("session_max_minutes") is not None:
        session = _req_int(args, "session_max_minutes", "单次最长分钟")
        if session < 20 or session > 180:
            raise ValueError("session_max_minutes 允许 20-180（后端硬限制），收到：" + str(session))
        body["session_max_minutes"] = session
    for key, label in (("curfew_start", "宵禁开始"), ("curfew_end", "宵禁结束")):
        value = _opt_str(args, key)
        if value:
            body[key] = _norm_clock(value, label)
    if args.get("blacklist") is not None:
        body["blacklist"] = _str_list(args.get("blacklist"))
    notes = _opt_str(args, "notes")
    if notes:
        body["notes"] = notes
    reason = _opt_str(args, "reason")
    if reason:
        body["reason"] = reason
    if not body or (len(body) == 1 and "date" in body):
        raise ValueError("至少要提供一项策略变更（daily_base_minutes/curfew_start/curfew_end/session_max_minutes/blacklist/notes）")
    policy = _request("PUT", "/policy", body=body) or {}
    return tool_result(
        {
            "date": policy.get("date"),
            "daily_base_minutes": policy.get("daily_base_minutes"),
            "curfew_start": policy.get("curfew_start"),
            "curfew_end": policy.get("curfew_end"),
            "session_max_minutes": policy.get("session_max_minutes"),
            "blacklist": policy.get("blacklist"),
            "notes": policy.get("notes"),
            "source": policy.get("source"),
        }
    )


@_wrap
def _handle_grant_quota(args: Dict[str, Any]) -> str:
    minutes = _req_int(args, "minutes", "奖励分钟数")
    if minutes < 1 or minutes > 480:
        raise ValueError("minutes 必须在 1-480 之间，收到：" + str(minutes))
    reason = _req_str(args, "reason", "奖励原因")
    body: Dict[str, Any] = {"minutes": minutes, "reason": reason, "actor": "hermes"}
    expires_at = _opt_str(args, "expires_at")
    if expires_at:
        body["expires_at"] = expires_at
    quota = _request("POST", "/quota/grant", body=body, idempotency_key=str(uuid.uuid4())) or {}
    return tool_result({"granted_minutes": minutes, "reason": reason, "quota": _quota_brief(quota)})


@_wrap
def _handle_get_stats(args: Dict[str, Any]) -> str:
    range_value = _opt_str(args, "range", "7d") or "7d"
    data = _request("GET", "/stats/summary", query={"range": range_value}) or {}
    if not isinstance(data, dict):
        data = {"raw": data}
    return tool_result(_trim_stats(data))


@_wrap
def _handle_list_pending_requests(args: Dict[str, Any]) -> str:
    limit = _clamp(_opt_int(args, "limit", 20) or 20, 1, 50)
    requests = _request("GET", "/quota/requests", query={"status": "pending", "limit": limit}) or []
    if not isinstance(requests, list):
        requests = []
    rows = []
    for item in requests:
        if not isinstance(item, dict):
            continue
        rows.append(
            {
                "id": item.get("id"),
                "date": item.get("date"),
                "minutes": item.get("minutes"),
                "reason": item.get("reason"),
                "device_id": item.get("device_id"),
                "status": item.get("status"),
                "created_at": item.get("created_at"),
            }
        )
    return tool_result({"count": len(rows), "requests": rows})


@_wrap
def _handle_decide_request(args: Dict[str, Any]) -> str:
    request_id = _req_str(args, "request_id", "申请 ID")
    if "approve" not in args or args.get("approve") is None:
        raise ValueError("缺少必填参数 approve（true=批准，false=拒绝）")
    approve = _as_bool(args.get("approve"))
    body: Dict[str, Any] = {"approve": approve}
    minutes = _opt_int(args, "minutes")
    if minutes is not None:
        if minutes < 0 or minutes > 480:
            raise ValueError("minutes 必须在 0-480 之间")
        body["minutes"] = minutes
    note = _opt_str(args, "note")
    if note:
        body["note"] = note
    data = _request(
        "POST",
        "/quota/requests/" + urllib.parse.quote(request_id) + "/decide",
        body=body,
        idempotency_key=str(uuid.uuid4()),
    ) or {}
    request = data.get("request") or {}
    return tool_result(
        {
            "request": {
                "id": request.get("id", request_id),
                "status": request.get("status"),
                "minutes": request.get("minutes"),
                "decision_note": request.get("decision_note"),
                "decided_by": request.get("decided_by"),
            },
            "quota": _quota_brief(data.get("quota") or {}),
        }
    )


@_wrap
def _handle_write_report(args: Dict[str, Any]) -> str:
    title = _req_str(args, "title", "报告标题")
    content = _req_str(args, "content", "报告正文")
    kind = _opt_str(args, "kind", "system") or "system"
    if kind not in ("morning", "evening", "system"):
        raise ValueError("kind 只能是 morning/evening/system，收到：" + kind)
    report = _request("POST", "/reports", body={"title": title, "content": content, "kind": kind}) or {}
    return tool_result(
        {
            "id": report.get("id"),
            "title": report.get("title", title),
            "kind": report.get("kind", kind),
            "created_at": report.get("created_at"),
        }
    )


# ---------------------------------------------------------------------------
# 工具 schema（中文描述，给呆看）
# ---------------------------------------------------------------------------

_DATE_DESC = "日期 YYYY-MM-DD（Asia/Shanghai），不传默认今天"
_BOTTOM_LINES = (
    "三条硬底线由后端强制、任何工具都改不了：1) 审计与事件不可删除/停用；"
    "2) 03:00-07:00 必须禁止娱乐（curfew_start 不得晚于 03:00，curfew_end 不得早于 07:00）；"
    "3) 连续 3 个自然日零学习后，次日基础额度强制减半（forced_tighten），直到出现学习完成记录。"
)

_SCHEMAS = {
    "get_panel": {
        "name": "get_panel",
        "description": (
            "查看狂人日记首页聚合面板：用户档案、今日额度（quota）、今日/逾期任务、今天的日记、"
            "体重、周统计、考试倒计时、未读通知。额度是动态的：剩余分钟、锁定状态、宵禁、违规次数、"
            "强制收紧都可能每次心跳变化——先查面板再决定要不要奖励/调策略，不要凭记忆直接改。"
            "每天至少查一次；给用户回话前先查。"
        ),
        "parameters": {
            "type": "object",
            "properties": {"date": {"type": "string", "description": _DATE_DESC}},
            "required": [],
        },
    },
    "list_tasks": {
        "name": "list_tasks",
        "description": (
            "按日期/状态/分类列出任务（返回 id 用于 complete_task）。查某天用 date；"
            "查区间用 from+to；只看没做完的传 status=pending。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "date": {"type": "string", "description": "只看某一天（YYYY-MM-DD）"},
                "from": {"type": "string", "description": "区间开始日期（YYYY-MM-DD）"},
                "to": {"type": "string", "description": "区间结束日期（YYYY-MM-DD）"},
                "status": {"type": "string", "enum": ["pending", "done", "skipped"], "description": "任务状态"},
                "category": {"type": "string", "enum": ["study", "body", "life", "mind", "other"], "description": "任务分类"},
                "limit": {"type": "integer", "description": "最多返回多少条，默认 50，最大 100"},
            },
            "required": [],
        },
    },
    "create_task": {
        "name": "create_task",
        "description": (
            "给用户创建任务。学习类任务才计入学习日和总学习分钟；奖励值（经验/金币）由后端计算，不用传。"
            "适合把用户口述的计划落成任务，或早晚报里补第二天的任务。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "任务标题，例如 政治：肖八选择题一节"},
                "description": {"type": "string", "description": "任务备注/细节，可空"},
                "category": {"type": "string", "enum": ["study", "body", "life", "mind", "other"], "description": "分类，默认 study"},
                "difficulty": {"type": "integer", "description": "难度 1-5，默认 3"},
                "attribute": {"type": "string", "enum": ["intelligence", "stamina", "willpower", "focus", "order"], "description": "加哪个属性，默认按分类自动推断"},
                "due_date": {"type": "string", "description": "截止日期 YYYY-MM-DD，默认今天"},
                "estimated_minutes": {"type": "integer", "description": "预计分钟数，默认 45"},
                "repeat_rule": {"type": "string", "enum": ["none", "daily", "weekly"], "description": "重复规则，仅记录不自动生成，默认 none"},
                "created_by": {"type": "string", "description": "创建者，默认 hermes"},
            },
            "required": ["title"],
        },
    },
    "complete_task": {
        "name": "complete_task",
        "description": (
            "把任务标记完成（后端发经验/金币/属性，学习任务累计学习分钟和 streak）。"
            "只有用户明确说完成了才调用；已完成的重复调用不会重复加经验。返回奖励和剩余额度，"
            "可以顺便告诉用户还差多少到下一级。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {"type": "string", "description": "任务 id（先 list_tasks 或 get_panel 查）"},
                "note": {"type": "string", "description": "完成备注，可空"},
                "actual_minutes": {"type": "integer", "description": "实际用时分钟数，可空（学习任务会用它累计学习分钟）"},
            },
            "required": ["task_id"],
        },
    },
    "log_weight": {
        "name": "log_weight",
        "description": "记录某天体重（每天最多一条，重复写会覆盖）。source 默认 hermes。",
        "parameters": {
            "type": "object",
            "properties": {
                "weight_kg": {"type": "number", "description": "体重（公斤），必填"},
                "date": {"type": "string", "description": _DATE_DESC},
                "body_fat": {"type": "number", "description": "体脂率（%），可空"},
                "waist_cm": {"type": "number", "description": "腰围（厘米），可空"},
                "note": {"type": "string", "description": "备注，例如 晨起空腹"},
            },
            "required": ["weight_kg"],
        },
    },
    "write_diary_comment": {
        "name": "write_diary_comment",
        "description": (
            "给某天的日记写 AI 点评（App 只读展示）。用户写了日记后调用；"
            "语气像朋友，先接住情绪再给一条具体建议，不要空泛鸡汤，不要重复日记原文。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "ai_comment": {"type": "string", "description": "点评正文，两三句话，中文"},
                "date": {"type": "string", "description": _DATE_DESC},
            },
            "required": ["ai_comment"],
        },
    },
    "set_daily_policy": {
        "name": "set_daily_policy",
        "description": (
            "设置某天的娱乐策略：基础额度、宵禁、单次时长、黑名单、备注。"
            "额度是动态的，改之前先 get_panel 看当前用量和违规；调松要有理由（用户学习达标/请求），调紧要说明原因。"
            + _BOTTOM_LINES
            + " 插件不能越过底线，越界会被后端以 policy_violation 拒绝。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "date": {"type": "string", "description": "策略生效日期，默认今天"},
                "daily_base_minutes": {"type": "integer", "description": "每日基础娱乐额度 0-480 分钟"},
                "curfew_start": {"type": "string", "description": "宵禁开始 HH:MM，不得晚于 03:00"},
                "curfew_end": {"type": "string", "description": "宵禁结束 HH:MM，不得早于 07:00"},
                "session_max_minutes": {"type": "integer", "description": "单次最长 20-180 分钟"},
                "blacklist": {"type": "array", "items": {"type": "string"}, "description": "要拦截的程序名列表，如 steam.exe"},
                "notes": {"type": "string", "description": "写进策略备注给用户看的话"},
                "reason": {"type": "string", "description": "变更原因（审计用）"},
            },
            "required": [],
        },
    },
    "grant_quota": {
        "name": "grant_quota",
        "description": (
            "给今天额外奖励娱乐额度（bonus_minutes），会记审计并可能立即让 PC 解锁。"
            "先 get_panel：只有用户学习任务确实完成、或用户明确请求且理由合理时才奖励；"
            "额度是动态的，不要连着几次小额度硬凑。"
            + _BOTTOM_LINES
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "minutes": {"type": "integer", "description": "奖励分钟数 1-480"},
                "reason": {"type": "string", "description": "奖励原因，要能写进审计"},
                "expires_at": {"type": "string", "description": "可空，过期时间 ISO-8601（+08:00）"},
            },
            "required": ["minutes", "reason"],
        },
    },
    "get_stats": {
        "name": "get_stats",
        "description": "查看统计汇总：学习分钟、完成任务数、完成率、娱乐用量、体重变化、streak、每日明细、五维属性。写早晚报前先调。",
        "parameters": {
            "type": "object",
            "properties": {
                "range": {"type": "string", "description": "统计区间，默认 7d，也可传 30d/90d 等后端支持的格式"}
            },
            "required": [],
        },
    },
    "list_pending_requests": {
        "name": "list_pending_requests",
        "description": "查看用户从 PC/手机发来的待处理额度申请（申请延时）。用户问“我的申请呢”或早晚报前调用。",
        "parameters": {
            "type": "object",
            "properties": {"limit": {"type": "integer", "description": "最多返回条数，默认 20，最大 50"}},
            "required": [],
        },
    },
    "decide_request": {
        "name": "decide_request",
        "description": (
            "批准或拒绝一条额度申请。approve=true 批准（可给 minutes 指定批准分钟数），false 拒绝。"
            "决策前先 get_panel 看当天学习完成情况和剩余额度；理由要写进 note。"
            + _BOTTOM_LINES
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "request_id": {"type": "string", "description": "申请 id（先 list_pending_requests）"},
                "approve": {"type": "boolean", "description": "true=批准，false=拒绝"},
                "minutes": {"type": "integer", "description": "批准时给多少分钟（可少于申请值），拒绝可不传"},
                "note": {"type": "string", "description": "给用户的决策理由"},
            },
            "required": ["request_id", "approve"],
        },
    },
    "write_report": {
        "name": "write_report",
        "description": (
            "写一份早报/晚报/系统报告，App 会展示。写之前先 get_panel + get_stats，"
            "内容要具体（数字、任务名、剩余额度），不要编造没发生的事。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "标题，例如 今日早报"},
                "content": {"type": "string", "description": "报告正文，中文，可用短句/列表"},
                "kind": {"type": "string", "enum": ["morning", "evening", "system"], "description": "类型：早报/晚报/系统，默认 system"},
            },
            "required": ["title", "content"],
        },
    },
}

_TOOLS = (
    ("get_panel", _SCHEMAS["get_panel"], _handle_get_panel, "📋"),
    ("list_tasks", _SCHEMAS["list_tasks"], _handle_list_tasks, "📝"),
    ("create_task", _SCHEMAS["create_task"], _handle_create_task, "➕"),
    ("complete_task", _SCHEMAS["complete_task"], _handle_complete_task, "✅"),
    ("log_weight", _SCHEMAS["log_weight"], _handle_log_weight, "⚖️"),
    ("write_diary_comment", _SCHEMAS["write_diary_comment"], _handle_write_diary_comment, "💬"),
    ("set_daily_policy", _SCHEMAS["set_daily_policy"], _handle_set_daily_policy, "🎛️"),
    ("grant_quota", _SCHEMAS["grant_quota"], _handle_grant_quota, "🎁"),
    ("get_stats", _SCHEMAS["get_stats"], _handle_get_stats, "📊"),
    ("list_pending_requests", _SCHEMAS["list_pending_requests"], _handle_list_pending_requests, "📥"),
    ("decide_request", _SCHEMAS["decide_request"], _handle_decide_request, "🧾"),
    ("write_report", _SCHEMAS["write_report"], _handle_write_report, "📰"),
)


def register(ctx) -> None:
    """Hermes 插件入口：注册全部工具到 kuangren toolset。"""
    for name, schema, handler, emoji in _TOOLS:
        ctx.register_tool(
            name=name,
            toolset=TOOLSET,
            schema=schema,
            handler=handler,
            check_fn=_check_available,
            emoji=emoji,
        )
