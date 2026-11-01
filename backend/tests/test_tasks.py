"""任务 CRUD、完成奖励幂等、回退、顺延、软删除。"""

from __future__ import annotations

from datetime import timedelta

from app import timeutil
from app.models import Task
from helpers import HEADERS, auth, complete, create_task, fail, ok, profile


def test_create_and_list_tasks(client):
    task = create_task(client, title="线性代数", difficulty=2, estimated_minutes=45)
    assert task["status"] == "pending"
    assert task["due_date"] == timeutil.today_str()
    assert task["category"] == "study"
    assert task["exp_reward"] > 0 and task["coin_reward"] > 0
    listed = ok(client.get("/api/v1/tasks", headers=HEADERS))
    assert [row["id"] for row in listed] == [task["id"]]
    filtered = ok(client.get("/api/v1/tasks", params={"status": "pending", "category": "study"}, headers=HEADERS))
    assert len(filtered) == 1
    fail(client.get("/api/v1/tasks", params={"status": "wat"}, headers=HEADERS), 400, "invalid_argument")


def test_create_validation(client):
    fail(client.post("/api/v1/tasks", json={"title": ""}, headers=HEADERS), 400, "invalid_argument")
    fail(client.post("/api/v1/tasks", json={"title": "x", "category": "game"}, headers=HEADERS), 400, "invalid_argument")
    fail(client.post("/api/v1/tasks", json={"title": "x", "difficulty": 9}, headers=HEADERS), 400, "invalid_argument")
    fail(client.post("/api/v1/tasks", json={"title": "x", "attribute": "luck"}, headers=HEADERS), 400, "invalid_argument")
    fail(client.post("/api/v1/tasks", json={"title": "x", "repeat_rule": "yearly"}, headers=HEADERS), 400, "invalid_argument")
    fail(client.post("/api/v1/tasks", json={"title": "x", "due_date": "2026-13-40"}, headers=HEADERS), 400, "invalid_argument")


def test_patch_and_postpone(client):
    task = create_task(client, title="原标题", difficulty=2)
    patched = ok(
        client.patch(f"/api/v1/tasks/{task['id']}", json={"title": "新标题", "difficulty": 4}, headers=HEADERS)
    )
    assert patched["title"] == "新标题" and patched["difficulty"] == 4
    # 不允许用 PATCH 直接把任务标成 done（否则绕过奖励）
    fail(client.patch(f"/api/v1/tasks/{task['id']}", json={"status": "done"}, headers=HEADERS), 400, "invalid_argument")

    tomorrow = timeutil.date_str(timeutil.today() + timedelta(days=1))
    moved = ok(client.post(f"/api/v1/tasks/{task['id']}/postpone", json={"to_date": tomorrow}, headers=HEADERS))
    assert moved["due_date"] == tomorrow

    yesterday = timeutil.date_str(timeutil.today() - timedelta(days=1))
    fail(
        client.post(f"/api/v1/tasks/{task['id']}/postpone", json={"to_date": yesterday}, headers=HEADERS),
        400,
        "invalid_argument",
    )
    fail(client.patch("/api/v1/tasks/not-exists", json={"title": "x"}, headers=HEADERS), 404, "not_found")


def test_complete_rewards_formula_and_idempotent(client, freeze_time):
    freeze_time(14)
    task = create_task(client, difficulty=3, estimated_minutes=40)
    data = ok(complete(client, task["id"]))
    rewards = data["rewards"]
    # (10 + 10 * 3) * 1.0 = 40 exp；5 * 3 * 1.0 = 15 积分；属性 +6
    assert rewards["exp"] == 40
    assert rewards["coins"] == 15
    assert rewards["attribute"] == "intelligence"
    assert rewards["attribute_delta"] == 6
    assert rewards["streak_multiplier"] == 1.0
    assert data["task"]["status"] == "done"
    assert data["task"]["actual_minutes"] is None

    after = data["profile"]
    assert after["exp"] == 40 and after["coins"] == 15
    assert after["attributes"]["intelligence"] == 6
    assert after["total_tasks_done"] == 1
    assert after["total_study_minutes"] == 40
    # 完成任务不消耗娱乐额度
    assert data["quota"]["used_minutes"] == 0

    # 重复完成：返回首次奖励，不重复加经验/积分/属性
    again = ok(complete(client, task["id"]))
    assert again["rewards"] == rewards
    assert again["profile"]["exp"] == 40
    assert again["profile"]["coins"] == 15
    assert again["profile"]["total_tasks_done"] == 1
    assert again["profile"]["total_study_minutes"] == 40

    # 带 Idempotency-Key 重放同样只结算一次
    third = ok(complete(client, task["id"], note="再来一次"))
    assert third["profile"]["exp"] == 40


