"""核心业务规则：等级/EXP/积分/五维/连击/额度/策略。"""

from __future__ import annotations

import math
import uuid
from datetime import date, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import timeutil
from ..models import (
    DailyStat,
    Event,
    Grant,
    Notification,
    Policy,
    Profile,
    QuotaDay,
    Task,
)
from ..settings import settings

ATTRIBUTE_FIELDS = {
    "intelligence": "intelligence",
    "stamina": "stamina",
    "willpower": "willpower",
    "focus": "focus",
    "order": "order_attr",
}
ATTRIBUTE_NAMES = tuple(ATTRIBUTE_FIELDS.keys())
TASK_CATEGORIES = ("study", "body", "life", "mind", "other")
TASK_STATUSES = ("pending", "done", "skipped")
REPEAT_RULES = ("none", "daily", "weekly")
DIFFICULTY_MIN = 1
DIFFICULTY_MAX = 5

DEFAULT_BLACKLIST = [
    "steam.exe",
    "WeGame.exe",
    "valorant.exe",
    "deadlock.exe",
    "MinecraftLauncher.exe",
]

FORCED_TIGHTEN_REASON = "连续 3 个自然日零学习：基础娱乐额度强制减半，完成任意学习任务后解除"


# ---------------------------------------------------------------------------
# 基础计算
# ---------------------------------------------------------------------------
def round_half_up(value: float) -> int:
    """四舍五入（Python 内建 round 是银行家舍入，这里不用它）。"""
    return int(math.floor(value + 0.5))


def exp_required_next(level: int) -> int:
    return 100 + 50 * (max(1, int(level)) - 1)


def streak_multiplier(streak: int) -> float:
    effective = min(max(int(streak), 0), settings.streak_cap)
    return min(settings.streak_multiplier_cap, 1.0 + settings.streak_step * effective)


def compute_rewards(difficulty: int, streak: int) -> dict:
    multiplier = streak_multiplier(streak)
    return {
        "exp": round_half_up((10 + 10 * int(difficulty)) * multiplier),
        "coins": round_half_up(5 * int(difficulty) * multiplier),
        "attribute_delta": int(difficulty) * 2,
        "streak_multiplier": round(multiplier, 2),
    }


def build_reward_payload(task: Task, streak: int) -> dict:
    base = compute_rewards(task.difficulty, streak)
    return {
        "exp": base["exp"],
        "coins": base["coins"],
        "attribute": task.attribute,
        "attribute_delta": base["attribute_delta"],
        "streak_multiplier": base["streak_multiplier"],
    }


def add_exp(profile: Profile, amount: int) -> None:
    profile.exp = int(profile.exp or 0) + max(0, int(amount))
    while profile.exp >= exp_required_next(profile.level):
        profile.exp -= exp_required_next(profile.level)
        profile.level += 1


def remove_exp(profile: Profile, amount: int) -> None:
    profile.exp = int(profile.exp or 0) - max(0, int(amount))
    while profile.exp < 0 and profile.level > 1:
        profile.level -= 1
        profile.exp += exp_required_next(profile.level)
    if profile.exp < 0:
        profile.exp = 0


def add_attribute(profile: Profile, attribute: str, delta: int) -> None:
    field = ATTRIBUTE_FIELDS.get(attribute)
    if field is None:
        return
    setattr(profile, field, min(100, int(getattr(profile, field) or 0) + max(0, int(delta))))


def remove_attribute(profile: Profile, attribute: str, delta: int) -> None:
    field = ATTRIBUTE_FIELDS.get(attribute)
    if field is None:
        return
    setattr(profile, field, max(0, int(getattr(profile, field) or 0) - max(0, int(delta))))


# ---------------------------------------------------------------------------
# Profile
# ---------------------------------------------------------------------------
def get_profile(db: Session) -> Profile:
    profile = db.get(Profile, "default")
    if profile is None:
        stamp = timeutil.now_iso()
        profile = Profile(
            id="default",
            name="宿主",
            title="初醒者",
            level=1,
            exp=0,
            coins=0,
            streak=0,
            intelligence=0,
            stamina=0,
            willpower=0,
            focus=0,
            order_attr=0,
            exam_date=settings.default_exam_date,
            daily_study_target_minutes=settings.default_daily_study_target_minutes,
            total_study_minutes=0,
            total_tasks_done=0,
            created_at=stamp,
            updated_at=stamp,
        )
        db.add(profile)
        db.commit()
        db.refresh(profile)
    return profile


