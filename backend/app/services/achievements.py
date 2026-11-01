"""成就：只展示，不打断；解锁时写通知并（由调用方）返回。"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import timeutil
from ..models import AchievementUnlock, DiaryEntry, Profile, WeightEntry
from . import rules

DEFINITIONS: list[dict] = [
    {"id": "first_task", "title": "第一次睁眼", "description": "完成第一个任务"},
    {"id": "first_diary", "title": "会回嘴的日记", "description": "写下第一篇日记"},
    {"id": "streak_7", "title": "七日不眠", "description": "连续 7 天有学习完成记录"},
    {"id": "streak_30", "title": "习惯成形", "description": "连续 30 天有学习完成记录"},
    {"id": "tasks_50", "title": "半百", "description": "累计完成 50 个任务"},
    {"id": "tasks_200", "title": "两百次", "description": "累计完成 200 个任务"},
    {"id": "study_100h", "title": "一百小时", "description": "累计学习 100 小时"},
    {"id": "weight_loss_1kg", "title": "轻了一公斤", "description": "体重较首条记录下降至少 1kg"},
    {"id": "level_5", "title": "五级", "description": "等级达到 5 级"},
]

_EVALUATORS = {
    "first_task": lambda ctx: ctx["profile"].total_tasks_done >= 1,
    "first_diary": lambda ctx: ctx["diary_count"] >= 1,
    "streak_7": lambda ctx: ctx["profile"].streak >= 7,
    "streak_30": lambda ctx: ctx["profile"].streak >= 30,
    "tasks_50": lambda ctx: ctx["profile"].total_tasks_done >= 50,
    "tasks_200": lambda ctx: ctx["profile"].total_tasks_done >= 200,
    "study_100h": lambda ctx: ctx["profile"].total_study_minutes >= 100 * 60,
    "weight_loss_1kg": lambda ctx: ctx["weight_loss"] is not None and ctx["weight_loss"] >= 1.0,
    "level_5": lambda ctx: ctx["profile"].level >= 5,
}


def _build_context(db: Session, profile: Profile) -> dict:
    diary_count = int(db.scalar(select(func.count(DiaryEntry.date))) or 0)
    weights = db.scalars(select(WeightEntry).order_by(WeightEntry.date)).all()
    weight_loss = None
    if len(weights) >= 2:
        weight_loss = float(weights[0].weight_kg) - float(weights[-1].weight_kg)
    return {"profile": profile, "diary_count": diary_count, "weight_loss": weight_loss}


def _achievement_dict(definition: dict, unlocked_at: str | None) -> dict:
    return {
        "id": definition["id"],
        "title": definition["title"],
        "description": definition["description"],
        "unlocked": unlocked_at is not None,
        "unlocked_at": unlocked_at,
    }


def _unlocked_map(db: Session) -> dict[str, str]:
    rows = db.scalars(select(AchievementUnlock)).all()
    return {row.id: row.unlocked_at for row in rows}


def list_achievements(db: Session) -> list[dict]:
    unlocked = _unlocked_map(db)
    return [_achievement_dict(item, unlocked.get(item["id"])) for item in DEFINITIONS]


def evaluate(db: Session, profile: Profile) -> list[dict]:
    """检查未解锁成就；解锁的写库+写通知，返回新解锁列表。"""
    unlocked = _unlocked_map(db)
    context = _build_context(db, profile)
    newly: list[dict] = []
    for definition in DEFINITIONS:
        if definition["id"] in unlocked:
            continue
        evaluator = _EVALUATORS.get(definition["id"])
        if evaluator is None or not evaluator(context):
            continue
        stamp = timeutil.now_iso()
        db.add(AchievementUnlock(id=definition["id"], unlocked_at=stamp))
        rules.add_notification(
            db,
            f"成就解锁：{definition['title']}",
            definition["description"],
            kind="achievement",
        )
        newly.append(_achievement_dict(definition, stamp))
    return newly
