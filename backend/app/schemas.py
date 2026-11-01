"""请求体模型 + 响应对象序列化（字段严格对齐 docs/api.md）。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict

from . import timeutil
from .models import (
    AuditLog,
    Command,
    Device,
    DiaryEntry,
    Event,
    Notification,
    Policy,
    Profile,
    QuotaRequest,
    Report,
    Task,
    WeightEntry,
)
from .services import rules


class _Request(BaseModel):
    model_config = ConfigDict(extra="ignore")


# ---------------------------------------------------------------------------
# 请求体
# ---------------------------------------------------------------------------
class ProfilePatch(_Request):
    name: str | None = None
    title: str | None = None
    exam_date: str | None = None
    daily_study_target_minutes: int | None = None


class TaskCreate(_Request):
    title: str
    description: str | None = None
    category: str = "other"
    difficulty: int = 3
    attribute: str = "intelligence"
    due_date: str | None = None
    estimated_minutes: int | None = None
    repeat_rule: str = "none"
    created_by: str | None = None


class TaskPatch(_Request):
    title: str | None = None
    description: str | None = None
    category: str | None = None
    difficulty: int | None = None
    attribute: str | None = None
    due_date: str | None = None
    estimated_minutes: int | None = None
    status: str | None = None


class TaskComplete(_Request):
    note: str | None = None
    actual_minutes: int | None = None
    client_completed_at: str | None = None


class TaskPostpone(_Request):
    to_date: str


class DiaryPut(_Request):
    mood: int | None = None
    content: str
    tags: list[str] | None = None


class DiaryPatch(_Request):
    mood: int | None = None
    content: str | None = None
    tags: list[str] | None = None


class DiaryComment(_Request):
    ai_comment: str


class WeightPut(_Request):
    weight_kg: float
    body_fat: float | None = None
    waist_cm: float | None = None
    note: str | None = None
    source: str = "manual"


class PolicyPut(_Request):
    date: str | None = None
    daily_base_minutes: int | None = None
    curfew_start: str | None = None
    curfew_end: str | None = None
    session_max_minutes: int | None = None
    blacklist: list[str] | None = None
    notes: str | None = None
    reason: str | None = None


class GrantBody(_Request):
    minutes: int
    reason: str
    actor: str | None = None
    expires_at: str | None = None


class ConsumeBody(_Request):
    minutes: int
    source: str = "pc"
    device_id: str | None = None
    detail: str | None = None


class LockBody(_Request):
    reason: str
    until: str | None = None
    device_id: str | None = None


class UnlockBody(_Request):
    reason: str


class QuotaRequestBody(_Request):
    minutes: int
    reason: str
    device_id: str | None = None


class DecideBody(_Request):
    approve: bool
    minutes: int | None = None
    note: str | None = None


class HeartbeatBody(_Request):
    name: str | None = None
    platform: str | None = None
    app_version: str | None = None
    state: dict[str, Any] | None = None


class EventBody(_Request):
    type: str
    severity: str = "info"
    payload: dict[str, Any] | None = None
    at: str | None = None


class CommandCreate(_Request):
    device_id: str
    type: str
    payload: dict[str, Any] | None = None
    ttl_seconds: int | None = None


class CommandAck(_Request):
    status: str
    note: str | None = None


class ReportCreate(_Request):
    title: str
    content: str
    kind: str = "system"


# ---------------------------------------------------------------------------
# 序列化
# ---------------------------------------------------------------------------
def profile_dict(profile: Profile) -> dict:
    exp_next = rules.exp_required_next(profile.level)
    exam_days = 0
    try:
        exam_days = max(0, (timeutil.parse_date(profile.exam_date) - timeutil.today()).days)
    except Exception:
        exam_days = 0
    return {
        "id": profile.id,
        "name": profile.name,
        "title": profile.title,
        "level": int(profile.level),
        "exp": int(profile.exp),
        "exp_next": exp_next,
        "exp_progress": round(int(profile.exp) / exp_next, 4) if exp_next else 0.0,
        "coins": int(profile.coins),
        "streak": int(profile.streak),
        "attributes": {
            "intelligence": int(profile.intelligence),
            "stamina": int(profile.stamina),
            "willpower": int(profile.willpower),
            "focus": int(profile.focus),
            "order": int(profile.order_attr),
        },
        "exam_date": profile.exam_date,
        "exam_days": exam_days,
        "daily_study_target_minutes": int(profile.daily_study_target_minutes),
        "total_study_minutes": int(profile.total_study_minutes),
        "total_tasks_done": int(profile.total_tasks_done),
        "created_at": profile.created_at,
        "updated_at": profile.updated_at,
    }


def task_dict(task: Task) -> dict:
    return {
        "id": task.id,
        "title": task.title,
        "description": task.description,
        "category": task.category,
        "difficulty": int(task.difficulty),
        "attribute": task.attribute,
        "due_date": task.due_date,
        "estimated_minutes": task.estimated_minutes,
        "status": task.status,
        "repeat_rule": task.repeat_rule,
        "created_by": task.created_by,
        "created_at": task.created_at,
        "completed_at": task.completed_at,
        "completion_note": task.completion_note,
        "actual_minutes": task.actual_minutes,
        "exp_reward": int(task.exp_reward or 0),
        "coin_reward": int(task.coin_reward or 0),
        "deleted_at": task.deleted_at,
    }


def diary_dict(entry: DiaryEntry) -> dict:
    return {
        "date": entry.date,
        "mood": entry.mood,
        "content": entry.content,
        "ai_comment": entry.ai_comment,
        "tags": list(entry.tags or []),
        "created_at": entry.created_at,
        "updated_at": entry.updated_at,
    }


def weight_dict(entry: WeightEntry) -> dict:
    return {
        "date": entry.date,
        "weight_kg": float(entry.weight_kg),
        "body_fat": float(entry.body_fat) if entry.body_fat is not None else None,
        "waist_cm": float(entry.waist_cm) if entry.waist_cm is not None else None,
        "note": entry.note,
        "source": entry.source,
        "created_at": entry.created_at,
    }


def policy_dict(row: Policy) -> dict:
    return rules.policy_to_dict(row)


def quota_request_dict(row: QuotaRequest) -> dict:
    return {
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


def device_dict(device: Device) -> dict:
    return {
        "id": device.id,
        "name": device.name,
        "platform": device.platform,
        "app_version": device.app_version,
        "last_seen": device.last_seen,
        "online": rules.device_is_online(device.last_seen),
        "state": device.state or {},
    }


def command_dict(row: Command) -> dict:
    return {
        "id": row.id,
        "device_id": row.device_id,
        "type": row.type,
        "payload": row.payload or {},
        "status": row.status,
        "created_at": row.created_at,
        "acked_at": row.acked_at,
    }


def event_dict(row: Event) -> dict:
    return {
        "id": row.id,
        "device_id": row.device_id,
        "type": row.type,
        "severity": row.severity,
        "payload": row.payload or {},
        "at": row.at,
    }


def audit_dict(row: AuditLog) -> dict:
    return {
        "id": row.id,
        "actor": row.actor,
        "action": row.action,
        "target_type": row.target_type,
        "target_id": row.target_id,
        "detail": row.detail or {},
        "created_at": row.created_at,
    }


def report_dict(row: Report) -> dict:
    return {
        "id": row.id,
        "title": row.title,
        "content": row.content,
        "kind": row.kind,
        "created_at": row.created_at,
    }


def notification_dict(row: Notification) -> dict:
    return {
        "id": row.id,
        "title": row.title,
        "content": row.content,
        "kind": row.kind,
        "read": bool(row.read),
        "created_at": row.created_at,
    }
