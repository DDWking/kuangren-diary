"""策略与娱乐额度：硬底线 2（03:00–07:00 必禁）、grant/consume/lock/unlock/requests。"""

from __future__ import annotations

from datetime import timedelta

from app import timeutil
from helpers import HEADERS, fail, ok, quota


def put_policy(client, **overrides):
    payload = {"reason": "测试调整"}
    payload.update(overrides)
    return client.put("/api/v1/policy", json=payload, headers=HEADERS)


def consume(client, minutes, source="pc"):
    return client.post(
        "/api/v1/quota/consume",
        json={"minutes": minutes, "source": source},
        headers=HEADERS,
    )


def grant(client, minutes, **overrides):
    payload = {"minutes": minutes, "reason": "测试补时"}
    payload.update(overrides)
    return client.post("/api/v1/quota/grant", json=payload, headers=HEADERS)


def test_policy_fallback_defaults(client):
    data = ok(client.get("/api/v1/policy", headers=HEADERS))
    assert data["date"] == timeutil.today_str()
    assert data["source"] == "fallback"
    assert data["daily_base_minutes"] == 180
    assert data["curfew_start"] == "01:00"
    assert data["curfew_end"] == "07:00"
    assert data["session_max_minutes"] == 60
    assert "steam.exe" in data["blacklist"]
    assert data["updated_by"] == "system"


def test_quota_defaults(client, freeze_time):
    freeze_time()
    data = quota(client)
    assert data["base_minutes"] == 180
    assert data["bonus_minutes"] == 0
    assert data["used_minutes"] == 0
    assert data["remaining_minutes"] == 180
    assert data["locked"] is False
    assert data["extensions"] == []
    assert data["forced_tighten"] is False
    assert data["policy"]["source"] == "fallback"


def test_policy_update_history_and_audit(client):
    data = ok(
        put_policy(
            client,
            daily_base_minutes=240,
            curfew_start="02:00",
            curfew_end="08:00",
            session_max_minutes=90,
            blacklist=["game.exe"],
            notes="周末放宽",
        )
    )
    assert data["daily_base_minutes"] == 240
    assert data["curfew_start"] == "02:00"
    assert data["curfew_end"] == "08:00"
    assert data["session_max_minutes"] == 90
    assert data["blacklist"] == ["game.exe"]
    assert data["notes"] == "周末放宽"
    assert data["source"] == "hermes"
    assert data["updated_by"] == "app"

    current = ok(client.get("/api/v1/policy", headers=HEADERS))
    assert current["daily_base_minutes"] == 240

    history = ok(client.get("/api/v1/policies?limit=20", headers=HEADERS))
    assert isinstance(history, list) and history
    assert any(row["daily_base_minutes"] == 240 for row in history)

    export = ok(client.get("/api/v1/export", headers=HEADERS))
    assert any(log["action"] == "policy.update" for log in export["audit_logs"])


def test_policy_cannot_shorten_curfew(client):
    cases = [
        {"curfew_start": "05:00"},
        {"curfew_start": "03:30"},
        {"curfew_end": "06:00"},
        {"curfew_start": "04:00", "curfew_end": "06:00"},
        {"curfew_start": "03:00", "curfew_end": "06:30"},
    ]
    for case in cases:
        error = fail(put_policy(client, **case), 422, "policy_violation")
        assert "硬底线" in error["message"]
        assert "03:00" in error["message"]
    current = ok(client.get("/api/v1/policy", headers=HEADERS))
    assert current["source"] == "fallback"
    assert (current["curfew_start"], current["curfew_end"]) == ("01:00", "07:00")


def test_policy_exact_and_looser_curfew_allowed(client):
    data = ok(put_policy(client, curfew_start="03:00", curfew_end="07:00", daily_base_minutes=200))
    assert (data["curfew_start"], data["curfew_end"]) == ("03:00", "07:00")
    data = ok(put_policy(client, curfew_start="00:30", curfew_end="09:00"))
    assert (data["curfew_start"], data["curfew_end"]) == ("00:30", "09:00")
    data = ok(put_policy(client, curfew_start="02:30", curfew_end="07:30"))
    assert (data["curfew_start"], data["curfew_end"]) == ("02:30", "07:30")


def test_policy_range_validation(client):
    fail(put_policy(client, daily_base_minutes=481), 400, "invalid_argument")
    fail(put_policy(client, daily_base_minutes=-1), 400, "invalid_argument")
    fail(put_policy(client, session_max_minutes=19), 400, "invalid_argument")
    fail(put_policy(client, session_max_minutes=181), 400, "invalid_argument")
    fail(put_policy(client, curfew_start="25:00"), 400, "invalid_argument")
    fail(put_policy(client, blacklist=["x.exe"] * 51), 400, "invalid_argument")


