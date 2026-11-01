"""测试辅助函数（不依赖 app，避免导入顺序问题）。"""

from __future__ import annotations

TOKEN = "test-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}


def auth(**extra) -> dict:
    headers = dict(HEADERS)
    headers.update({key: str(value) for key, value in extra.items()})
    return headers


def ok(response, status: int = 200):
    assert response.status_code == status, f"{response.request.method} {response.request.url} -> {response.status_code}: {response.text}"
    body = response.json()
    assert body["ok"] is True, body
    assert "server_time" in body
    return body["data"]


def fail(response, status: int, code: str):
    assert response.status_code == status, f"{response.request.method} {response.request.url} -> {response.status_code}: {response.text}"
    body = response.json()
    assert body["ok"] is False, body
    assert body["error"]["code"] == code, body
    assert isinstance(body["error"]["message"], str) and body["error"]["message"]
    return body["error"]


def create_task(client, **overrides):
    payload = {
        "title": "刷题",
        "category": "study",
        "difficulty": 3,
        "attribute": "intelligence",
        "estimated_minutes": 30,
    }
    payload.update(overrides)
    return ok(client.post("/api/v1/tasks", json=payload, headers=HEADERS))


def complete(client, task_id, **body):
    return client.post(f"/api/v1/tasks/{task_id}/complete", json=body, headers=HEADERS)


def profile(client):
    return ok(client.get("/api/v1/profile", headers=HEADERS))


def quota(client):
    return ok(client.get("/api/v1/quota", headers=HEADERS))
