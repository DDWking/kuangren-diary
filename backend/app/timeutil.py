from __future__ import annotations

import re
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from .settings import settings

try:  # Windows 上可能没有系统时区库，docker/requirements 里装了 tzdata
    TZ = ZoneInfo(settings.timezone)
except Exception:  # pragma: no cover - 兜底为固定 UTC+8
    TZ = timezone(timedelta(hours=8), "Asia/Shanghai")
DATE_FMT = "%Y-%m-%d"
_HHMM_RE = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")


def now() -> datetime:
    return datetime.now(TZ)


def now_iso() -> str:
    """当前服务器时间的 ISO-8601 字符串（带 +08:00）。"""
    return now().isoformat(timespec="seconds")


def to_iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=TZ)
    return dt.astimezone(TZ).isoformat(timespec="seconds")


def parse_dt(value: object) -> datetime | None:
    """宽松解析 ISO-8601 时间；不带时区的按 Asia/Shanghai 处理。"""
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:  # noqa: TRY003
        raise ValueError("时间格式不正确，应为 ISO-8601（如 2026-11-02T02:30:00+08:00）") from exc
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=TZ)
    return dt.astimezone(TZ)


def today() -> date:
    return now().date()


def today_str() -> str:
    return today().isoformat()


def date_str(value: date) -> str:
    return value.isoformat()


def parse_date(value: object) -> date:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = str(value).strip()
    try:
        return datetime.strptime(text, DATE_FMT).date()
    except ValueError as exc:  # noqa: TRY003
        raise ValueError("日期格式不正确，应为 YYYY-MM-DD") from exc


def end_of_day_iso(day: date) -> str:
    return datetime.combine(day, time(23, 59, 59), tzinfo=TZ).isoformat(timespec="seconds")


def parse_hhmm(value: object) -> time:
    text = str(value).strip()
    match = _HHMM_RE.match(text)
    if not match:
        raise ValueError("时间格式不正确，应为 HH:MM（00:00-23:59）")
    return time(int(match.group(1)), int(match.group(2)))


def minutes_of_day(value: time) -> int:
    return value.hour * 60 + value.minute


def _is_in_window(start: time, end: time, moment: datetime) -> bool:
    start_m = minutes_of_day(start)
    end_m = minutes_of_day(end)
    if start_m == end_m:
        return False
    current_m = minutes_of_day(moment.astimezone(TZ).time())
    if start_m < end_m:
        return start_m <= current_m < end_m
    # 跨午夜，例如 22:00-07:00
    return current_m >= start_m or current_m < end_m


def is_in_window(start: time, end: time, moment: datetime | None = None) -> bool:
    return _is_in_window(start, end, moment or now())


def window_covers(start: time, end: time, cover_start: time, cover_end: time) -> bool:
    """判断 [start,end) 是否完整覆盖 [cover_start,cover_end) 的每一分钟。"""
    cover_begin = minutes_of_day(cover_start)
    cover_finish = minutes_of_day(cover_end)
    for minute in range(cover_begin, cover_finish):
        probe = datetime.combine(now().date(), time(minute // 60, minute % 60), tzinfo=TZ)
        if not _is_in_window(start, end, probe):
            return False
    return True


def add_days(value: date, days: int) -> date:
    return value + timedelta(days=days)


def date_range(start: date, end: date) -> list[date]:
    result: list[date] = []
    cursor = start
    while cursor <= end:
        result.append(cursor)
        cursor = cursor + timedelta(days=1)
    return result
