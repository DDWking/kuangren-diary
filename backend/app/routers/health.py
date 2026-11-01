"""健康检查：不依赖认证与数据库也可返回（数据库异常时标红）。"""

from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import text

from ..db import engine
from ..responses import envelope
from ..settings import settings
from ..timeutil import now_iso

router = APIRouter(tags=["system"])


def _db_status() -> str:
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        return "ok"
    except Exception:  # pragma: no cover - 只在数据库损坏时触发
        return "error"


def health_payload() -> dict:
    return envelope(
        {
            "status": "ok",
            "version": settings.version,
            "db": _db_status(),
            "server_time": now_iso(),
        }
    )


@router.get("/health")
@router.get("/api/v1/health")
def health() -> dict:
    return health_payload()
