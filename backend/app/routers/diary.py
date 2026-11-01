"""日记：PUT upsert / PATCH / Hermes 写点评 / 列表。"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import timeutil
from ..db import get_db
from ..errors import ApiError
from ..models import DiaryEntry
from ..responses import envelope
from ..schemas import DiaryComment, DiaryPatch, DiaryPut, diary_dict
from ..services import audit
from .deps import actor, check_int, check_limit, optional_day, parse_day

router = APIRouter(tags=["diary"])


def _clean_tags(value: list[str] | None) -> list[str]:
    if not value:
        return []
    tags: list[str] = []
    for item in value:
        if not isinstance(item, str):
            raise ApiError("invalid_argument", "tags 必须为字符串数组")
        text = item.strip()
        if not text:
            continue
        if len(text) > 30:
            raise ApiError("invalid_argument", "单个 tag 长度不能超过 30 个字符")
        if text not in tags:
            tags.append(text)
    if len(tags) > 10:
        raise ApiError("invalid_argument", "tags 最多 10 个")
    return tags


def _validate_mood(value: object) -> int:
    return check_int(value, 1, 5, field="mood")


def _get_entry(db: Session, day: str) -> DiaryEntry:
    row = db.get(DiaryEntry, day)
    if row is None:
        raise ApiError("not_found", "当天没有日记", 404)
    return row


@router.get("/diary")
def get_diary(
    date: str | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    day = optional_day(date) or timeutil.today_str()
    row = db.get(DiaryEntry, day)
    return envelope(diary_dict(row) if row else None)


@router.get("/diary/entries")
def list_diary(
    from_date: str | None = Query(default=None, alias="from"),
    to_date: str | None = Query(default=None, alias="to"),
    limit: int | None = Query(default=None),
    db: Session = Depends(get_db),
) -> dict:
    max_rows = check_limit(limit, default=100)
    stmt = select(DiaryEntry)
    begin = optional_day(from_date, field="from")
    if begin:
        stmt = stmt.where(DiaryEntry.date >= begin)
    finish = optional_day(to_date, field="to")
    if finish:
        stmt = stmt.where(DiaryEntry.date <= finish)
    rows = db.scalars(stmt.order_by(DiaryEntry.date.desc()).limit(max_rows)).all()
    return envelope([diary_dict(row) for row in rows])


@router.put("/diary/{date}")
def put_diary(
    date: str,
    body: DiaryPut,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    day = parse_day(date)
    content = (body.content or "").strip()
    if not content:
        raise ApiError("invalid_argument", "content 不能为空")
    if len(content) > 20000:
        raise ApiError("invalid_argument", "content 过长（最多 20000 字）")
    mood = _validate_mood(body.mood) if body.mood is not None else None
    tags = _clean_tags(body.tags)

    row = db.get(DiaryEntry, day)
    stamp = timeutil.now_iso()
    if row is None:
        row = DiaryEntry(
            date=day,
            mood=mood,
            content=content,
            ai_comment=None,
            tags=tags,
            created_at=stamp,
            updated_at=stamp,
        )
        db.add(row)
        action = "diary.create"
    else:
        row.content = content
        if body.mood is not None:
            row.mood = mood
        if body.tags is not None:
            row.tags = tags
        row.updated_at = stamp
        action = "diary.update"

    audit.log(db, action, actor=act, target_type="diary", target_id=day, detail={"mood": mood})
    db.commit()
    return envelope(diary_dict(row))


@router.patch("/diary/{date}")
def patch_diary(
    date: str,
    body: DiaryPatch,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    day = parse_day(date)
    row = _get_entry(db, day)
    changed: dict = {}
    fields = body.model_fields_set
    if "content" in fields and body.content is not None:
        content = body.content.strip()
        if not content:
            raise ApiError("invalid_argument", "content 不能为空")
        if len(content) > 20000:
            raise ApiError("invalid_argument", "content 过长（最多 20000 字）")
        row.content = content
        changed["content"] = True
    if "mood" in fields and body.mood is not None:
        row.mood = _validate_mood(body.mood)
        changed["mood"] = row.mood
    if "tags" in fields:
        row.tags = _clean_tags(body.tags)
        changed["tags"] = row.tags
    row.updated_at = timeutil.now_iso()
    audit.log(db, "diary.update", actor=act, target_type="diary", target_id=day, detail=changed)
    db.commit()
    return envelope(diary_dict(row))


@router.post("/diary/{date}/comment")
def comment_diary(
    date: str,
    body: DiaryComment,
    db: Session = Depends(get_db),
    act: str = Depends(actor),
) -> dict:
    day = parse_day(date)
    text = (body.ai_comment or "").strip()
    if not text:
        raise ApiError("invalid_argument", "ai_comment 不能为空")
    row = _get_entry(db, day)
    row.ai_comment = text
    row.updated_at = timeutil.now_iso()
    audit.log(db, "diary.comment", actor=act, target_type="diary", target_id=day, detail={})
    db.commit()
    return envelope(diary_dict(row))
