"""统计、热力图、报告、成就。"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import Report
from ..responses import envelope
from ..schemas import ReportCreate, report_dict
from ..services import analytics, audit, rules
from ..services.achievements import evaluate, list_achievements
from .deps import actor, check_int

router = APIRouter(tags=["stats"])

REPORT_KINDS = ("morning", "evening", "system")


@router.get("/stats/summary")
def stats_summary(
    range: str | None = Query(default="7d"),
    db: Session = Depends(get_db),
) -> dict:
    profile = rules.get_profile(db)
    return envelope(analytics.stats_summary(db, range, profile))


@router.get("/stats/heatmap")
def stats_heatmap(
    days: int | None = Query(default=90),
    db: Session = Depends(get_db),
) -> dict:
    window = check_int(days, 1, 365, field="days")
    return envelope(analytics.heatmap(db, window))


@router.get("/reports")
def list_reports(
    limit: int | None = Query(default=20),
    db: Session = Depends(get_db),
) -> dict:
    max_rows = check_int(limit, 1, 200, field="limit")
    rows = db.scalars(select(Report).order_by(Report.created_at.desc()).limit(max_rows)).all()
    return envelope([report_dict(row) for row in rows])


@router.post("/reports")
def create_report(
    body: ReportCreate,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    title = (body.title or "").strip()
    if not title or len(title) > 200:
        raise ApiError("invalid_argument", "title 长度应为 1–200 个字符")
    content = (body.content or "").strip()
    if not content:
        raise ApiError("invalid_argument", "content 不能为空")
    kind = (body.kind or "system").strip() or "system"
    if kind not in REPORT_KINDS:
        raise ApiError("invalid_argument", f"kind 应为 {'/'.join(REPORT_KINDS)}")
    row = Report(
        id=rules.new_id(),
        title=title,
        content=content,
        kind=kind,
        created_at=timeutil.now_iso(),
    )
    db.add(row)
    audit.log(
        db,
        "report.create",
        actor=act,
        target_type="report",
        target_id=row.id,
        detail={"title": title, "kind": kind},
    )
    rules.add_notification(db, title, content[:100], kind="report")
    db.commit()
    return envelope(report_dict(row))


@router.get("/achievements")
def achievements(db: Session = Depends(get_db)) -> dict:
    # 兜底评估：日记/体重等操作本身不触发成就检查，读取时补一次（幂等，只在新解锁时写库）
    profile = rules.get_profile(db)
    if evaluate(db, profile):
        db.commit()
    return envelope(list_achievements(db))
