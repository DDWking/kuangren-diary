"""统计、报告、成就、通知、导出与面板聚合。"""

from __future__ import annotations

from datetime import timedelta

from app import timeutil
from helpers import HEADERS, complete, create_task, fail, ok


def test_panel_aggregates(client, freeze_time):
    freeze_time()
    today = timeutil.today_str()
    yesterday = timeutil.date_str(timeutil.today() - timedelta(days=1))
    today_task = create_task(client, title="今天的任务")
    overdue_task = create_task(client, title="昨天的任务", due_date=yesterday)

    data = ok(client.get("/api/v1/panel", headers=HEADERS))
    assert data["date"] == today
    assert data["profile"]["id"] == "default"
    assert data["quota"]["date"] == today
    assert [task["id"] for task in data["tasks"]["today"]] == [today_task["id"]]
    assert [task["id"] for task in data["tasks"]["overdue"]] == [overdue_task["id"]]
    assert data["diary"] is None
    assert data["weight"]["latest"] is None
    assert data["weight"]["change_7d"] is None
    assert data["stats"]["streak"] == 0
    assert data["exam"]["date"] == data["profile"]["exam_date"]
    assert isinstance(data["exam"]["days"], int)
    assert isinstance(data["notifications"], list)

    ok(client.put(f"/api/v1/diary/{today}", json={"content": "今天还不错", "mood": 4}, headers=HEADERS))
    ok(client.put(f"/api/v1/weight/{today}", json={"weight_kg": 70.5}, headers=HEADERS))
    data = ok(client.get("/api/v1/panel", headers=HEADERS))
    assert data["diary"]["content"] == "今天还不错"
    assert data["diary"]["mood"] == 4
    assert data["weight"]["latest"]["weight_kg"] == 70.5


def test_stats_summary_and_heatmap(client, freeze_time):
    freeze_time()
    today = timeutil.today_str()
    first = create_task(client, estimated_minutes=30)
    second = create_task(client, estimated_minutes=25)
    ok(complete(client, first["id"], actual_minutes=25), 200)
    ok(complete(client, second["id"]), 200)

    summary = ok(client.get("/api/v1/stats/summary?range=7d", headers=HEADERS))
    assert summary["range"] == "7d"
    assert summary["study_minutes"] == 50
    assert summary["tasks_done"] == 2
    assert 0.0 <= summary["completion_rate"] <= 1.0
    assert summary["weight_start"] is None
    assert summary["weight_end"] is None
    assert summary["weight_change"] is None
    assert len(summary["daily"]) == 7
    assert summary["daily"][-1]["date"] == today
    assert summary["daily"][-1]["study_minutes"] == 50
    assert summary["daily"][-1]["tasks_done"] == 2
    assert len(summary["heatmap"]) == 7
    assert summary["heatmap"][-1] == {"date": today, "value": 50}
    assert summary["attributes"]["intelligence"] >= 6
    assert summary["streak"] == 1

    fail(client.get("/api/v1/stats/summary?range=oops", headers=HEADERS), 400, "invalid_argument")
    fail(client.get("/api/v1/stats/summary?range=400d", headers=HEADERS), 400, "invalid_argument")

    heat = ok(client.get("/api/v1/stats/heatmap?days=30", headers=HEADERS))
    assert len(heat) == 30
    assert heat[-1] == {"date": today, "value": 50}
    fail(client.get("/api/v1/stats/heatmap?days=0", headers=HEADERS), 400, "invalid_argument")


def test_reports_achievements_notifications(client, freeze_time):
    freeze_time()
    task = create_task(client)
    ok(complete(client, task["id"]), 200)

    report = ok(
        client.post(
            "/api/v1/reports",
            json={"title": "早报", "content": "今天也要学习", "kind": "morning"},
            headers=HEADERS,
        )
    )
    assert report["kind"] == "morning"
    rows = ok(client.get("/api/v1/reports?limit=20", headers=HEADERS))
    assert rows and rows[0]["id"] == report["id"]
    fail(
        client.post("/api/v1/reports", json={"title": "x", "content": "y", "kind": "nope"}, headers=HEADERS),
        400,
        "invalid_argument",
    )
    fail(client.post("/api/v1/reports", json={"title": "", "content": "y"}, headers=HEADERS), 400, "invalid_argument")

    achievements = ok(client.get("/api/v1/achievements", headers=HEADERS))
    assert len(achievements) == 9
    unlocked = {row["id"]: row for row in achievements}
    assert unlocked["first_task"]["unlocked"] is True
    assert unlocked["first_task"]["unlocked_at"]
    assert unlocked["first_diary"]["unlocked"] is False

    ok(
        client.put(
            f"/api/v1/diary/{timeutil.today_str()}",
            json={"content": "写了日记", "mood": 3},
            headers=HEADERS,
        )
    )
    achievements = ok(client.get("/api/v1/achievements", headers=HEADERS))
    assert {row["id"]: row for row in achievements}["first_diary"]["unlocked"] is True

    notifications = ok(client.get("/api/v1/notifications?unread=1", headers=HEADERS))
    assert notifications
    assert all(row["read"] is False for row in notifications)
    read = ok(client.post("/api/v1/notifications/read", headers=HEADERS))
    assert read["updated"] >= 1
    assert ok(client.get("/api/v1/notifications?unread=1", headers=HEADERS)) == []
    all_rows = ok(client.get("/api/v1/notifications?limit=200", headers=HEADERS))
    assert all(row["read"] is True for row in all_rows)


def test_export_contains_everything(client, freeze_time):
    freeze_time()
    task = create_task(client)
    ok(complete(client, task["id"]), 200)
    data = ok(client.get("/api/v1/export", headers=HEADERS))
    for key in (
        "exported_at",
        "profile",
        "tasks",
        "diary",
        "weight",
        "policies",
        "quota_days",
        "grants",
        "quota_requests",
        "devices",
        "events",
        "commands",
        "reports",
        "achievements",
        "notifications",
        "audit_logs",
    ):
        assert key in data, key
    assert any(row["id"] == task["id"] and row["status"] == "done" for row in data["tasks"])
    assert data["profile"]["total_tasks_done"] == 1
