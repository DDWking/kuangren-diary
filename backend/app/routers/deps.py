"""路由公共依赖与参数校验工具。"""

from __future__ import annotations

import hmac

from fastapi import Header
from sqlalchemy.orm import Session

from .. import timeutil
from ..errors import ApiError
from ..settings import settings

VALID_ACTORS = ("app", "pc", "hermes", "system")


def require_token(authorization: str | None = Header(default=None, alias="Authorization")) -> str:
    """校验 Authorization: Bearer <TOKEN>。除 /health 外所有接口都依赖它。"""
    if not authorization:
        raise ApiError("unauthorized", "缺少 Authorization: Bearer <TOKEN> 请求头", 401)
    parts = authorization.split(" ", 1)
    if len(parts) != 2 or parts[0].strip().lower() != "bearer" or not parts[1].strip():
        raise ApiError("unauthorized", "Authorization 格式应为 Bearer <TOKEN>", 401)
    token = parts[1].strip()
    if not hmac.compare_digest(token, settings.effective_token):
        raise ApiError("unauthorized", "token 不正确", 401)
    return token


def actor(x_actor: str | None = Header(default=None, alias="X-Actor")) -> str:
    value = (x_actor or "").strip().lower()
    return value if value in VALID_ACTORS else "app"


def device_header(x_device_id: str | None = Header(default=None, alias="X-Device-Id")) -> str | None:
    value = (x_device_id or "").strip()
    return value or None


def parse_day(value: object, *, field: str = "date") -> str:
    """解析 YYYY-MM-DD，返回规范字符串；失败抛 400。"""
    try:
        return timeutil.date_str(timeutil.parse_date(value))
    except ValueError as exc:
        raise ApiError("invalid_argument", f"{field} 格式不正确，应为 YYYY-MM-DD") from exc


def optional_day(value: object, *, field: str = "date") -> str | None:
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    return parse_day(value, field=field)


def optional_iso(value: object, *, field: str = "时间") -> str | None:
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    try:
        moment = timeutil.parse_dt(value)
    except ValueError as exc:
        raise ApiError("invalid_argument", f"{field}格式不正确，应为 ISO-8601") from exc
    return timeutil.to_iso(moment) if moment else None


def check_int(value: object, low: int, high: int, *, field: str) -> int:
    try:
        number = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError) as exc:
        raise ApiError("invalid_argument", f"{field} 应为整数") from exc
    if number < low or number > high:
        raise ApiError("invalid_argument", f"{field} 范围应为 {low}–{high}")
    return number


def check_limit(value: object | None, *, default: int, high: int = 500, field: str = "limit") -> int:
    if value is None or value == "":
        return default
    return check_int(value, 1, high, field=field)


def get_or_404(db: Session, model: type, key: str, message: str):
    row = db.get(model, key)
    if row is None:
        raise ApiError("not_found", message, 404)
    return row
