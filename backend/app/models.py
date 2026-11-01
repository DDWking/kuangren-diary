"""SQLAlchemy 2 数据模型。

约定：
- 所有时间戳以 ISO-8601 字符串存储（Asia/Shanghai，带 +08:00），方便按字典序排序与直接回给客户端；
- 所有日期以 YYYY-MM-DD 字符串存储（Asia/Shanghai 本地日切）；
- 审计表 audit_logs 只增不删（没有任何删除接口）。
"""

from __future__ import annotations

from sqlalchemy import JSON, Boolean, Float, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class Profile(Base):
    __tablename__ = "profiles"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(64), default="宿主")
    title: Mapped[str] = mapped_column(String(64), default="初醒者")
    level: Mapped[int] = mapped_column(Integer, default=1)
    exp: Mapped[int] = mapped_column(Integer, default=0)
    coins: Mapped[int] = mapped_column(Integer, default=0)
    streak: Mapped[int] = mapped_column(Integer, default=0)
    intelligence: Mapped[int] = mapped_column(Integer, default=0)
    stamina: Mapped[int] = mapped_column(Integer, default=0)
    willpower: Mapped[int] = mapped_column(Integer, default=0)
    focus: Mapped[int] = mapped_column(Integer, default=0)
    order_attr: Mapped[int] = mapped_column("order_attr", Integer, default=0)
    exam_date: Mapped[str] = mapped_column(String(10), default="2026-12-19")
    daily_study_target_minutes: Mapped[int] = mapped_column(Integer, default=300)
    total_study_minutes: Mapped[int] = mapped_column(Integer, default=0)
    total_tasks_done: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[str] = mapped_column(String(40), default="")
    updated_at: Mapped[str] = mapped_column(String(40), default="")


class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    title: Mapped[str] = mapped_column(String(200))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    category: Mapped[str] = mapped_column(String(16), default="other")
    difficulty: Mapped[int] = mapped_column(Integer, default=3)
    attribute: Mapped[str] = mapped_column(String(16), default="intelligence")
    due_date: Mapped[str] = mapped_column(String(10), default="")
    estimated_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="pending")
    repeat_rule: Mapped[str] = mapped_column(String(16), default="none")
    created_by: Mapped[str] = mapped_column(String(16), default="user")
    created_at: Mapped[str] = mapped_column(String(40), default="")
    updated_at: Mapped[str] = mapped_column(String(40), default="")
    completed_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    completion_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    actual_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    exp_reward: Mapped[int] = mapped_column(Integer, default=0)
    coin_reward: Mapped[int] = mapped_column(Integer, default=0)
    completion_meta: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    deleted_at: Mapped[str | None] = mapped_column(String(40), nullable=True)


