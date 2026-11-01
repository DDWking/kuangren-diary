"""体重：每日一条 upsert / 查询 / 删除。"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import WeightEntry
from ..responses import envelope
from ..schemas import WeightPut, weight_dict
from ..services import analytics, audit
from .deps import actor, check_int, parse_day

router = APIRouter(tags=["weight"])

SOURCES = ("manual", "hermes", "scale")


def _float_range(value: object, low: float, high: float, *, field: str) -> float:
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError) as exc:
        raise ApiError("invalid_argument", f"{field} 应为数字") from exc
    if number < low or number > high:
        raise ApiError("invalid_argument", f"{field} 范围应为 {low}–{high}")
    return round(number, 2)


@router.get("/weight")
def get_weight(
    days: int | None = Query(default=30),
    db: Session = Depends(get_db),
) -> dict:
    window = check_int(days, 1, 365, field="days")
    return envelope(analytics.weight_summary(db, days=window))


@router.put("/weight/{date}")
def put_weight(
    date: str,
    body: WeightPut,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    day = parse_day(date)
    weight = _float_range(body.weight_kg, 20, 300, field="weight_kg")
    body_fat = _float_range(body.body_fat, 0, 80, field="body_fat") if body.body_fat is not None else None
    waist = _float_range(body.waist_cm, 30, 200, field="waist_cm") if body.waist_cm is not None else None
    source = (body.source or "manual").strip()
    if source not in SOURCES:
        raise ApiError("invalid_argument", f"source 应为 {'/'.join(SOURCES)}")
    note = body.note
    if note is not None and len(note) > 200:
        raise ApiError("invalid_argument", "note 最多 200 个字符")

    stamp = timeutil.now_iso()
    row = db.get(WeightEntry, day)
    if row is None:
        row = WeightEntry(
            date=day,
            weight_kg=weight,
            body_fat=body_fat,
            waist_cm=waist,
            note=note,
            source=source,
            created_at=stamp,
            updated_at=stamp,
        )
        db.add(row)
        action = "weight.create"
    else:
        row.weight_kg = weight
        row.body_fat = body_fat
        row.waist_cm = waist
        row.note = note
        row.source = source
        row.updated_at = stamp
        action = "weight.update"
    audit.log(
        db,
        action,
        actor=act,
        target_type="weight",
        target_id=day,
        detail={"weight_kg": weight},
    )
    db.commit()
    return envelope(weight_dict(row))


@router.delete("/weight/{date}")
def delete_weight(
    date: str,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    day = parse_day(date)
    row = db.get(WeightEntry, day)
    if row is None:
        raise ApiError("not_found", "当天没有体重记录", 404)
    audit.log(
        db,
        "weight.delete",
        actor=act,
        target_type="weight",
        target_id=day,
        detail={"weight_kg": float(row.weight_kg)},
    )
    db.delete(row)
    db.commit()
    return envelope({"deleted": True})
