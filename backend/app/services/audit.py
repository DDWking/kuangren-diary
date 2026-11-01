"""审计日志：只增不删。

- 没有任何删除/清空审计的接口；
- 完成、改策略、grant/lock/consume、设备事件、命令、报告、成就解锁都会写审计。
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..models import AuditLog

# 允许写入的 actor（来自 X-Actor 头，默认 app）
ACTORS = ("app", "pc", "hermes", "system")


def log(
    db: Session,
    action: str,
    *,
    actor: str = "system",
    target_type: str | None = None,
    target_id: str | None = None,
    detail: dict | None = None,
) -> AuditLog:
    row = AuditLog(
        id=timeutil.now_iso() + "-" + action[:40] + "-" + (target_id or "")[:8],
        actor=actor if actor in ACTORS else "system",
        action=action,
        target_type=target_type,
        target_id=str(target_id) if target_id is not None else None,
        detail=detail or {},
        created_at=timeutil.now_iso(),
    )
    # id 需要唯一：时间戳 + action + target 可能碰撞，补随机后缀
    import uuid

    row.id = str(uuid.uuid4())
    db.add(row)
    return row


def audit_to_dict(row: AuditLog) -> dict:
    return {
        "id": row.id,
        "actor": row.actor,
        "action": row.action,
        "target_type": row.target_type,
        "target_id": row.target_id,
        "detail": row.detail or {},
        "created_at": row.created_at,
    }


def list_logs(db: Session, limit: int = 50, action: str | None = None) -> list[dict]:
    stmt = select(AuditLog).order_by(AuditLog.created_at.desc()).limit(max(1, min(limit, 500)))
    if action:
        stmt = stmt.where(AuditLog.action == action)
    return [audit_to_dict(row) for row in db.scalars(stmt).all()]