class DiaryEntry(Base):
    __tablename__ = "diary_entries"

    date: Mapped[str] = mapped_column(String(10), primary_key=True)
    mood: Mapped[int | None] = mapped_column(Integer, nullable=True)
    content: Mapped[str] = mapped_column(Text, default="")
    ai_comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    tags: Mapped[list | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[str] = mapped_column(String(40), default="")
    updated_at: Mapped[str] = mapped_column(String(40), default="")


class WeightEntry(Base):
    __tablename__ = "weight_entries"

    date: Mapped[str] = mapped_column(String(10), primary_key=True)
    weight_kg: Mapped[float] = mapped_column(Float)
    body_fat: Mapped[float | None] = mapped_column(Float, nullable=True)
    waist_cm: Mapped[float | None] = mapped_column(Float, nullable=True)
    note: Mapped[str | None] = mapped_column(String(200), nullable=True)
    source: Mapped[str] = mapped_column(String(16), default="manual")
    created_at: Mapped[str] = mapped_column(String(40), default="")
    updated_at: Mapped[str] = mapped_column(String(40), default="")


class Policy(Base):
    __tablename__ = "policies"

    date: Mapped[str] = mapped_column(String(10), primary_key=True)
    daily_base_minutes: Mapped[int] = mapped_column(Integer, default=180)
    curfew_start: Mapped[str] = mapped_column(String(5), default="01:00")
    curfew_end: Mapped[str] = mapped_column(String(5), default="07:00")
    session_max_minutes: Mapped[int] = mapped_column(Integer, default=60)
    blacklist: Mapped[list | None] = mapped_column(JSON, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str] = mapped_column(String(16), default="fallback")
    updated_by: Mapped[str] = mapped_column(String(16), default="system")
    updated_at: Mapped[str] = mapped_column(String(40), default="")


class QuotaDay(Base):
    __tablename__ = "quota_days"

    date: Mapped[str] = mapped_column(String(10), primary_key=True)
    used_minutes: Mapped[int] = mapped_column(Integer, default=0)
    locked: Mapped[bool] = mapped_column(Boolean, default=False)
    lock_reason: Mapped[str | None] = mapped_column(String(200), nullable=True)
    lock_until: Mapped[str | None] = mapped_column(String(40), nullable=True)
    created_at: Mapped[str] = mapped_column(String(40), default="")
    updated_at: Mapped[str] = mapped_column(String(40), default="")


class Grant(Base):
    __tablename__ = "quota_grants"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    date: Mapped[str] = mapped_column(String(10), index=True)
    minutes: Mapped[int] = mapped_column(Integer, default=0)
    reason: Mapped[str] = mapped_column(String(200), default="")
    actor: Mapped[str] = mapped_column(String(16), default="hermes")
    expires_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[str] = mapped_column(String(40), default="")


class QuotaRequest(Base):
    __tablename__ = "quota_requests"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    date: Mapped[str] = mapped_column(String(10), index=True)
    minutes: Mapped[int] = mapped_column(Integer, default=0)
    reason: Mapped[str] = mapped_column(String(200), default="")
    device_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="pending")
    decided_by: Mapped[str | None] = mapped_column(String(16), nullable=True)
    decision_note: Mapped[str | None] = mapped_column(String(200), nullable=True)
    created_at: Mapped[str] = mapped_column(String(40), default="")
    decided_at: Mapped[str | None] = mapped_column(String(40), nullable=True)


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(100), default="")
    platform: Mapped[str] = mapped_column(String(16), default="windows")
    app_version: Mapped[str] = mapped_column(String(32), default="")
    last_seen: Mapped[str | None] = mapped_column(String(40), nullable=True)
    state: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[str] = mapped_column(String(40), default="")
    updated_at: Mapped[str] = mapped_column(String(40), default="")


class Event(Base):
    __tablename__ = "events"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    device_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    type: Mapped[str] = mapped_column(String(40), default="info")
    severity: Mapped[str] = mapped_column(String(16), default="info")
    payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    at: Mapped[str] = mapped_column(String(40), default="", index=True)
    created_at: Mapped[str] = mapped_column(String(40), default="")


class Command(Base):
    __tablename__ = "commands"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    type: Mapped[str] = mapped_column(String(16), default="lock")
    payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="pending")
    ttl_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[str] = mapped_column(String(40), default="")
    acked_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    note: Mapped[str | None] = mapped_column(String(200), nullable=True)


class AuditLog(Base):
    """审计日志：只增不删。没有删除/清空接口。"""

    __tablename__ = "audit_logs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    actor: Mapped[str] = mapped_column(String(16), default="system")
    action: Mapped[str] = mapped_column(String(64), default="")
    target_type: Mapped[str | None] = mapped_column(String(32), nullable=True)
    target_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    detail: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[str] = mapped_column(String(40), default="", index=True)


class AchievementUnlock(Base):
    __tablename__ = "achievement_unlocks"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    unlocked_at: Mapped[str] = mapped_column(String(40), default="")


class Report(Base):
    __tablename__ = "reports"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    title: Mapped[str] = mapped_column(String(200), default="")
    content: Mapped[str] = mapped_column(Text, default="")
    kind: Mapped[str] = mapped_column(String(16), default="system")
    created_at: Mapped[str] = mapped_column(String(40), default="")


class Notification(Base):
    __tablename__ = "notifications"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    title: Mapped[str] = mapped_column(String(200), default="")
    content: Mapped[str] = mapped_column(Text, default="")
    kind: Mapped[str] = mapped_column(String(16), default="system")
    read: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[str] = mapped_column(String(40), default="")


class DailyStat(Base):
    __tablename__ = "daily_stats"

    date: Mapped[str] = mapped_column(String(10), primary_key=True)
    study_minutes: Mapped[int] = mapped_column(Integer, default=0)
    tasks_done: Mapped[int] = mapped_column(Integer, default=0)
    quota_used: Mapped[int] = mapped_column(Integer, default=0)


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_keys"

    key: Mapped[str] = mapped_column(String(200), primary_key=True)
    method: Mapped[str] = mapped_column(String(10), default="")
    path: Mapped[str] = mapped_column(String(200), default="")
    status_code: Mapped[int] = mapped_column(Integer, default=200)
    response_body: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[str] = mapped_column(String(40), default="")