def profile_created_date(profile: Profile) -> date:
    try:
        return timeutil.parse_date(str(profile.created_at)[:10])
    except Exception:
        return timeutil.today()


# ---------------------------------------------------------------------------
# 策略
# ---------------------------------------------------------------------------
def default_blacklist() -> list[str]:
    return list(DEFAULT_BLACKLIST)


def fallback_policy(day: str) -> dict:
    return {
        "date": day,
        "daily_base_minutes": settings.default_daily_base_minutes,
        "curfew_start": settings.default_curfew_start,
        "curfew_end": settings.default_curfew_end,
        "session_max_minutes": settings.default_session_max_minutes,
        "blacklist": default_blacklist(),
        "notes": None,
        "source": "fallback",
        "updated_by": "system",
        "updated_at": f"{day}T00:00:00+08:00",
    }


def policy_to_dict(row: Policy) -> dict:
    return {
        "date": row.date,
        "daily_base_minutes": int(row.daily_base_minutes),
        "curfew_start": row.curfew_start,
        "curfew_end": row.curfew_end,
        "session_max_minutes": int(row.session_max_minutes),
        "blacklist": list(row.blacklist or []),
        "notes": row.notes,
        "source": row.source,
        "updated_by": row.updated_by,
        "updated_at": row.updated_at,
    }


def get_policy(db: Session, day: str) -> dict:
    row = db.get(Policy, day)
    if row is None:
        return fallback_policy(day)
    return policy_to_dict(row)


# ---------------------------------------------------------------------------
# 每日统计 / 连击
# ---------------------------------------------------------------------------
def bump_daily_stat(
    db: Session,
    day: str,
    *,
    study_minutes: int = 0,
    tasks_done: int = 0,
    quota_used: int = 0,
) -> DailyStat:
    row = db.get(DailyStat, day)
    if row is None:
        row = DailyStat(date=day, study_minutes=0, tasks_done=0, quota_used=0)
        db.add(row)
        db.flush()
    row.study_minutes = max(0, int(row.study_minutes or 0) + int(study_minutes))
    row.tasks_done = max(0, int(row.tasks_done or 0) + int(tasks_done))
    row.quota_used = max(0, int(row.quota_used or 0) + int(quota_used))
    return row


def study_minutes_on(db: Session, day: date | str) -> int:
    key = day if isinstance(day, str) else timeutil.date_str(day)
    row = db.get(DailyStat, key)
    return int(row.study_minutes or 0) if row else 0


def recompute_streak(db: Session, profile: Profile, day: date | None = None) -> int:
    """连击 = 从今天（今天没学则从昨天）往回数的连续“有学习完成记录”自然日数。"""
    cursor = day or timeutil.today()
    if study_minutes_on(db, cursor) <= 0:
        cursor = cursor - timedelta(days=1)
    streak = 0
    guard = 0
    while study_minutes_on(db, cursor) > 0 and guard < 4000:
        streak += 1
        cursor = cursor - timedelta(days=1)
        guard += 1
    profile.streak = streak
    return streak


# ---------------------------------------------------------------------------
# 硬底线 3：连续 3 个自然日零学习 → 次日基础额度减半
# ---------------------------------------------------------------------------
def forced_tighten_state(db: Session, profile: Profile, day: date) -> tuple[bool, str | None]:
    created = profile_created_date(profile)
    # 账号建立不满 3 天时不追溯（避免新库/新账号第一天就被强制收紧）
    if (day - created).days < 3:
        return False, None
    if study_minutes_on(db, day) > 0:
        return False, None
    for offset in (1, 2, 3):
        if study_minutes_on(db, day - timedelta(days=offset)) > 0:
            return False, None
    return True, FORCED_TIGHTEN_REASON


