#!/usr/bin/env python3
"""狂人日记 API 冒烟测试。

默认打本地测试实例；部署后可用：
    python scripts/smoke-api.py --base http://127.0.0.1:8090 --token <TOKEN>
    python scripts/smoke-api.py --base http://159.75.236.90/kuangren-api --token <TOKEN> --readonly
"""

from __future__ import annotations

import argparse
import sys
import uuid
from datetime import date

import httpx

FAILS: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        print(f"  PASS  {name}")
    else:
        FAILS.append(f"{name} {detail}".strip())
        print(f"  FAIL  {name}  {detail}")


def is_curfew_rejection(resp: httpx.Response) -> bool:
    if resp.status_code != 422:
        return False
    try:
        error = (resp.json() or {}).get("error") or {}
    except Exception:
        return False
    message = str(error.get("message") or "")
    return error.get("code") == "policy_violation" and ("宵禁" in message or "禁玩" in message)


def expect(resp: httpx.Response, name: str, status: int = 200) -> dict:
    ok = resp.status_code == status
    body = {}
    try:
        body = resp.json()
    except Exception:
        pass
    check(name, ok, f"HTTP {resp.status_code} {resp.text[:200]}")
    if ok and isinstance(body, dict) and body.get("ok") is True:
        return body.get("data") or {}
    return body


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18090")
    parser.add_argument("--token", default="test-token")
    parser.add_argument("--readonly", action="store_true")
    args = parser.parse_args()

    client = httpx.Client(
        base_url=args.base.rstrip("/"),
        headers={"Authorization": f"Bearer {args.token}", "X-Actor": "system"},
        timeout=10.0,
    )
    today = date.today().isoformat()
    suffix = uuid.uuid4().hex[:6]
    print(f"base={args.base} token=*** readonly={args.readonly}")

    print("[health]")
    r = client.get("/api/v1/health", headers={"Authorization": ""})
    data = expect(r, "health 无需认证")
    check("health.db=ok", data.get("db") == "ok", str(data.get("db")))

    r = client.get("/api/v1/profile", headers={"Authorization": "Bearer wrong"})
    expect(r, "错误 token 返回 401", 401)

    print("[read]")
    profile = expect(client.get("/api/v1/profile"), "GET profile")
    check("profile 默认名", profile.get("name") == "宿主", str(profile.get("name")))
    check("exam_days 是数字", isinstance(profile.get("exam_days"), int), str(profile.get("exam_days")))
    panel = expect(client.get("/api/v1/panel"), "GET panel")
    check("panel 含 quota/tasks", "quota" in panel and "tasks" in panel)
    policy = expect(client.get("/api/v1/policy"), "GET policy")
    check("policy 有 fallback", "daily_base_minutes" in policy)
    quota = expect(client.get("/api/v1/quota"), "GET quota")
    check("quota 剩余非负", int(quota.get("remaining_minutes", -1)) >= 0, str(quota))
    stats = expect(client.get("/api/v1/stats/summary?range=7d"), "GET stats")
    check("stats 有 daily", isinstance(stats.get("daily"), list))
    if args.readonly:
        return report()

    print("[tasks]")
    key = f"smoke-task-{suffix}"
    payload = {
        "title": f"冒烟测试任务 {suffix}",
        "category": "study",
        "difficulty": 3,
        "attribute": "intelligence",
        "due_date": today,
        "estimated_minutes": 25,
    }
    headers = {"Idempotency-Key": key}
    t1 = expect(client.post("/api/v1/tasks", json=payload, headers=headers), "创建任务")
    t2 = expect(client.post("/api/v1/tasks", json=payload, headers=headers), "幂等创建不重复")
    check("幂等任务 id 相同", t1.get("id") == t2.get("id"), f"{t1.get('id')} vs {t2.get('id')}")
    task_id = t1.get("id")
    check("任务有奖励预估", int(t1.get("exp_reward", 0)) > 0, str(t1.get("exp_reward")))
    done = expect(client.post(f"/api/v1/tasks/{task_id}/complete", json={"note": "smoke", "actual_minutes": 25}), "完成任务")
    rewards = done.get("rewards") or {}
    check("完成有 exp", int(rewards.get("exp", 0)) > 0, str(rewards))
    again = expect(client.post(f"/api/v1/tasks/{task_id}/complete", json={}), "重复完成不重复给奖")
    check("重复完成 exp 相同", int((again.get("rewards") or {}).get("exp", -1)) == int(rewards.get("exp", -2)))
    expect(client.post(f"/api/v1/tasks/{task_id}/reopen", json={}), "重新打开任务")

    print("[diary/weight]")
    expect(client.put(f"/api/v1/diary/{today}", json={"mood": 4, "content": f"smoke diary {suffix}", "tags": ["测试"]}), "写日记")
    d = expect(client.get(f"/api/v1/diary?date={today}"), "读日记")
    check("日记内容回读", d.get("content") == f"smoke diary {suffix}", str(d.get("content")))
    w = expect(client.put(f"/api/v1/weight/{today}", json={"weight_kg": 100.5, "body_fat": 25.0, "source": "manual"}), "写体重")
    check("体重回读", abs(float(w.get("weight_kg", 0)) - 100.5) < 0.01, str(w.get("weight_kg")))

    print("[quota/policy]")
    q0 = expect(client.get("/api/v1/quota"), "读取额度")
    used0 = int(q0.get("used_minutes", 0))
    cr = client.post("/api/v1/quota/consume", json={"minutes": 10, "source": "smoke"})
    if is_curfew_rejection(cr):
        print("  SKIP  消耗额度（当前处于宵禁时段，后端按规则拒绝）")
    else:
        c = expect(cr, "消耗额度")
        check("消耗后 used 增加", int(c.get("used_minutes", 0)) >= used0 + 10, str(c.get("used_minutes")))
    g = expect(client.post("/api/v1/quota/grant", json={"minutes": 20, "reason": "smoke 奖励"}), "发放额度")
    check("grant 后 bonus 增加", int(g.get("bonus_minutes", 0)) >= 20, str(g.get("bonus_minutes")))
    bad = client.put("/api/v1/policy", json={"date": today, "curfew_start": "04:00"})
    expect(bad, "触碰凌晨 4 点硬底线被拒", 422)
    good = expect(client.put("/api/v1/policy", json={"date": today, "daily_base_minutes": 120, "notes": "smoke"}), "设置软策略")
    check("软策略生效", int(good.get("daily_base_minutes", 0)) == 120, str(good.get("daily_base_minutes")))
    req = expect(client.post("/api/v1/quota/requests", json={"minutes": 15, "reason": "smoke 申请"}), "创建额度申请")
    req_id = (req.get("request") or req).get("id")
    decided = expect(client.post(f"/api/v1/quota/requests/{req_id}/decide", json={"approve": True, "minutes": 15, "note": "ok"}), "批准申请")
    check("申请已批准", ((decided.get("request") or {}).get("status") == "approved"), str(decided))

    print("[devices]")
    hb = expect(client.post("/api/v1/devices/smoke-pc/heartbeat", json={"name": "smoke", "platform": "windows", "app_version": "0.0.1", "state": {}}), "设备心跳")
    check("心跳返回 quota/policy", "quota" in hb and "policy" in hb)
    ev = expect(client.post("/api/v1/devices/smoke-pc/events", json={"type": "app_start", "severity": "info", "payload": {"exe": "smoke.exe"}}), "上报事件")
    check("事件有 id", bool(ev.get("id")))
    cmd = expect(client.post("/api/v1/commands", json={"device_id": "smoke-pc", "type": "lock", "payload": {"reason": "smoke"}}), "创建命令")
    check("命令 pending", cmd.get("status") == "pending", str(cmd.get("status")))
    ack = expect(client.post(f"/api/v1/commands/{cmd.get('id')}/ack", json={"status": "done", "note": "smoke"}), "确认命令")
    check("命令 done", ack.get("status") == "done", str(ack.get("status")))

    print("[export]")
    export = expect(client.get("/api/v1/export"), "导出全量数据")
    check("导出含 tasks", isinstance(export.get("tasks"), list), str(list(export)[:5]))

    return report()


def report() -> int:
    print()
    if FAILS:
        print(f"失败 {len(FAILS)} 项：")
        for item in FAILS:
            print("  -", item)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())