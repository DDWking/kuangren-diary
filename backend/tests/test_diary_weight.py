"""日记 upsert/PATCH/点评，体重每日一条/删除/变化。"""

from __future__ import annotations

from datetime import timedelta

from app import timeutil
from helpers import HEADERS, auth, fail, ok


def test_diary_upsert_patch_and_comment(client):
    day = timeutil.today_str()
    created = ok(
        client.put(
            f"/api/v1/diary/{day}",
            json={"mood": 4, "content": "今天学了 3 小时", "tags": ["学习", "学习", "跑步"]},
            headers=HEADERS,
        )
    )
    assert created["date"] == day and created["mood"] == 4
    assert created["tags"] == ["学习", "跑步"]
    assert created["ai_comment"] is None

    fetched = ok(client.get("/api/v1/diary", params={"date": day}, headers=HEADERS))
    assert fetched["content"] == "今天学了 3 小时"

    # PUT 覆盖为 upsert：保留 created_at，更新 updated_at
    updated = ok(
        client.put(
            f"/api/v1/diary/{day}",
            json={"mood": 5, "content": "改成晚上写", "tags": ["夜"]},
            headers=HEADERS,
        )
    )
    assert updated["created_at"] == created["created_at"]
    assert updated["mood"] == 5 and updated["content"] == "改成晚上写"

    patched = ok(client.patch(f"/api/v1/diary/{day}", json={"mood": 2}, headers=HEADERS))
    assert patched["mood"] == 2 and patched["content"] == "改成晚上写"

    commented = ok(
        client.post(
            f"/api/v1/diary/{day}/comment",
            json={"ai_comment": "别熬夜，明天继续。"},
            headers=auth(**{"X-Actor": "hermes"}),
        )
    )
    assert commented["ai_comment"] == "别熬夜，明天继续。"

    entries = ok(client.get("/api/v1/diary/entries", headers=HEADERS))
    assert len(entries) == 1 and entries[0]["date"] == day


def test_diary_missing_and_validation(client):
    day = timeutil.today_str()
    response = client.get("/api/v1/diary", params={"date": "2000-01-01"}, headers=HEADERS)
    assert response.status_code == 200 and response.json()["data"] is None
    fail(client.get("/api/v1/diary", params={"date": "bad-date"}, headers=HEADERS), 400, "invalid_argument")
    fail(client.put(f"/api/v1/diary/{day}", json={"content": "   "}, headers=HEADERS), 400, "invalid_argument")
    fail(client.put(f"/api/v1/diary/{day}", json={"content": "x", "mood": 9}, headers=HEADERS), 400, "invalid_argument")
    fail(client.patch(f"/api/v1/diary/2000-01-01", json={"mood": 3}, headers=HEADERS), 404, "not_found")
    fail(
        client.post(f"/api/v1/diary/{day}/comment", json={"ai_comment": "x"}, headers=HEADERS),
        404,
        "not_found",
    )


def test_weight_daily_upsert_and_delete(client):
    today = timeutil.today_str()
    baseline_day = timeutil.date_str(timeutil.today() - timedelta(days=8))

    ok(client.put(f"/api/v1/weight/{baseline_day}", json={"weight_kg": 72.0}, headers=HEADERS))
    created = ok(
        client.put(
            f"/api/v1/weight/{today}",
            json={"weight_kg": 70.5, "body_fat": 20.1, "waist_cm": 88.0, "note": "晨起", "source": "scale"},
            headers=HEADERS,
        )
    )
    assert created["weight_kg"] == 70.5 and created["source"] == "scale"

    summary = ok(client.get("/api/v1/weight", params={"days": 30}, headers=HEADERS))
    assert len(summary["entries"]) == 2
    assert summary["latest"]["date"] == today
    assert summary["change_7d"] == -1.5

    # 同一天再 PUT 是覆盖而不是新增
    replaced = ok(client.put(f"/api/v1/weight/{today}", json={"weight_kg": 70.0}, headers=HEADERS))
    assert replaced["date"] == today
    summary = ok(client.get("/api/v1/weight", headers=HEADERS))
    assert len(summary["entries"]) == 2
    assert summary["latest"]["weight_kg"] == 70.0

    assert ok(client.delete(f"/api/v1/weight/{today}", headers=HEADERS)) == {"deleted": True}
    summary = ok(client.get("/api/v1/weight", headers=HEADERS))
    assert len(summary["entries"]) == 1 and summary["latest"]["date"] == baseline_day


def test_weight_validation(client):
    today = timeutil.today_str()
    fail(client.put(f"/api/v1/weight/{today}", json={"weight_kg": 500}, headers=HEADERS), 400, "invalid_argument")
    fail(client.put(f"/api/v1/weight/{today}", json={"weight_kg": 70, "source": "robot"}, headers=HEADERS), 400, "invalid_argument")
    fail(client.put(f"/api/v1/weight/{today}", json={"weight_kg": 70, "body_fat": 120}, headers=HEADERS), 400, "invalid_argument")
    fail(client.delete(f"/api/v1/weight/{today}", headers=HEADERS), 404, "not_found")
    fail(client.get("/api/v1/weight", params={"days": 0}, headers=HEADERS), 400, "invalid_argument")
