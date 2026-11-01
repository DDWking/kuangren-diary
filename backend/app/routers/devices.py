"""设备心跳、事件上报、命令下发与确认。"""

from __future__ import annotations

import re

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import Command, Device, Event
from ..responses import envelope
from ..schemas import (
    CommandAck,
    CommandCreate,
    EventBody,
    HeartbeatBody,
    command_dict,
    device_dict,
    event_dict,
)
from ..services import audit, rules, settlement
from .deps import actor, check_int, device_header

router = APIRouter(tags=["devices"])

DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")
COMMAND_TYPES = ("lock", "unlock", "reload", "kill")
VIOLATIONS_FOR_LOCK = 3


def _validate_device_id(device_id: str) -> str:
    value = (device_id or "").strip()
    if not DEVICE_ID_RE.match(value):
        raise ApiError("invalid_argument", "device_id 格式不正确（1–64 位字母/数字/_.:-）")
    return value


def _upsert_device(db: Session, device_id: str) -> Device:
    row = db.get(Device, device_id)
    if row is None:
        stamp = timeutil.now_iso()
        row = Device(
            id=device_id,
            name=device_id,
            platform="windows",
            app_version="",
            last_seen=None,
            state={},
            created_at=stamp,
            updated_at=stamp,
        )
        db.add(row)
        db.flush()
    return row


@router.get("/devices")
def list_devices(db: Session = Depends(get_db)) -> dict:
    rows = db.scalars(select(Device).order_by(Device.last_seen.desc())).all()
    return envelope([device_dict(row) for row in rows])


@router.post("/devices/{device_id}/heartbeat")
def heartbeat(
    device_id: str,
    body: HeartbeatBody,
    db: Session = Depends(get_db),
) -> dict:
    device_key = _validate_device_id(device_id)
    settlement.ensure_day(db)
    stamp = timeutil.now_iso()
    row = _upsert_device(db, device_key)
    if body.name:
        row.name = body.name.strip()[:100]
    if body.platform:
        row.platform = body.platform.strip()[:16]
    if body.app_version:
        row.app_version = body.app_version.strip()[:32]
    if body.state is not None:
        row.state = body.state
    row.last_seen = stamp
    row.updated_at = stamp
    db.commit()

    pending = db.scalars(
        select(Command)
        .where(Command.device_id == device_key, Command.status == "pending")
        .order_by(Command.created_at)
        .limit(20)
    ).all()
    return envelope(
        {
            "device": device_dict(row),
            "quota": rules.build_quota(db, timeutil.today_str()),
            "policy": rules.get_policy(db, timeutil.today_str()),
            "commands": [command_dict(item) for item in pending],
        }
    )


@router.post("/devices/{device_id}/events")
def report_event(
    device_id: str,
    body: EventBody,
    db: Session = Depends(get_db),
    header_device: str | None = Depends(device_header),
) -> dict:
    device_key = _validate_device_id(device_id)
    event_type = (body.type or "").strip()
    if not event_type or len(event_type) > 40:
        raise ApiError("invalid_argument", "type 长度应为 1–40 个字符")
    severity = (body.severity or "info").strip() or "info"
    if len(severity) > 16:
        raise ApiError("invalid_argument", "severity 最多 16 个字符")
    at = timeutil.parse_dt(body.at) if body.at else timeutil.now()

    _upsert_device(db, device_key)
    stamp = timeutil.now_iso()
    event = Event(
        id=rules.new_id(),
        device_id=device_key,
        type=event_type,
        severity=severity,
        payload=body.payload or {},
        at=timeutil.to_iso(at),
        created_at=stamp,
    )
    db.add(event)
    db.flush()  # Session 关闭了 autoflush：先落库再统计，保证第 3 次违规立即触发锁屏
    audit.log(
        db,
        "device.event",
        actor="pc",
        target_type="device",
        target_id=device_key,
        detail={"type": event_type, "severity": severity, "payload": body.payload or {}},
    )

    # 同一自然日第 3 次违规 → 下发锁屏命令（PC 端执行）
    if event_type == "violation":
        count = rules.violations_on(db, timeutil.today_str())
        if count >= VIOLATIONS_FOR_LOCK:
            existing = db.scalars(
                select(Command).where(
                    Command.device_id == device_key,
                    Command.type == "lock",
                    Command.status == "pending",
                    Command.created_at.like(f"{timeutil.today_str()}%"),
                )
            ).first()
            if existing is None:
                db.add(
                    Command(
                        id=rules.new_id(),
                        device_id=device_key,
                        type="lock",
                        payload={
                            "reason": "violations_limit",
                            "violations_today": count,
                        },
                        status="pending",
                        ttl_seconds=86400,
                        created_at=stamp,
                    )
                )
                audit.log(
                    db,
                    "command.create",
                    actor="system",
                    target_type="device",
                    target_id=device_key,
                    detail={"type": "lock", "reason": "violations_limit", "violations_today": count},
                )
                rules.add_notification(
                    db,
                    "今日违规达到 3 次",
                    "已向 PC 下发锁屏命令。",
                    kind="violation",
                )
    db.commit()
    return envelope({"id": event.id, "violations_today": rules.violations_on(db, timeutil.today_str())})


