"""三条硬底线（docs/spec.md）必须有代码级拦截，这里逐条验证。"""

from __future__ import annotations

from datetime import timedelta

from app import timeutil
from app.models import DailyStat, Profile
from helpers import HEADERS, complete, create_task, fail, ok, profile, quota


def test_hard_line_1_audit_is_append_only(client, freeze_time):
    freeze_time()
    task = create_task(client)
    ok(complete(client, task["id"]), 200)
    ok(client.delete(f"/api/v1/tasks/{task['id']}", headers=HEADERS), 200)

    before = ok(client.get("/api/v1/export", headers=HEADERS))["audit_logs"]
    actions = [row["action"] for row in before]
    assert "task.create" in actions
    assert "task.complete" in actions
    assert "task.delete" in actions

    for url in (
        "/api/v1/audit_logs",
        "/api/v1/audit_logs/anything",
        "/api/v1/events/anything",
        "/api/v1/policies/2026-12-19",
    ):
        response = client.delete(url, headers=HEADERS)
        assert response.status_code == 404, (url, response.status_code, response.text)
        body = response.json()
        assert body["ok"] is False
        assert body["error"]["code"] == "not_found"

    create_task(client)
    after = ok(client.get("/api/v1/export", headers=HEADERS))["audit_logs"]
    assert len(after) >= len(before)
    assert len({row["id"] for row in after}) == len(after)
    assert any(row["action"] == "task.create" for row in after)

    spec = client.get("/api/v1/openapi.json").json()
    for path, methods in spec["paths"].items():
        assert "audit" not in path.lower(), path
        if path in ("/api/v1/events", "/api/v1/policies", "/api/v1/devices"):
            assert "delete" not in methods, path


def test_hard_line_2_curfew_cannot_be_shortened(client):
    cases = [
        {"curfew_start": "05:00", "curfew_end": "07:00"},
        {"curfew_start": "03:00", "curfew_end": "06:59"},
        {"curfew_start": "03:01"},
        {"curfew_end": "06:00"},
        {"curfew_start": "04:00", "curfew_end": "06:00"},
    ]
    for payload in cases:
        error = fail(client.put("/api/v1/policy", json=payload, headers=HEADERS), 422, "policy_violation")
        assert "硬底线" in error["message"]
    current = ok(client.get("/api/v1/policy", headers=HEADERS))
    assert (current["curfew_start"], current["curfew_end"]) == ("01:00", "07:00")
    assert current["source"] == "fallback"


def test_hard_line_2_curfew_blocks_quota_use(client, freeze_time):
    freeze_time(hour=4, minute=30)
    data = quota(client)
    assert data["locked"] is True
    assert data["lock_reason"] == "curfew"
    error = fail(
        client.post("/api/v1/quota/consume", json={"minutes": 10, "source": "pc"}, headers=HEADERS),
        422,
        "policy_violation",
    )
    assert "宵禁" in error["message"]


def test_hard_line_2_curfew_early_morning(client, freeze_time):
    ok(
        client.put(
            "/api/v1/policy",
            json={"curfew_start": "00:30", "curfew_end": "08:00"},
            headers=HEADERS,
        )
    )
    freeze_time(hour=0, minute=45)
    data = quota(client)
    assert data["locked"] is True
    assert data["lock_reason"] == "curfew"
    freeze_time(hour=12)
    data = quota(client)
    assert data["locked"] is False


def _backdate_profile(db, days: int) -> None:
    row = db.get(Profile, "default")
    assert row is not None
    row.created_at = timeutil.to_iso(timeutil.now() - timedelta(days=days))
    db.commit()


def test_hard_line_3_new_account_not_tightened(client, freeze_time):
    freeze_time()
    data = quota(client)
    assert data["forced_tighten"] is False
    assert data["base_minutes"] == 180


def test_hard_line_3_three_zero_study_days_half_quota(client, db, freeze_time):
    freeze_time()
    profile(client)
    _backdate_profile(db, 5)

    data = quota(client)
    assert data["forced_tighten"] is True
    assert "连续 3 个自然日零学习" in data["tighten_reason"]
    assert data["base_minutes"] == 90
    assert data["remaining_minutes"] == 90

    ok(
        client.post("/api/v1/quota/consume", json={"minutes": 60, "source": "pc"}, headers=HEADERS),
        200,
    )
    fail(
        client.post("/api/v1/quota/consume", json={"minutes": 31, "source": "pc"}, headers=HEADERS),
        422,
        "policy_violation",
    )


def test_hard_line_3_any_study_within_three_days_prevents_tighten(client, db, freeze_time):
    freeze_time()
    profile(client)
    _backdate_profile(db, 5)
    day = timeutil.date_str(timeutil.today() - timedelta(days=2))
    db.add(DailyStat(date=day, study_minutes=10, tasks_done=1, quota_used=0))
    db.commit()

    data = quota(client)
    assert data["forced_tighten"] is False
    assert data["base_minutes"] == 180


def test_hard_line_3_released_after_study(client, db, freeze_time):
    freeze_time()
    profile(client)
    _backdate_profile(db, 5)
    assert quota(client)["forced_tighten"] is True

    task = create_task(client, category="study")
    ok(complete(client, task["id"], actual_minutes=20), 200)

    data = quota(client)
    assert data["forced_tighten"] is False
    assert data["tighten_reason"] is None
    assert data["base_minutes"] == 180