def effective_base_minutes(policy: dict, tightened: bool) -> int:
    base = int(policy.get("daily_base_minutes") or 0)
    if tightened:
        base = base // 2
    return max(0, base)


# ---------------------------------------------------------------------------
# 额度
# ---------------------------------------------------------------------------
def quota_row(db: Session, day: str) -> QuotaDay:
    row = db.get(QuotaDay, day)
    if row is None:
        stamp = timeutil.now_iso()
        row = QuotaDay(
            date=day,
            used_minutes=0,
            locked=False,
            lock_reason=None,
            lock_until=None,
            created_at=stamp,
            updated_at=stamp,
        )
        db.add(row)
        db.commit()
        db.refresh(row)
    return row


def active_grants(db: Session, day: str) -> list[Grant]:
    now_stamp = timeutil.now_iso()
    rows = db.scalars(
        select(Grant).where(Grant.date == day, Grant.revoked.is_(False)).order_by(Grant.created_at)
    ).all()
    return [row for row in rows if not row.expires_at or row.expires_at > now_stamp]


def grant_to_dict(row: Grant) -> dict:
    return {
        "id": row.id,
        "minutes": int(row.minutes),
        "reason": row.reason,
        "actor": row.actor,
        "expires_at": row.expires_at,
        "revoked": bool(row.revoked),
        "created_at": row.created_at,
    }


def violations_on(db: Session, day: str) -> int:
    total = db.scalar(
        select(func.count(Event.id)).where(Event.type == "violation", Event.at.like(f"{day}%"))
    )
    return int(total or 0)


def build_quota(db: Session, day: str, profile: Profile | None = None) -> dict:
    profile = profile or get_profile(db)
    day_date = timeutil.parse_date(day)
    policy = get_policy(db, day)
    tightened, tighten_reason = forced_tighten_state(db, profile, day_date)
    base_minutes = effective_base_minutes(policy, tightened)
    row = quota_row(db, day)

    stamp = timeutil.now_iso()
    grants = active_grants(db, day)
    bonus_minutes = sum(int(grant.minutes) for grant in grants)
    used_minutes = int(row.used_minutes or 0)
    remaining_minutes = max(0, base_minutes + bonus_minutes - used_minutes)

    explicit_locked = bool(row.locked) and (not row.lock_until or row.lock_until > stamp)
    curfew_locked = day == timeutil.today_str() and timeutil.is_in_window(
        timeutil.parse_hhmm(policy["curfew_start"]),
        timeutil.parse_hhmm(policy["curfew_end"]),
    )

    if explicit_locked:
        locked = True
        lock_reason = row.lock_reason or "explicit_lock"
        lock_until = row.lock_until
    elif curfew_locked:
        locked = True
        lock_reason = "curfew"
        lock_until = None
    elif remaining_minutes <= 0:
        locked = True
        lock_reason = "quota_exhausted"
        lock_until = None
    else:
        locked = False
        lock_reason = None
        lock_until = None

    return {
        "date": day,
        "base_minutes": base_minutes,
        "bonus_minutes": bonus_minutes,
        "used_minutes": used_minutes,
        "remaining_minutes": remaining_minutes,
        "locked": locked,
        "lock_reason": lock_reason,
        "lock_until": lock_until,
        "policy": policy,
        "extensions": [grant_to_dict(grant) for grant in grants],
        "violations_today": violations_on(db, day),
        "forced_tighten": tightened,
        "tighten_reason": tighten_reason,
    }


# ---------------------------------------------------------------------------
# 任务完成 / 回退
# ---------------------------------------------------------------------------
def task_credited_minutes(task: Task, actual_minutes: int | None = None) -> int:
    if actual_minutes is not None:
        return max(0, int(actual_minutes))
    if task.actual_minutes is not None:
        return max(0, int(task.actual_minutes))
    return max(0, int(task.estimated_minutes or 0))


