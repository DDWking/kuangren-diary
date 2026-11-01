"""每日结算 / 惰性日切。

没有常驻定时任务：每次需要“今天”的数据时（quota/panel/heartbeat/complete 等）调用
ensure_day，确保当天 quota 行存在、连续零学习收紧通知只发一次。
这样服务重启、服务器时区变化都不会丢数据；SQLite 数据在 <data_dir>/kuangren.db 持久化。
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import timeutil
from ..models import Notification
from . import rules


def ensure_day(db: Session, day: str | None = None) -> None:
    key = day or timeutil.today_str()
    rules.quota_row(db, key)

    profile = rules.get_profile(db)
    tightened, reason = rules.forced_tighten_state(db, profile, timeutil.parse_date(key))
    if not tightened:
        return
    exists = db.scalar(
        select(func.count(Notification.id)).where(
            Notification.kind == "tighten", Notification.created_at.like(f"{key}%")
        )
    )
    if exists:
        return
    rules.add_notification(db, "额度收紧", reason or rules.FORCED_TIGHTEN_REASON, kind="tighten")
    db.commit()
