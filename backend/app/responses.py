from __future__ import annotations

from typing import Any

from .timeutil import now_iso


def envelope(data: Any) -> dict:
    """成功响应统一包：{ok, data, server_time}。"""
    return {"ok": True, "data": data, "server_time": now_iso()}


def error_payload(code: str, message: str) -> dict:
    return {
        "ok": False,
        "error": {"code": code, "message": message},
        "server_time": now_iso(),
    }
