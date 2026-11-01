"""健康检查、鉴权、统一响应包、CORS、错误码。"""

from __future__ import annotations

from helpers import HEADERS, auth, fail, ok


def test_health_no_auth(client):
    body = client.get("/health")
    data = ok(body)
    assert data["status"] == "ok"
    assert data["db"] == "ok"
    assert data["version"]
    assert "server_time" in data


def test_api_health_no_auth(client):
    data = ok(client.get("/api/v1/health"))
    assert data["status"] == "ok"


def test_business_api_requires_token(client):
    error = fail(client.get("/api/v1/profile"), 401, "unauthorized")
    assert error["message"]
    fail(client.get("/api/v1/profile", headers={"Authorization": "Bearer wrong-token"}), 401, "unauthorized")
    fail(client.get("/api/v1/profile", headers={"Authorization": "Basic abc"}), 401, "unauthorized")


def test_profile_defaults(client):
    data = profile(client)
    assert data["id"] == "default"
    assert data["name"] == "宿主"
    assert data["title"] == "初醒者"
    assert data["level"] == 1 and data["exp"] == 0 and data["coins"] == 0
    assert data["exp_next"] == 100
    assert data["exp_progress"] == 0.0
    assert data["exam_date"] == "2026-12-19"
    assert data["exam_days"] > 0
    assert data["daily_study_target_minutes"] == 300
    assert set(data["attributes"]) == {"intelligence", "stamina", "willpower", "focus", "order"}


def test_unknown_route_returns_envelope(client):
    fail(client.get("/api/v1/not-exists", headers=HEADERS), 404, "not_found")


def test_validation_error_returns_400(client):
    fail(client.post("/api/v1/tasks", json={"category": "study"}, headers=HEADERS), 400, "invalid_argument")


def test_cors_preflight(client):
    response = client.options(
        "/api/v1/tasks",
        headers={
            "Origin": "http://127.0.0.1:8099",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization,content-type",
        },
    )
    assert response.status_code in (200, 204)
    assert response.headers.get("access-control-allow-origin") == "*"


def test_profile_patch_validation(client):
    fail(client.patch("/api/v1/profile", json={"name": "x" * 40}, headers=HEADERS), 400, "invalid_argument")
    fail(client.patch("/api/v1/profile", json={"daily_study_target_minutes": 5}, headers=HEADERS), 400, "invalid_argument")
    fail(client.patch("/api/v1/profile", json={"exam_date": "2026/12/19"}, headers=HEADERS), 400, "invalid_argument")
    data = ok(client.patch("/api/v1/profile", json={"name": "阿狂", "title": "夜行者"}, headers=HEADERS))
    assert data["name"] == "阿狂" and data["title"] == "夜行者"


def test_actor_header_accepted(client):
    data = ok(client.patch("/api/v1/profile", json={"name": "PC"}, headers=auth(**{"X-Actor": "hermes"})))
    assert data["name"] == "PC"


def test_request_idempotency_key(client):
    payload = {"title": "幂等任务", "category": "study", "difficulty": 2}
    first = ok(client.post("/api/v1/tasks", json=payload, headers=auth(**{"Idempotency-Key": "key-create-1"})))
    second = ok(client.post("/api/v1/tasks", json=payload, headers=auth(**{"Idempotency-Key": "key-create-1"})))
    assert first["id"] == second["id"]
    rows = ok(client.get("/api/v1/tasks", headers=HEADERS))
    assert len([row for row in rows if row["id"] == first["id"]]) == 1


def ok(response, status: int = 200):
    body = response.json()
    assert response.status_code == status, f"{response.status_code}: {response.text}"
    assert body["ok"] is True
    return body["data"]


def profile(client):
    return ok(client.get("/api/v1/profile", headers=HEADERS))
