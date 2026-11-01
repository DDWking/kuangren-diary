"""任务：增删改查、完成（幂等）、回退、顺延。"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import Task
from ..responses import envelope
from ..schemas import TaskComplete, TaskCreate, TaskPatch, TaskPostpone, profile_dict, task_dict
from ..services import audit, rules, settlement
from ..services import achievements as achievements_service
from .deps import actor, check_int, check_limit, optional_day, parse_day

router = APIRouter(tags=["tasks"])


def _get_task(db: Session, task_id: str) -> Task:
    task = db.get(Task, task_id)
    if task is None or task.deleted_at:
        raise ApiError("not_found", "任务不存在", 404)
    return task


def _validate_category(value: str) -> str:
    if value not in rules.TASK_CATEGORIES:
        raise ApiError("invalid_argument", f"category 应为 {'/'.join(rules.TASK_CATEGORIES)}")
    return value


def _validate_attribute(value: str) -> str:
    if value not in rules.ATTRIBUTE_NAMES:
        raise ApiError("invalid_argument", f"attribute 应为 {'/'.join(rules.ATTRIBUTE_NAMES)}")
    return value


def _validate_difficulty(value: object) -> int:
    return check_int(value, rules.DIFFICULTY_MIN, rules.DIFFICULTY_MAX, field="difficulty")


def _validate_repeat(value: str) -> str:
    if value not in rules.REPEAT_RULES:
        raise ApiError("invalid_argument", f"repeat_rule 应为 {'/'.join(rules.REPEAT_RULES)}")
    return value


@router.get("/tasks")
def list_tasks(
    date: str | None = Query(default=None),
    from_date: str | None = Query(default=None, alias="from"),
    to_date: str | None = Query(default=None, alias="to"),
    status: str | None = Query(default=None),
    category: str | None = Query(default=None),
    limit: int | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    rows_limit = check_limit(limit, default=200)
    stmt = select(Task).where(Task.deleted_at.is_(None))
    day = optional_day(date)
    if day:
        stmt = stmt.where(Task.due_date == day)
    begin = optional_day(from_date, field="from")
    if begin:
        stmt = stmt.where(Task.due_date >= begin)
    finish = optional_day(to_date, field="to")
    if finish:
        stmt = stmt.where(Task.due_date <= finish)
    if status:
        if status not in rules.TASK_STATUSES:
            raise ApiError("invalid_argument", f"status 应为 {'/'.join(rules.TASK_STATUSES)}")
        stmt = stmt.where(Task.status == status)
    if category:
        stmt = stmt.where(Task.category == _validate_category(category))

    rows = db.scalars(
        stmt.order_by(Task.due_date, Task.created_at).limit(rows_limit)
    ).all()
    return envelope([task_dict(row) for row in rows])


@router.post("/tasks")
def create_task(
    body: TaskCreate,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    title = (body.title or "").strip()
    if not title or len(title) > 200:
        raise ApiError("invalid_argument", "title 长度应为 1–200 个字符")
    category = _validate_category(body.category or "other")
    attribute = _validate_attribute(body.attribute or "intelligence")
    difficulty = _validate_difficulty(body.difficulty)
    repeat_rule = _validate_repeat(body.repeat_rule or "none")
    due_date = parse_day(body.due_date, field="due_date") if body.due_date else timeutil.today_str()
    actual_estimate = None
    if body.estimated_minutes is not None:
        actual_estimate = check_int(body.estimated_minutes, 1, 1440, field="estimated_minutes")

    profile = rules.get_profile(db)
    preview = rules.compute_rewards(difficulty, profile.streak)
    task = Task(
        id=rules.new_id(),
        title=title,
        description=body.description,
        category=category,
        difficulty=difficulty,
        attribute=attribute,
        due_date=due_date,
        estimated_minutes=actual_estimate,
        status="pending",
        repeat_rule=repeat_rule,
        created_by=(body.created_by or act or "user")[:16],
        created_at=timeutil.now_iso(),
        updated_at=timeutil.now_iso(),
        exp_reward=preview["exp"],
        coin_reward=preview["coins"],
    )
    db.add(task)
    audit.log(
        db,
        "task.create",
        actor=act,
        target_type="task",
        target_id=task.id,
        detail={"title": title, "category": category, "difficulty": difficulty},
    )
    db.commit()
    return envelope(task_dict(task))


@router.patch("/tasks/{task_id}")
def patch_task(
    task_id: str,
    body: TaskPatch,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    task = _get_task(db, task_id)
    changed: dict = {}
    fields = body.model_fields_set
    if "title" in fields and body.title is not None:
        title = body.title.strip()
        if not title or len(title) > 200:
            raise ApiError("invalid_argument", "title 长度应为 1–200 个字符")
        task.title = title
        changed["title"] = title
    if "description" in fields:
        task.description = body.description
        changed["description"] = body.description
    if "category" in fields and body.category is not None:
        task.category = _validate_category(body.category)
        changed["category"] = task.category
    if "difficulty" in fields and body.difficulty is not None:
        task.difficulty = _validate_difficulty(body.difficulty)
        changed["difficulty"] = task.difficulty
    if "attribute" in fields and body.attribute is not None:
        task.attribute = _validate_attribute(body.attribute)
        changed["attribute"] = task.attribute
    if "due_date" in fields and body.due_date is not None:
        task.due_date = parse_day(body.due_date, field="due_date")
        changed["due_date"] = task.due_date
    if "estimated_minutes" in fields and body.estimated_minutes is not None:
        task.estimated_minutes = check_int(body.estimated_minutes, 1, 1440, field="estimated_minutes")
        changed["estimated_minutes"] = task.estimated_minutes
    if "status" in fields and body.status is not None:
        if body.status == "done":
            raise ApiError("invalid_argument", "完成任务请使用 POST /tasks/{id}/complete，以保证奖励幂等")
        if body.status not in rules.TASK_STATUSES:
            raise ApiError("invalid_argument", f"status 应为 {'/'.join(rules.TASK_STATUSES)}")
        task.status = body.status
        changed["status"] = body.status

    task.updated_at = timeutil.now_iso()
    audit.log(db, "task.update", actor=act, target_type="task", target_id=task.id, detail=changed)
    db.commit()
    return envelope(task_dict(task))


@router.delete("/tasks/{task_id}")
def delete_task(
    task_id: str,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    task = db.get(Task, task_id)
    if task is None:
        raise ApiError("not_found", "任务不存在", 404)
    if task.deleted_at is None:
        task.deleted_at = timeutil.now_iso()
        task.updated_at = task.deleted_at
        audit.log(
            db,
            "task.delete",
            actor=act,
            target_type="task",
            target_id=task.id,
            detail={"title": task.title},
        )
        db.commit()
    return envelope({"deleted": True})


@router.post("/tasks/{task_id}/complete")
def complete_task(
    task_id: str,
    body: TaskComplete,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    task = _get_task(db, task_id)
    settlement.ensure_day(db)
    profile = rules.get_profile(db)
    if body.actual_minutes is not None:
        check_int(body.actual_minutes, 0, 1440, field="actual_minutes")
    result = rules.complete_task(
        db,
        task,
        profile,
        note=body.note,
        actual_minutes=body.actual_minutes,
    )
    unlocked = achievements_service.evaluate(db, profile)
    audit.log(
        db,
        "task.complete",
        actor=act,
        target_type="task",
        target_id=task.id,
        detail={
            "rewards": result["rewards"],
            "already_done": result["already_done"],
            "title": task.title,
        },
    )
    db.commit()
    quota = rules.build_quota(db, timeutil.today_str(), profile)
    return envelope(
        {
            "task": task_dict(task),
            "rewards": result["rewards"],
            "profile": profile_dict(profile),
            "quota": quota,
            "achievements_unlocked": unlocked,
        }
    )


@router.post("/tasks/{task_id}/reopen")
def reopen_task(
    task_id: str,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    task = _get_task(db, task_id)
    if task.status != "done":
        raise ApiError("conflict", "任务尚未完成，无法回退", 409)
    completed_day = (task.completed_at or "")[:10]
    if completed_day != timeutil.today_str():
        raise ApiError("conflict", "只允许回退当天完成的任务", 409)
    profile = rules.get_profile(db)
    result = rules.reopen_task(db, task, profile)
    audit.log(
        db,
        "task.reopen",
        actor=act,
        target_type="task",
        target_id=task.id,
        detail={"reverted_rewards": result["reverted_rewards"], "title": task.title},
    )
    db.commit()
    return envelope(task_dict(task))


@router.post("/tasks/{task_id}/postpone")
def postpone_task(
    task_id: str,
    body: TaskPostpone,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    task = _get_task(db, task_id)
    if task.status == "done":
        raise ApiError("conflict", "已完成的任务不能顺延", 409)
    to_date = parse_day(body.to_date, field="to_date")
    if to_date < timeutil.today_str():
        raise ApiError("invalid_argument", "to_date 不能早于今天")
    previous = task.due_date
    task.due_date = to_date
    task.updated_at = timeutil.now_iso()
    audit.log(
        db,
        "task.postpone",
        actor=act,
        target_type="task",
        target_id=task.id,
        detail={"from": previous, "to": to_date, "title": task.title},
    )
    db.commit()
    return envelope(task_dict(task))