def test_complete_with_actual_minutes_and_streak_multiplier(client, freeze_time):
    freeze_time(14)
    # 先完成一个任务积累连击（今天有学习记录 → streak=1 → 倍率 1.05）
    first = create_task(client, title="第一件", difficulty=2, estimated_minutes=10)
    ok(complete(client, first["id"]))
    second = create_task(client, title="第二件", difficulty=2, estimated_minutes=10)
    data = ok(complete(client, second["id"], actual_minutes=25, note="多学了 15 分钟"))
    assert data["rewards"]["streak_multiplier"] == 1.05
    assert data["rewards"]["exp"] == round((10 + 20) * 1.05)
    assert data["profile"]["streak"] == 1
    assert data["profile"]["total_study_minutes"] == 10 + 25
    assert data["task"]["actual_minutes"] == 25


def test_non_study_task_does_not_add_study_minutes(client, freeze_time):
    freeze_time(14)
    task = create_task(client, title="跑步", category="body", attribute="stamina", estimated_minutes=30)
    data = ok(complete(client, task["id"]))
    assert data["profile"]["total_study_minutes"] == 0
    assert data["profile"]["attributes"]["stamina"] == 6
    assert data["profile"]["total_tasks_done"] == 1


def test_reopen_same_day(client, freeze_time):
    freeze_time(14)
    task = create_task(client, difficulty=3, estimated_minutes=30)
    ok(complete(client, task["id"]))
    reopened = ok(client.post(f"/api/v1/tasks/{task['id']}/reopen", headers=HEADERS))
    assert reopened["status"] == "pending"
    assert reopened["completed_at"] is None and reopened["completion_note"] is None
    after = profile(client)
    assert after["exp"] == 0 and after["coins"] == 0
    assert after["attributes"]["intelligence"] == 0
    assert after["total_tasks_done"] == 0
    assert after["total_study_minutes"] == 0
    # 未完成的任务不能回退
    fail(client.post(f"/api/v1/tasks/{task['id']}/reopen", headers=HEADERS), 409, "conflict")


def test_reopen_old_completion_rejected(client, db, freeze_time):
    freeze_time(14)
    task = create_task(client)
    ok(complete(client, task["id"]))
    row = db.get(Task, task["id"])
    row.completed_at = timeutil.to_iso(timeutil.now() - timedelta(days=1))
    db.commit()
    fail(client.post(f"/api/v1/tasks/{task['id']}/reopen", headers=HEADERS), 409, "conflict")


def test_delete_is_soft_and_audited(client):
    task = create_task(client)
    data = ok(client.delete(f"/api/v1/tasks/{task['id']}", headers=HEADERS))
    assert data == {"deleted": True}
    assert ok(client.get("/api/v1/tasks", headers=HEADERS)) == []
    fail(complete(client, task["id"]), 404, "not_found")
    # 重复删除保持幂等
    assert ok(client.delete(f"/api/v1/tasks/{task['id']}", headers=HEADERS)) == {"deleted": True}
    # 审计里保留删除记录
    export = ok(client.get("/api/v1/export", headers=HEADERS))
    actions = [row["action"] for row in export["audit_logs"]]
    assert "task.create" in actions and "task.delete" in actions


def test_complete_missing_task(client):
    fail(complete(client, "00000000-0000-0000-0000-000000000000"), 404, "not_found")