@router.get("/events")
def list_events(
    limit: int | None = Query(default=50),
    device_id: str | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    max_rows = check_int(limit, 1, 500, field="limit")
    stmt = select(Event).order_by(Event.at.desc()).limit(max_rows)
    if device_id:
        stmt = stmt.where(Event.device_id == _validate_device_id(device_id))
    rows = db.scalars(stmt).all()
    return envelope([event_dict(row) for row in rows])


def _expire_ttl_commands(db: Session) -> None:
    """把超过 ttl 仍未确认的命令标记为 failed，避免 PC 端执行过期命令。"""
    rows = db.scalars(select(Command).where(Command.status == "pending")).all()
    now = timeutil.now()
    changed = False
    for row in rows:
        if not row.ttl_seconds:
            continue
        try:
            created = timeutil.parse_dt(row.created_at)
        except ValueError:
            continue
        if created and (now - created).total_seconds() > int(row.ttl_seconds):
            row.status = "failed"
            row.note = "已过期（ttl）"
            row.acked_at = timeutil.now_iso()
            changed = True
    if changed:
        db.commit()


@router.get("/commands")
def list_commands(
    device_id: str | None = Query(default=None),
    status: str | None = Query(default="pending"),
    limit: int | None = Query(default=50),
    db: Session = Depends(get_db),
) -> dict:
    _expire_ttl_commands(db)
    max_rows = check_int(limit, 1, 200, field="limit")
    stmt = select(Command).order_by(Command.created_at).limit(max_rows)
    if device_id:
        stmt = stmt.where(Command.device_id == _validate_device_id(device_id))
    if status:
        stmt = stmt.where(Command.status == status)
    rows = db.scalars(stmt).all()
    return envelope([command_dict(row) for row in rows])


@router.post("/commands")
def create_command(
    body: CommandCreate,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    device_key = _validate_device_id(body.device_id)
    command_type = (body.type or "").strip()
    if command_type not in COMMAND_TYPES:
        raise ApiError("invalid_argument", f"type 应为 {'/'.join(COMMAND_TYPES)}")
    ttl = check_int(body.ttl_seconds, 1, 86400, field="ttl_seconds") if body.ttl_seconds is not None else None
    _upsert_device(db, device_key)
    row = Command(
        id=rules.new_id(),
        device_id=device_key,
        type=command_type,
        payload=body.payload or {},
        status="pending",
        ttl_seconds=ttl,
        created_at=timeutil.now_iso(),
    )
    db.add(row)
    audit.log(
        db,
        "command.create",
        actor=act,
        target_type="device",
        target_id=device_key,
        detail={"type": command_type, "payload": body.payload or {}, "command_id": row.id},
    )
    db.commit()
    return envelope(command_dict(row))


@router.post("/commands/{command_id}/ack")
def ack_command(
    command_id: str,
    body: CommandAck,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    row = db.get(Command, command_id)
    if row is None:
        raise ApiError("not_found", "命令不存在", 404)
    status = (body.status or "").strip()
    if status not in ("done", "failed"):
        raise ApiError("invalid_argument", "status 应为 done 或 failed")
    if row.status != "pending":
        if row.status == status:
            return envelope(command_dict(row))
        raise ApiError("conflict", "命令已确认过，状态不一致", 409)
    row.status = status
    row.acked_at = timeutil.now_iso()
    row.note = (body.note or "").strip()[:200] or None
    audit.log(
        db,
        "command.ack",
        actor=act,
        target_type="command",
        target_id=row.id,
        detail={"status": status, "note": row.note},
    )
    db.commit()
    return envelope(command_dict(row))
