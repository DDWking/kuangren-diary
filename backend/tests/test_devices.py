"""设备心跳、事件上报、命令下发与确认。"""

from __future__ import annotations

from datetime import timedelta

from app import timeutil
from app.models import Command
from helpers import HEADERS, fail, ok

DEVICE = "pc-main"


def heartbeat(client, device_id=DEVICE, **overrides):
    payload = {
        "name": "Windows PC",
        "platform": "windows",
        "app_version": "1.0.0",
        "state": {"running": True, "game": None},
    }
    payload.update(overrides)
    return client.post(f"/api/v1/devices/{device_id}/heartbeat", json=payload, headers=HEADERS)


def report_event(client, device_id=DEVICE, **overrides):
    payload = {"type": "app_start", "severity": "info"}
    payload.update(overrides)
    return client.post(f"/api/v1/devices/{device_id}/events", json=payload, headers=HEADERS)


def test_heartbeat_registers_device(client):
    data = ok(heartbeat(client))
    device = data["device"]
    assert device["id"] == DEVICE
    assert device["name"] == "Windows PC"
    assert device["platform"] == "windows"
    assert device["app_version"] == "1.0.0"
    assert device["online"] is True
    assert device["last_seen"]
    assert data["quota"]["date"] == timeutil.today_str()
    assert data["policy"]["curfew_start"] == "01:00"
    assert data["commands"] == []

    rows = ok(client.get("/api/v1/devices", headers=HEADERS))
    assert [row["id"] for row in rows] == [DEVICE]


def test_heartbeat_invalid_device_id(client):
    fail(heartbeat(client, device_id="bad id!"), 400, "invalid_argument")


def test_violation_event_auto_lock_command(client):
    first = ok(report_event(client, type="violation", payload={"process": "steam.exe"}))
    assert first["violations_today"] == 1
    second = ok(report_event(client, type="violation", payload={"process": "steam.exe"}))
    assert second["violations_today"] == 2
    assert ok(client.get(f"/api/v1/commands?device_id={DEVICE}&status=pending", headers=HEADERS)) == []

    third = ok(report_event(client, type="violation", payload={"process": "steam.exe"}))
    assert third["violations_today"] == 3

    commands = ok(client.get(f"/api/v1/commands?device_id={DEVICE}&status=pending", headers=HEADERS))
    assert len(commands) == 1
    lock = commands[0]
    assert lock["type"] == "lock"
    assert lock["status"] == "pending"
    assert lock["payload"]["violations_today"] == 3

    events = ok(client.get(f"/api/v1/events?limit=10&device_id={DEVICE}", headers=HEADERS))
    assert len(events) == 3
    assert {row["type"] for row in events} == {"violation"}
    assert all(row["device_id"] == DEVICE for row in events)

    acked = ok(
        client.post(
            f"/api/v1/commands/{lock['id']}/ack",
            json={"status": "done", "note": "已锁屏"},
            headers=HEADERS,
        )
    )
    assert acked["status"] == "done"
    assert acked["acked_at"]


def test_commands_lifecycle(client):
    created = ok(
        client.post(
            "/api/v1/commands",
            json={"device_id": DEVICE, "type": "lock", "payload": {"reason": "manual"}, "ttl_seconds": 120},
            headers=HEADERS,
        )
    )
    assert created["status"] == "pending"
    assert created["type"] == "lock"
    assert created["device_id"] == DEVICE

    pending = ok(client.get(f"/api/v1/commands?device_id={DEVICE}", headers=HEADERS))
    assert [row["id"] for row in pending] == [created["id"]]

    data = ok(client.post(f"/api/v1/commands/{created['id']}/ack", json={"status": "done"}, headers=HEADERS))
    assert data["status"] == "done"
    again = ok(client.post(f"/api/v1/commands/{created['id']}/ack", json={"status": "done"}, headers=HEADERS))
    assert again["id"] == created["id"]
    assert again["status"] == "done"

    fail(
        client.post(f"/api/v1/commands/{created['id']}/ack", json={"status": "failed"}, headers=HEADERS),
        409,
        "conflict",
    )
    fail(
        client.post("/api/v1/commands", json={"device_id": DEVICE, "type": "boom"}, headers=HEADERS),
        400,
        "invalid_argument",
    )
    fail(
        client.post(f"/api/v1/commands/{created['id']}/ack", json={"status": "maybe"}, headers=HEADERS),
        400,
        "invalid_argument",
    )
    fail(
        client.post("/api/v1/commands/not-exist/ack", json={"status": "done"}, headers=HEADERS),
        404,
        "not_found",
    )


def test_command_ttl_expires(client, db):
    created = ok(
        client.post(
            "/api/v1/commands",
            json={"device_id": DEVICE, "type": "reload", "ttl_seconds": 1},
            headers=HEADERS,
        )
    )
    row = db.get(Command, created["id"])
    row.created_at = timeutil.to_iso(timeutil.now() - timedelta(seconds=30))
    db.commit()

    pending = ok(client.get(f"/api/v1/commands?device_id={DEVICE}&status=pending", headers=HEADERS))
    assert pending == []
    failed = ok(client.get(f"/api/v1/commands?device_id={DEVICE}&status=failed", headers=HEADERS))
    assert [row["id"] for row in failed] == [created["id"]]
    assert failed[0]["status"] == "failed"


def test_heartbeat_returns_pending_commands(client):
    created = ok(
        client.post(
            "/api/v1/commands",
            json={"device_id": DEVICE, "type": "kill", "payload": {"target": "steam.exe"}},
            headers=HEADERS,
        )
    )
    data = ok(heartbeat(client))
    assert [row["id"] for row in data["commands"]] == [created["id"]]
    assert data["device"]["id"] == DEVICE