def complete_task(
    db: Session,
    task: Task,
    profile: Profile,
    *,
    note: str | None = None,
    actual_minutes: int | None = None,
    completed_at: str | None = None,
) -> dict:
    """执行任务完成。已完成的任务重复调用返回首次结果（幂等，不重复加经验）。"""
    if task.status == "done":
        meta = task.completion_meta or {}
        rewards = meta.get("rewards") or build_reward_payload(task, profile.streak)
        return {"rewards": rewards, "already_done": True}

    rewards = build_reward_payload(task, profile.streak)
    stamp = completed_at or timeutil.now_iso()
    day_key = stamp[:10]

    task.status = "done"
    task.completed_at = stamp
    task.completion_note = note
    task.actual_minutes = actual_minutes
    task.exp_reward = rewards["exp"]
    task.coin_reward = rewards["coins"]
    task.completion_meta = {
        "rewards": rewards,
        "completed_at": stamp,
        "actual_minutes": actual_minutes,
        "credited_minutes": task_credited_minutes(task, actual_minutes),
        "category": task.category,
        "difficulty": task.difficulty,
    }
    task.updated_at = timeutil.now_iso()
    db.add(task)

    add_exp(profile, rewards["exp"])
    profile.coins = int(profile.coins or 0) + int(rewards["coins"])
    add_attribute(profile, task.attribute, rewards["attribute_delta"])
    profile.total_tasks_done = int(profile.total_tasks_done or 0) + 1

    minutes = int(task.completion_meta["credited_minutes"])
    if task.category == "study":
        profile.total_study_minutes = int(profile.total_study_minutes or 0) + minutes
        bump_daily_stat(db, day_key, study_minutes=minutes, tasks_done=1)
    else:
        bump_daily_stat(db, day_key, tasks_done=1)

    recompute_streak(db, profile)
    profile.updated_at = timeutil.now_iso()
    db.flush()
    return {"rewards": rewards, "already_done": False}


def reopen_task(db: Session, task: Task, profile: Profile) -> dict:
    """回退任务完成状态与奖励（只允许当天完成的任务，由路由层校验）。"""
    meta = task.completion_meta or {}
    rewards = meta.get("rewards") or build_reward_payload(task, profile.streak)
    stamp = task.completed_at or timeutil.now_iso()
    day_key = stamp[:10]
    category = meta.get("category") or task.category
    minutes = int(meta.get("credited_minutes") or 0)

    remove_exp(profile, int(rewards.get("exp") or 0))
    profile.coins = max(0, int(profile.coins or 0) - int(rewards.get("coins") or 0))
    remove_attribute(profile, task.attribute, int(rewards.get("attribute_delta") or 0))
    profile.total_tasks_done = max(0, int(profile.total_tasks_done or 0) - 1)
    if category == "study":
        profile.total_study_minutes = max(0, int(profile.total_study_minutes or 0) - minutes)
        bump_daily_stat(db, day_key, study_minutes=-minutes, tasks_done=-1)
    else:
        bump_daily_stat(db, day_key, tasks_done=-1)

    task.status = "pending"
    task.completed_at = None
    task.completion_note = None
    task.actual_minutes = None
    task.completion_meta = None
    task.exp_reward = compute_rewards(task.difficulty, 0)["exp"]
    task.coin_reward = compute_rewards(task.difficulty, 0)["coins"]
    task.updated_at = timeutil.now_iso()
    db.add(task)

    recompute_streak(db, profile)
    profile.updated_at = timeutil.now_iso()
    db.flush()
    return {"reverted_rewards": rewards, "day": day_key}


# ---------------------------------------------------------------------------
# 杂项
# ---------------------------------------------------------------------------
def new_id() -> str:
    return str(uuid.uuid4())


def add_notification(
    db: Session,
    title: str,
    content: str,
    *,
    kind: str = "system",
) -> Notification:
    row = Notification(
        id=new_id(),
        title=title,
        content=content,
        kind=kind,
        read=False,
        created_at=timeutil.now_iso(),
    )
    db.add(row)
    return row


def device_is_online(last_seen: str | None) -> bool:
    if not last_seen:
        return False
    try:
        moment = timeutil.parse_dt(last_seen)
    except Exception:
        return False
    if moment is None:
        return False
    return (timeutil.now() - moment).total_seconds() <= settings.online_seconds
