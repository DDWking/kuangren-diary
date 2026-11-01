"""娱乐额度与策略：策略硬底线校验、grant/consume/lock/unlock、申请与审批。"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import Grant, Policy, QuotaRequest
from ..responses import envelope
from ..schemas import (
    ConsumeBody,
    DecideBody,
    GrantBody,
    LockBody,
    PolicyPut,
    QuotaRequestBody,
    UnlockBody,
    quota_request_dict,
)
from ..services import audit, rules, settlement
from .deps import VALID_ACTORS, actor, check_int, check_limit, optional_day, optional_iso, parse_day

router = APIRouter(tags=["quota"])

REQUEST_STATUSES = ("pending", "approved", "rejected")


# ---------------------------------------------------------------------------
# 查询
# ---------------------------------------------------------------------------
@router.get("/quota")
def get_quota(
    date: str | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    settlement.ensure_day(db)
    day = optional_day(date) or timeutil.today_str()
    return envelope(rules.build_quota(db, day))


@router.get("/policy")
def get_policy(
    date: str | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    day = optional_day(date) or timeutil.today_str()
    return envelope(rules.get_policy(db, day))


@router.get("/policies")
def list_policies(
    limit: int | None = Query(default=20),
    db: Session = Depends(get_db),
) -> dict:
    max_rows = check_limit(limit, default=20)
    rows = db.scalars(
        select(Policy).order_by(Policy.updated_at.desc()).limit(max_rows)
    ).all()
    return envelope([rules.policy_to_dict(row) for row in rows])


# ---------------------------------------------------------------------------
# 硬底线 2：03:00-07:00 必禁，curfew 不可缩短
# ---------------------------------------------------------------------------
def _merge_policy(current: dict, body: PolicyPut) -> dict:
    merged = dict(current)
    fields = body.model_fields_set
    if "daily_base_minutes" in fields and body.daily_base_minutes is not None:
        merged["daily_base_minutes"] = body.daily_base_minutes
    if "curfew_start" in fields and body.curfew_start is not None:
        merged["curfew_start"] = body.curfew_start
    if "curfew_end" in fields and body.curfew_end is not None:
        merged["curfew_end"] = body.curfew_end
    if "session_max_minutes" in fields and body.session_max_minutes is not None:
        merged["session_max_minutes"] = body.session_max_minutes
    if "blacklist" in fields and body.blacklist is not None:
        merged["blacklist"] = body.blacklist
    if "notes" in fields:
        merged["notes"] = body.notes
    return merged


def _validate_policy_values(merged: dict) -> None:
    base = merged.get("daily_base_minutes")
    if base is not None:
        if not isinstance(base, int) or base < 0 or base > 480:
            raise ApiError("invalid_argument", "daily_base_minutes 范围应为 0–480")
    session = merged.get("session_max_minutes")
    if session is not None:
        if not isinstance(session, int) or session < 20 or session > 180:
            raise ApiError("invalid_argument", "session_max_minutes 范围应为 20–180")

    try:
        start = timeutil.parse_hhmm(merged.get("curfew_start"))
        end = timeutil.parse_hhmm(merged.get("curfew_end"))
    except ValueError as exc:
        raise ApiError("invalid_argument", "curfew_start/curfew_end 格式应为 HH:MM") from exc

    hard_start = timeutil.parse_hhmm(rules.settings.hard_curfew_start)
    hard_end = timeutil.parse_hhmm(rules.settings.hard_curfew_end)

    # 明确的可读错误：开始晚于 03:00 或结束早于 07:00 都属于触碰硬底线
    start_minutes = timeutil.minutes_of_day(start)
    end_minutes = timeutil.minutes_of_day(end)
    hard_start_minutes = timeutil.minutes_of_day(hard_start)
    hard_end_minutes = timeutil.minutes_of_day(hard_end)

    violation_message = (
        "触碰硬底线：03:00–07:00 必须禁止娱乐，curfew_start 不得晚于 03:00、"
        "curfew_end 不得早于 07:00（Hermes 不能缩短硬宵禁窗口）"
    )
    if start_minutes > hard_start_minutes:
        raise ApiError("policy_violation", violation_message, 422)
    if end_minutes < hard_end_minutes:
        raise ApiError("policy_violation", violation_message, 422)
    # 额外兜底：整个 03:00–07:00 必须落在宵禁窗口内（含跨午夜写法）
    if not timeutil.window_covers(start, end, hard_start, hard_end):
        raise ApiError("policy_violation", violation_message, 422)

    blacklist = merged.get("blacklist")
    if blacklist is not None:
        if not isinstance(blacklist, list):
            raise ApiError("invalid_argument", "blacklist 必须为字符串数组")
        cleaned: list[str] = []
        for item in blacklist:
            if not isinstance(item, str) or not item.strip():
                raise ApiError("invalid_argument", "blacklist 必须为字符串数组")
            if len(item) > 100:
                raise ApiError("invalid_argument", "blacklist 单项最多 100 个字符")
            cleaned.append(item.strip())
        if len(cleaned) > 50:
            raise ApiError("invalid_argument", "blacklist 最多 50 项")
        merged["blacklist"] = cleaned

    notes = merged.get("notes")
    if notes is not None and len(str(notes)) > 2000:
        raise ApiError("invalid_argument", "notes 最多 2000 个字符")


@router.put("/policy")
def put_policy(
    body: PolicyPut,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    day = parse_day(body.date, field="date") if body.date else timeutil.today_str()
    current = rules.get_policy(db, day)
    merged = _merge_policy(current, body)
    _validate_policy_values(merged)

    stamp = timeutil.now_iso()
    row = db.get(Policy, day)
    if row is None:
        row = Policy(
            date=day,
            daily_base_minutes=int(merged["daily_base_minutes"]),
            curfew_start=str(merged["curfew_start"]),
            curfew_end=str(merged["curfew_end"]),
            session_max_minutes=int(merged["session_max_minutes"]),
            blacklist=list(merged.get("blacklist") or []),
            notes=merged.get("notes"),
            source=("hermes" if act != "system" else "system"),
            updated_by=act,
            updated_at=stamp,
        )
        db.add(row)
    else:
        row.daily_base_minutes = int(merged["daily_base_minutes"])
        row.curfew_start = str(merged["curfew_start"])
        row.curfew_end = str(merged["curfew_end"])
        row.session_max_minutes = int(merged["session_max_minutes"])
        row.blacklist = list(merged.get("blacklist") or [])
        row.notes = merged.get("notes")
        row.source = "hermes" if act != "system" else "system"
        row.updated_by = act
        row.updated_at = stamp

    audit.log(
        db,
        "policy.update",
        actor=act,
        target_type="policy",
        target_id=day,
        detail={
            "before": {
                "daily_base_minutes": current.get("daily_base_minutes"),
                "curfew_start": current.get("curfew_start"),
                "curfew_end": current.get("curfew_end"),
                "session_max_minutes": current.get("session_max_minutes"),
            },
            "after": {
                "daily_base_minutes": int(merged["daily_base_minutes"]),
                "curfew_start": str(merged["curfew_start"]),
                "curfew_end": str(merged["curfew_end"]),
                "session_max_minutes": int(merged["session_max_minutes"]),
            },
            "reason": (body.reason or "").strip() or None,
        },
    )
    db.commit()
    return envelope(rules.get_policy(db, day))


# ---------------------------------------------------------------------------
# grant / consume / lock / unlock
# ---------------------------------------------------------------------------
@router.post("/quota/grant")
def grant_quota(
    body: GrantBody,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    settlement.ensure_day(db)
    day = timeutil.today_str()
    minutes = check_int(body.minutes, 1, rules.settings.max_grant_minutes, field="minutes")
    reason = (body.reason or "").strip()
    if not reason or len(reason) > 200:
        raise ApiError("invalid_argument", "reason 长度应为 1–200 个字符")
    grant_actor = (body.actor or act).strip().lower()
    if grant_actor not in VALID_ACTORS:
        raise ApiError("invalid_argument", "actor 应为 app/pc/hermes/system")
    expires_at = optional_iso(body.expires_at, field="expires_at")
    if expires_at is not None and expires_at <= timeutil.now_iso():
        raise ApiError("invalid_argument", "expires_at 必须晚于当前时间")

    row = Grant(
        id=rules.new_id(),
        date=day,
        minutes=minutes,
        reason=reason,
        actor=grant_actor,
        expires_at=expires_at,
        revoked=False,
        created_at=timeutil.now_iso(),
    )
    db.add(row)
    audit.log(
        db,
        "quota.grant",
        actor=act,
        target_type="quota",
        target_id=day,
        detail={"minutes": minutes, "reason": reason, "expires_at": expires_at},
    )
    db.commit()
    return envelope(rules.build_quota(db, day))


@router.post("/quota/consume")
def consume_quota(
    body: ConsumeBody,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    settlement.ensure_day(db)
    day = timeutil.today_str()
    minutes = check_int(body.minutes, 1, rules.settings.max_consume_minutes, field="minutes")
    source = (body.source or "pc").strip()
    if not source or len(source) > 20:
        raise ApiError("invalid_argument", "source 长度应为 1–20 个字符")

    profile = rules.get_profile(db)
    quota = rules.build_quota(db, day, profile)
    if quota["locked"]:
        reason = quota["lock_reason"] or "locked"
        if reason == "curfew":
            raise ApiError(
                "policy_violation",
                "触碰硬底线：当前处于宵禁时段（03:00–07:00 必禁娱乐），不允许使用娱乐额度",
                422,
            )
        raise ApiError("policy_violation", f"当前额度已锁定（{reason}），不允许使用娱乐额度", 422)
    if int(quota["remaining_minutes"]) < minutes:
        raise ApiError(
            "policy_violation",
            f"娱乐额度不足：剩余 {quota['remaining_minutes']} 分钟，本次 {minutes} 分钟",
            422,
        )

    row = rules.quota_row(db, day)
    row.used_minutes = int(row.used_minutes or 0) + minutes
    row.updated_at = timeutil.now_iso()
    rules.bump_daily_stat(db, day, quota_used=minutes)
    audit.log(
        db,
        "quota.consume",
        actor=act,
        target_type="quota",
        target_id=day,
        detail={
            "minutes": minutes,
            "source": source,
            "device_id": body.device_id,
            "detail": body.detail,
        },
    )
    db.commit()

    updated = rules.build_quota(db, day, profile)
    if updated["remaining_minutes"] <= 0:
        rules.add_notification(
            db,
            "今日娱乐额度已用尽",
            "剩余 0 分钟。请遵守规则，把时间留给学习。",
            kind="quota",
        )
        db.commit()
        updated = rules.build_quota(db, day, profile)
    return envelope(updated)


@router.post("/quota/lock")
def lock_quota(
    body: LockBody,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    settlement.ensure_day(db)
    day = timeutil.today_str()
    reason = (body.reason or "").strip()
    if not reason or len(reason) > 200:
        raise ApiError("invalid_argument", "reason 长度应为 1–200 个字符")
    until = optional_iso(body.until, field="until")
    row = rules.quota_row(db, day)
    row.locked = True
    row.lock_reason = reason
    row.lock_until = until
    row.updated_at = timeutil.now_iso()
    audit.log(
        db,
        "quota.lock",
        actor=act,
        target_type="quota",
        target_id=day,
        detail={"reason": reason, "until": until, "device_id": body.device_id},
    )
    rules.add_notification(db, "娱乐额度已锁定", reason, kind="quota")
    db.commit()
    return envelope(rules.build_quota(db, day))


@router.post("/quota/unlock")
def unlock_quota(
    body: UnlockBody,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    settlement.ensure_day(db)
    day = timeutil.today_str()
    reason = (body.reason or "").strip()
    if not reason or len(reason) > 200:
        raise ApiError("invalid_argument", "reason 长度应为 1–200 个字符")
    row = rules.quota_row(db, day)
    row.locked = False
    row.lock_reason = None
    row.lock_until = None
    row.updated_at = timeutil.now_iso()
    audit.log(
        db,
        "quota.unlock",
        actor=act,
        target_type="quota",
        target_id=day,
        detail={"reason": reason},
    )
    db.commit()
    # 注意：解除显式锁定后，宵禁/额度用尽仍会由 build_quota 计算为 locked
    return envelope(rules.build_quota(db, day))


# ---------------------------------------------------------------------------
# 额度申请与审批
# ---------------------------------------------------------------------------
@router.post("/quota/requests")
def create_quota_request(
    body: QuotaRequestBody,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    settlement.ensure_day(db)
    day = timeutil.today_str()
    minutes = check_int(body.minutes, 1, rules.settings.max_grant_minutes, field="minutes")
    reason = (body.reason or "").strip()
    if not reason or len(reason) > 200:
        raise ApiError("invalid_argument", "reason 长度应为 1–200 个字符")

    row = QuotaRequest(
        id=rules.new_id(),
        date=day,
        minutes=minutes,
        reason=reason,
        device_id=(body.device_id or "").strip() or None,
        status="pending",
        created_at=timeutil.now_iso(),
    )
    db.add(row)
    audit.log(
        db,
        "quota.request",
        actor=act,
        target_type="quota_request",
        target_id=row.id,
        detail={"minutes": minutes, "reason": reason},
    )
    rules.add_notification(db, "收到额度申请", f"申请 {minutes} 分钟：{reason}", kind="quota")
    db.commit()
    return envelope(quota_request_dict(row))


@router.get("/quota/requests")
def list_quota_requests(
    status: str | None = Query(default=None),
    limit: int | None = Query(default=20),
    db: Session = Depends(get_db),
) -> dict:
    max_rows = check_limit(limit, default=20)
    stmt = select(QuotaRequest)
    if status:
        if status not in REQUEST_STATUSES:
            raise ApiError("invalid_argument", f"status 应为 {'/'.join(REQUEST_STATUSES)}")
        stmt = stmt.where(QuotaRequest.status == status)
    rows = db.scalars(stmt.order_by(QuotaRequest.created_at.desc()).limit(max_rows)).all()
    return envelope([quota_request_dict(row) for row in rows])


@router.post("/quota/requests/{request_id}/decide")
def decide_quota_request(
    request_id: str,
    body: DecideBody,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    row = db.get(QuotaRequest, request_id)
    if row is None:
        raise ApiError("not_found", "额度申请不存在", 404)
    if row.status != "pending":
        raise ApiError("conflict", "该申请已经处理过", 409)

    day = row.date or timeutil.today_str()
    note = (body.note or "").strip() or None
    stamp = timeutil.now_iso()
    if body.approve:
        minutes = check_int(
            body.minutes if body.minutes is not None else row.minutes,
            1,
            rules.settings.max_grant_minutes,
            field="minutes",
        )
        db.add(
            Grant(
                id=rules.new_id(),
                date=day,
                minutes=minutes,
                reason=f"批准额度申请：{row.reason}",
                actor="hermes",
                expires_at=timeutil.end_of_day_iso(timeutil.parse_date(day)),
                revoked=False,
                created_at=stamp,
            )
        )
        row.status = "approved"
        row.minutes = minutes
    else:
        row.status = "rejected"
    row.decided_by = act
    row.decision_note = note
    row.decided_at = stamp
    audit.log(
        db,
        "quota.request_decide",
        actor=act,
        target_type="quota_request",
        target_id=row.id,
        detail={"approve": bool(body.approve), "minutes": int(row.minutes), "note": note},
    )
    rules.add_notification(
        db,
        "额度申请已处理",
        f"{'已批准' if body.approve else '已拒绝'}：{row.reason}",
        kind="quota",
    )
    db.commit()
    return envelope(
        {
            "request": quota_request_dict(row),
            "quota": rules.build_quota(db, day),
        }
    )
