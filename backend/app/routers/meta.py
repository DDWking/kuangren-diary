"""系统信息：Profile、首页聚合、通知、全量导出。"""

from __future__ import annotations

from datetime import timedelta

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import (
    Command,
    Device,
    DiaryEntry,
    Event,
    Grant,
    Notification,
    Policy,
    QuotaDay,
    QuotaRequest,
    Report,
    Task,
    WeightEntry,
)
from ..responses import envelope
from ..schemas import (
    diary_dict,
    device_dict,
    notification_dict,
    profile_dict,
    task_dict,
    weight_dict,
    ProfilePatch,
)
from ..services import analytics, audit, rules, settlement
from ..services import achievements as achievements_service
from ..services.achievements import list_achievements
from .deps import actor, check_int, optional_day, parse_day

router = APIRouter(tags=["meta"])


@router.get("/profile")
def get_profile(db: Session = Depends(get_db)) -> dict:
    return envelope(profile_dict(rules.get_profile(db)))


@router.patch("/profile")
def patch_profile(
    body: ProfilePatch,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    profile = rules.get_profile(db)
    changed: dict = {}
    if "name" in body.model_fields_set and body.name is not None:
        name = body.name.strip()
        if not name or len(name) > 32:
            raise ApiError("invalid_argument", "name 长度应为 1–32 个字符")
        profile.name = name
        changed["name"] = name
    if "title" in body.model_fields_set and body.title is not None:
        title = body.title.strip()
        if not title or len(title) > 32:
            raise ApiError("invalid_argument", "title 长度应为 1–32 个字符")
        profile.title = title
        changed["title"] = title
    if "exam_date" in body.model_fields_set and body.exam_date is not None:
        profile.exam_date = parse_day(body.exam_date, field="exam_date")
        changed["exam_date"] = profile.exam_date
    if "daily_study_target_minutes" in body.model_fields_set and body.daily_study_target_minutes is not None:
        profile.daily_study_target_minutes = check_int(
            body.daily_study_target_minutes, 30, 900, field="daily_study_target_minutes"
        )
        changed["daily_study_target_minutes"] = profile.daily_study_target_minutes

    profile.updated_at = timeutil.now_iso()
    audit.log(db, "profile.update", actor=act, target_type="profile", target_id=profile.id, detail=changed)
    db.commit()
    return envelope(profile_dict(profile))


def _panel_tasks(db: Session, day: str) -> dict:
    today_tasks = db.scalars(
        select(Task)
        .where(Task.deleted_at.is_(None), Task.due_date == day)
        .order_by(Task.created_at)
    ).all()
    overdue = db.scalars(
        select(Task)
        .where(
            Task.deleted_at.is_(None),
            Task.status == "pending",
            Task.due_date != "",
            Task.due_date < day,
        )
        .order_by(Task.due_date, Task.created_at)
    ).all()
    return {
        "today": [task_dict(row) for row in today_tasks],
        "overdue": [task_dict(row) for row in overdue],
    }


@router.get("/panel")
def panel(
    date: str | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    settlement.ensure_day(db)
    day = optional_day(date) or timeutil.today_str()
    profile = rules.get_profile(db)
    quota = rules.build_quota(db, day, profile)

    diary_row = db.get(DiaryEntry, day)
    weight = analytics.weight_summary(db, days=30, until=day)
    week = analytics.week_stats(db, day)
    notifications = db.scalars(
        select(Notification).order_by(Notification.created_at.desc()).limit(5)
    ).all()

    exam_days = 0
    try:
        exam_days = max(0, (timeutil.parse_date(profile.exam_date) - timeutil.parse_date(day)).days)
    except Exception:
        exam_days = 0

    return envelope(
        {
            "date": day,
            "profile": profile_dict(profile),
            "quota": quota,
            "tasks": _panel_tasks(db, day),
            "diary": diary_dict(diary_row) if diary_row else None,
            "weight": {"latest": weight["latest"], "change_7d": weight["change_7d"]},
            "stats": {
                "week_study_minutes": week["week_study_minutes"],
                "week_tasks_done": week["week_tasks_done"],
                "streak": int(profile.streak or 0),
            },
            "notifications": [notification_dict(row) for row in notifications],
            "exam": {"date": profile.exam_date, "days": exam_days},
        }
    )


@router.get("/notifications")
def notifications(
    unread: bool = Query(default=False),
    limit: int = Query(default=50),
    db: Session = Depends(get_db),
) -> dict:
    max_rows = check_int(limit, 1, 200, field="limit")
    stmt = select(Notification).order_by(Notification.created_at.desc()).limit(max_rows)
    if unread:
        stmt = stmt.where(Notification.read.is_(False))
    rows = db.scalars(stmt).all()
    return envelope([notification_dict(row) for row in rows])


@router.post("/notifications/read")
def mark_notifications_read(db: Session = Depends(get_db)) -> dict:
    """App 拉取后标记已读（额外便利接口，不影响契约）。"""
    rows = db.scalars(select(Notification).where(Notification.read.is_(False))).all()
    for row in rows:
        row.read = True
    db.commit()
    return envelope({"updated": len(rows)})


@router.get("/export")
def export_all(db: Session = Depends(get_db)) -> dict:
    """全部数据 JSON（备份用）。"""
    profile = rules.get_profile(db)
    data = {
        "exported_at": timeutil.now_iso(),
        "profile": profile_dict(profile),
        "tasks": [task_dict(row) for row in db.scalars(select(Task)).all()],
        "diary": [diary_dict(row) for row in db.scalars(select(DiaryEntry)).all()],
        "weight": [weight_dict(row) for row in db.scalars(select(WeightEntry)).all()],
        "policies": [
            rules.policy_to_dict(row) for row in db.scalars(select(Policy).order_by(Policy.date)).all()
        ],
        "quota_days": [
            {
                "date": row.date,
                "used_minutes": int(row.used_minutes or 0),
                "locked": bool(row.locked),
                "lock_reason": row.lock_reason,
                "lock_until": row.lock_until,
            }
            for row in db.scalars(select(QuotaDay).order_by(QuotaDay.date)).all()
        ],
        "grants": [
            rules.grant_to_dict(row)
            for row in db.scalars(select(Grant).order_by(Grant.created_at)).all()
        ],
        "quota_requests": [
            {
                "id": row.id,
                "date": row.date,
                "minutes": int(row.minutes),
                "reason": row.reason,
                "device_id": row.device_id,
                "status": row.status,
                "decided_by": row.decided_by,
                "decision_note": row.decision_note,
                "created_at": row.created_at,
                "decided_at": row.decided_at,
            }
            for row in db.scalars(select(QuotaRequest).order_by(QuotaRequest.created_at)).all()
        ],
        "devices": [device_dict(row) for row in db.scalars(select(Device)).all()],
        "events": [
            {
                "id": row.id,
                "device_id": row.device_id,
                "type": row.type,
                "severity": row.severity,
                "payload": row.payload or {},
                "at": row.at,
            }
            for row in db.scalars(select(Event).order_by(Event.at)).all()
        ],
        "commands": [
            {
                "id": row.id,
                "device_id": row.device_id,
                "type": row.type,
                "payload": row.payload or {},
                "status": row.status,
                "created_at": row.created_at,
                "acked_at": row.acked_at,
            }
            for row in db.scalars(select(Command).order_by(Command.created_at)).all()
        ],
        "reports": [
            {
                "id": row.id,
                "title": row.title,
                "content": row.content,
                "kind": row.kind,
                "created_at": row.created_at,
            }
            for row in db.scalars(select(Report).order_by(Report.created_at)).all()
        ],
        "achievements": list_achievements(db),
        "notifications": [
            notification_dict(row)
            for row in db.scalars(select(Notification).order_by(Notification.created_at)).all()
        ],
        "audit_logs": audit.list_logs(db, limit=500),
    }
    return envelope(data)
