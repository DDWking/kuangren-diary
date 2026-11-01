"""统计聚合：体重变化、周统计、范围汇总、热力图。"""

from __future__ import annotations

from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..models import DailyStat, Task, WeightEntry

MAX_RANGE_DAYS = 366


def _daily_rows(db: Session, start: str, end: str) -> dict[str, DailyStat]:
    rows = db.scalars(
        select(DailyStat).where(DailyStat.date >= start, DailyStat.date <= end)
    ).all()
    return {row.date: row for row in rows}


def _stat(row: DailyStat | None, field: str) -> int:
    if row is None:
        return 0
    return int(getattr(row, field) or 0)


def daily_series(db: Session, start: str, end: str) -> list[dict]:
    """返回 [start, end]（含端点）每天的学习分钟/完成任务/娱乐使用。"""
    rows = _daily_rows(db, start, end)
    start_date = timeutil.parse_date(start)
    end_date = timeutil.parse_date(end)
    result: list[dict] = []
    for current in timeutil.date_range(start_date, end_date):
        key = timeutil.date_str(current)
        row = rows.get(key)
        result.append(
            {
                "date": key,
                "study_minutes": _stat(row, "study_minutes"),
                "tasks_done": _stat(row, "tasks_done"),
                "quota_used": _stat(row, "quota_used"),
            }
        )
    return result


def weight_change(db: Session, until: str | None = None, days: int = 7) -> float | None:
    """最近一条体重 - 7 天前最近一条体重（无基准则为 None）。"""
    till = until or timeutil.today_str()
    latest = db.scalars(
        select(WeightEntry).where(WeightEntry.date <= till).order_by(WeightEntry.date.desc()).limit(1)
    ).first()
    if latest is None:
        return None
    baseline_date = timeutil.date_str(timeutil.parse_date(till) - timedelta(days=days))
    baseline = db.scalars(
        select(WeightEntry)
        .where(WeightEntry.date <= baseline_date)
        .order_by(WeightEntry.date.desc())
        .limit(1)
    ).first()
    if baseline is None:
        return None
    return round(float(latest.weight_kg) - float(baseline.weight_kg), 2)


def weight_summary(db: Session, days: int = 30, until: str | None = None) -> dict:
    till = until or timeutil.today_str()
    start = timeutil.date_str(timeutil.parse_date(till) - timedelta(days=max(0, days - 1)))
    entries = db.scalars(
        select(WeightEntry)
        .where(WeightEntry.date >= start, WeightEntry.date <= till)
        .order_by(WeightEntry.date)
    ).all()
    latest = db.scalars(select(WeightEntry).order_by(WeightEntry.date.desc()).limit(1)).first()
    from ..schemas import weight_dict

    return {
        "entries": [weight_dict(row) for row in entries],
        "latest": weight_dict(latest) if latest else None,
        "change_7d": weight_change(db, till),
    }


def week_stats(db: Session, day: str) -> dict:
    end_date = timeutil.parse_date(day)
    start = timeutil.date_str(end_date - timedelta(days=6))
    rows = _daily_rows(db, start, day)
    return {
        "week_study_minutes": sum(_stat(row, "study_minutes") for row in rows.values()),
        "week_tasks_done": sum(_stat(row, "tasks_done") for row in rows.values()),
    }


def parse_range(value: str | None) -> int:
    """解析 7d / 30d / 90d，返回天数（默认 7）。"""
    from ..errors import ApiError

    if not value:
        return 7
    text = str(value).strip().lower()
    if text.endswith("d"):
        text = text[:-1]
    try:
        days = int(text)
    except ValueError as exc:
        raise ApiError("invalid_argument", "range 格式应为 7d / 30d / 90d") from exc
    if days < 1 or days > MAX_RANGE_DAYS:
        raise ApiError("invalid_argument", f"range 天数范围应为 1–{MAX_RANGE_DAYS}")
    return days


def stats_summary(db: Session, range_text: str | None, profile) -> dict:
    days = parse_range(range_text)
    end = timeutil.today()
    start = end - timedelta(days=days - 1)
    start_key, end_key = timeutil.date_str(start), timeutil.date_str(end)
    daily = daily_series(db, start_key, end_key)

    study_minutes = sum(item["study_minutes"] for item in daily)
    tasks_done = sum(item["tasks_done"] for item in daily)
    quota_used = sum(item["quota_used"] for item in daily)

    # 完成率：窗口内创建且未删除的任务中已完成的比例
    created = db.scalars(
        select(Task).where(
            Task.deleted_at.is_(None),
            Task.created_at >= start_key,
            Task.created_at <= end_key + "T23:59:59+08:00",
        )
    ).all()
    total_created = len(created)
    done_created = sum(1 for task in created if task.status == "done")
    completion_rate = round(done_created / total_created, 2) if total_created else 0.0

    weights = db.scalars(
        select(WeightEntry)
        .where(WeightEntry.date >= start_key, WeightEntry.date <= end_key)
        .order_by(WeightEntry.date)
    ).all()
    weight_start = float(weights[0].weight_kg) if weights else None
    weight_end = float(weights[-1].weight_kg) if weights else None
    weight_change = (
        round(weight_end - weight_start, 2)
        if weight_start is not None and weight_end is not None
        else None
    )

    from ..schemas import profile_dict

    return {
        "range": f"{days}d",
        "study_minutes": study_minutes,
        "tasks_done": tasks_done,
        "completion_rate": completion_rate,
        "quota_used_minutes": quota_used,
        "weight_start": weight_start,
        "weight_end": weight_end,
        "weight_change": weight_change,
        "streak": int(profile.streak or 0),
        "daily": daily,
        "attributes": profile_dict(profile)["attributes"],
        "heatmap": [{"date": item["date"], "value": item["study_minutes"]} for item in daily],
    }


def heatmap(db: Session, days: int = 90) -> list[dict]:
    end = timeutil.today()
    start = end - timedelta(days=max(0, days - 1))
    series = daily_series(db, timeutil.date_str(start), timeutil.date_str(end))
    return [{"date": item["date"], "value": item["study_minutes"]} for item in series]