def test_consume_flow(client, freeze_time):
    freeze_time()
    data = ok(consume(client, 30))
    assert (data["used_minutes"], data["remaining_minutes"]) == (30, 150)
    data = ok(consume(client, 60))
    assert (data["used_minutes"], data["remaining_minutes"]) == (90, 90)
    fail(consume(client, 61), 400, "invalid_argument")
    data = ok(consume(client, 60))
    assert (data["used_minutes"], data["remaining_minutes"]) == (150, 30)
    error = fail(consume(client, 31), 422, "policy_violation")
    assert "额度不足" in error["message"]

    export = ok(client.get("/api/v1/export", headers=HEADERS))
    actions = [log["action"] for log in export["audit_logs"]]
    assert actions.count("quota.consume") == 3


def test_consume_blocked_in_curfew(client, freeze_time):
    freeze_time(hour=4, minute=30)
    data = quota(client)
    assert data["locked"] is True
    assert data["lock_reason"] == "curfew"
    error = fail(consume(client, 10), 422, "policy_violation")
    assert "宵禁" in error["message"]


def test_grant_extends_quota(client, freeze_time):
    freeze_time()
    expires = timeutil.to_iso(timeutil.now() + timedelta(days=1))
    data = ok(grant(client, 30, reason="补偿", actor="hermes", expires_at=expires))
    assert data["bonus_minutes"] == 30
    assert data["remaining_minutes"] == 210
    assert len(data["extensions"]) == 1
    extension = data["extensions"][0]
    assert extension["minutes"] == 30
    assert extension["reason"] == "补偿"
    assert extension["revoked"] is False

    fail(grant(client, 0), 400, "invalid_argument")
    fail(grant(client, 1500), 400, "invalid_argument")
    past = timeutil.to_iso(timeutil.now() - timedelta(hours=1))
    fail(grant(client, 10, expires_at=past), 400, "invalid_argument")
    fail(grant(client, 10, actor="hacker"), 400, "invalid_argument")

    data = ok(consume(client, 60))
    assert data["used_minutes"] == 60
    assert data["remaining_minutes"] == 150


def test_explicit_lock_and_unlock(client, freeze_time):
    freeze_time()
    until = timeutil.to_iso(timeutil.now() + timedelta(hours=2))
    data = ok(
        client.post(
            "/api/v1/quota/lock",
            json={"reason": "打游戏超时", "until": until},
            headers=HEADERS,
        )
    )
    assert data["locked"] is True
    assert data["lock_reason"] == "打游戏超时"
    assert data["lock_until"] == until

    error = fail(consume(client, 5), 422, "policy_violation")
    assert "锁定" in error["message"]

    data = ok(client.post("/api/v1/quota/unlock", json={"reason": "手动解锁"}, headers=HEADERS))
    assert data["locked"] is False
    data = ok(consume(client, 5))
    assert data["used_minutes"] == 5

    fail(client.post("/api/v1/quota/lock", json={}, headers=HEADERS), 400, "invalid_argument")


def test_quota_exhausted_locks(client, freeze_time):
    freeze_time()
    ok(put_policy(client, daily_base_minutes=20))
    data = ok(consume(client, 20))
    assert data["remaining_minutes"] == 0
    assert data["locked"] is True
    assert data["lock_reason"] == "quota_exhausted"
    fail(consume(client, 1), 422, "policy_violation")


def test_quota_requests_approve_and_reject(client, freeze_time):
    freeze_time()
    today = timeutil.today_str()
    request = ok(
        client.post(
            "/api/v1/quota/requests",
            json={"minutes": 45, "reason": "想多玩半小时", "device_id": "pc-main"},
            headers=HEADERS,
        )
    )
    assert request["status"] == "pending"
    assert request["minutes"] == 45
    assert request["date"] == today

    rows = ok(client.get("/api/v1/quota/requests?status=pending", headers=HEADERS))
    assert any(row["id"] == request["id"] for row in rows)
    fail(client.get("/api/v1/quota/requests?status=bogus", headers=HEADERS), 400, "invalid_argument")

    data = ok(
        client.post(
            f"/api/v1/quota/requests/{request['id']}/decide",
            json={"approve": True, "minutes": 45, "note": "准了"},
            headers=HEADERS,
        )
    )
    assert data["request"]["status"] == "approved"
    assert data["request"]["decided_by"] == "app"
    assert data["request"]["decision_note"] == "准了"
    assert data["quota"]["bonus_minutes"] == 45
    assert data["quota"]["remaining_minutes"] == 225

    fail(
        client.post(f"/api/v1/quota/requests/{request['id']}/decide", json={"approve": True}, headers=HEADERS),
        409,
        "conflict",
    )

    second = ok(
        client.post(
            "/api/v1/quota/requests",
            json={"minutes": 30, "reason": "再来一次"},
            headers=HEADERS,
        )
    )
    data = ok(
        client.post(
            f"/api/v1/quota/requests/{second['id']}/decide",
            json={"approve": False, "note": "今天不行"},
            headers=HEADERS,
        )
    )
    assert data["request"]["status"] == "rejected"
    assert data["quota"]["bonus_minutes"] == 45

    fail(
        client.post("/api/v1/quota/requests/not-exist/decide", json={"approve": True}, headers=HEADERS),
        404,
        "not_found",
    )
