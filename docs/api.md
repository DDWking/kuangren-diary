# 狂人日记 API 契约 v1

本文档是 Android、云端后端、PC 执行器、Hermes 插件之间的唯一接口约定。所有实现必须以此为准；改接口先改本文档。

## 0. 基本约定

- 生产基址：`http://159.75.236.90/kuangren-api`
- 本地开发：`http://127.0.0.1:8090`
- API 前缀：`/api/v1`
- 认证：`Authorization: Bearer <TOKEN>`；除 `GET /health` 外全部需要。
- 可选头：
  - `X-Actor: app|pc|hermes|system`，默认 `app`
  - `X-Device-Id: pc-main|phone-main`
  - `Idempotency-Key: <uuid>`（写操作去重，至少 complete/consume/grant 支持）
- 时间：ISO-8601 带时区，统一 `Asia/Shanghai`（如 `2026-11-02T02:30:00+08:00`）。
- 日期：`YYYY-MM-DD`，一律按 `Asia/Shanghai` 的“今天”。
- ID：UUIDv4 字符串。
- 成功响应统一包一层：
  ```json
  { "ok": true, "data": { }, "server_time": "2026-11-02T02:30:00+08:00" }
  ```
- 失败响应（HTTP 4xx/5xx）：
  ```json
  { "ok": false, "error": { "code": "invalid_argument", "message": "人类可读的中文错误" }, "server_time": "..." }
  ```
- 幂等：同一 `Idempotency-Key` 重复请求返回首次结果。
- CORS：允许 `*`（下载页/调试用）。
- 健康检查：`GET /health` 无需认证，返回版本、数据库状态、服务器时间。

## 1. 通用对象

### Profile
```json
{
  "id": "default",
  "name": "宿主",
  "title": "初醒者",
  "level": 1,
  "exp": 0,
  "exp_next": 100,
  "exp_progress": 0.0,
  "coins": 0,
  "streak": 0,
  "attributes": {
    "intelligence": 0,
    "stamina": 0,
    "willpower": 0,
    "focus": 0,
    "order": 0
  },
  "exam_date": "2026-12-19",
  "exam_days": 47,
  "daily_study_target_minutes": 300,
  "total_study_minutes": 0,
  "total_tasks_done": 0,
  "created_at": "...",
  "updated_at": "..."
}
```

- 等级：`exp_next = 100 + 50 * (level - 1)`；`exp` 为当前等级内经验，升级后扣除。
- 五维：0–100，完成任务加 `difficulty * 2`，封顶 100。
- `exam_days` = `exam_date - 今天` 的自然日差，最小 0；考试当天为 0。
- 每日学习目标默认 300 分钟（用户要求 4–6 小时，取 5 小时）。

### Task
```json
{
  "id": "uuid",
  "title": "政治：肖八选择题一节",
  "description": null,
  "category": "study",
  "difficulty": 3,
  "attribute": "intelligence",
  "due_date": "2026-11-02",
  "estimated_minutes": 45,
  "status": "pending",
  "repeat_rule": "none",
  "created_by": "user",
  "created_at": "...",
  "completed_at": null,
  "completion_note": null,
  "actual_minutes": null,
  "exp_reward": 40,
  "coin_reward": 15,
  "deleted_at": null
}
```

- `category`: `study | body | life | mind | other`
- `attribute`: `intelligence | stamina | willpower | focus | order`
- `status`: `pending | done | skipped`
- `repeat_rule`: `none | daily | weekly`（仅记录，不自动生成；每日任务由 Hermes/客户端每日创建）
- 奖励由后端计算（客户端传的不算）：
  - `exp_reward = round((10 + 10 * difficulty) * streak_multiplier)`
  - `coin_reward = round(5 * difficulty * streak_multiplier)`
  - `streak_multiplier = 1 + 0.05 * min(streak, 10)`，上限 1.5
  - `attribute + difficulty * 2`
- 完成 study 任务时 `total_study_minutes += actual_minutes ?? estimated_minutes ?? 0`。
- 已完成的再 complete 返回原结果，不重复加经验。

### DiaryEntry
```json
{
  "date": "2026-11-02",
  "mood": 3,
  "content": "今天背了 50 个单词……",
  "ai_comment": "不错，明天把 408 也补上。",
  "tags": ["学习", "低气压"],
  "created_at": "...",
  "updated_at": "..."
}
```

- `mood`: 1–5（1 很差，5 很好）。
- `ai_comment` 由 Hermes 通过接口写入，App 只读。

### WeightEntry
```json
{
  "date": "2026-11-02",
  "weight_kg": 104.7,
  "body_fat": 28.4,
  "waist_cm": 102.0,
  "note": "晨起空腹",
  "source": "manual",
  "created_at": "..."
}
```

- `source`: `manual | hermes | scale`
- 每天最多一条，PUT 覆盖。

### Policy
```json
{
  "date": "2026-11-02",
  "daily_base_minutes": 180,
  "curfew_start": "01:00",
  "curfew_end": "07:00",
  "session_max_minutes": 60,
  "blacklist": ["steam.exe", "WeGame.exe", "valorant.exe", "deadlock.exe"],
  "notes": "今天学完 408 可以玩到 12 点",
  "source": "hermes",
  "updated_by": "hermes",
  "updated_at": "..."
}
```

- 每日策略由 Hermes 动态设置；没有设置时后端返回 fallback（`source: "fallback"`，180 分钟/01:00–07:00/60 分钟）。
- 三条硬底线由后端强制，Hermes 不能改：
  1. 不能删除/停用 App、审计、事件记录（没有删除接口）。
  2. 不能通宵：`curfew_start` 不得晚于 03:00，`curfew_end` 不得早于 07:00；即 03:00–07:00 必须是禁止娱乐窗口。
  3. 连续 3 个自然日“零学习完成”后，次日基础娱乐额度强制减半（`forced_tighten=true`），Hermes 不能覆盖；直到出现学习完成记录。
- 允许 Hermes 在底线内调松/调紧；单次 `daily_base_minutes` 范围 0–480，`session_max_minutes` 20–180。

### Quota
```json
{
  "date": "2026-11-02",
  "base_minutes": 180,
  "bonus_minutes": 30,
  "used_minutes": 35,
  "remaining_minutes": 175,
  "locked": false,
  "lock_reason": null,
  "lock_until": null,
  "policy": { "...Policy..." },
  "extensions": [
    {
      "id": "uuid",
      "minutes": 30,
      "reason": "宿主说今天学完了",
      "actor": "hermes",
      "expires_at": "2026-11-02T23:59:59+08:00",
      "revoked": false,
      "created_at": "..."
    }
  ],
  "violations_today": 0,
  "forced_tighten": false,
  "tighten_reason": null
}
```

- `remaining_minutes = max(0, base + bonus - used)`
- `base_minutes` 已包含 forced tighten 后的值。
- `bonus_minutes` = 当日未过期的 grants 之和。
- `locked` 由以下任一条件触发：剩余为 0 且仍在娱乐；处于宵禁；后端显式 lock。
- 时间窗口按 `Asia/Shanghai`；宵禁跨午夜（如 01:00–07:00）按跨日处理。

## 2. 接口

### 2.1 系统
- `GET /health` → `{status:"ok", version, db:"ok", server_time}`
- `GET /profile` → Profile
- `PATCH /profile` body `{name?, title?, exam_date?, daily_study_target_minutes?}` → Profile
- `GET /panel?date=YYYY-MM-DD` → 首页聚合：
  ```json
  {
    "date": "2026-11-02",
    "profile": { "...Profile..." },
    "quota": { "...Quota..." },
    "tasks": { "today": [Task], "overdue": [Task] },
    "diary": DiaryEntry | null,
    "weight": { "latest": WeightEntry | null, "change_7d": -0.5 },
    "stats": { "week_study_minutes": 420, "week_tasks_done": 12, "streak": 4 },
    "notifications": [Notification],
    "exam": { "date": "2026-12-19", "days": 47 }
  }
  ```

### 2.2 任务
- `GET /tasks?date=&from=&to=&status=&category=&limit=` → `[Task]`
- `POST /tasks` body：
  ```json
  {
    "title": "英语：阅读两篇",
    "description": null,
    "category": "study",
    "difficulty": 3,
    "attribute": "intelligence",
    "due_date": "2026-11-02",
    "estimated_minutes": 45,
    "repeat_rule": "none",
    "created_by": "user"
  }
  ```
  → Task
- `PATCH /tasks/{id}` body 任意 `{title?, description?, category?, difficulty?, attribute?, due_date?, estimated_minutes?, status?}` → Task
- `DELETE /tasks/{id}` → `{deleted:true}`（软删除，审计留存）
- `POST /tasks/{id}/complete` body `{note?, actual_minutes?, client_completed_at?}` →
  ```json
  {
    "task": Task,
    "rewards": { "exp": 40, "coins": 15, "attribute": "intelligence", "attribute_delta": 6, "streak_multiplier": 1.1 },
    "profile": Profile,
    "quota": Quota,
    "achievements_unlocked": [Achievement]
  }
  ```
- `POST /tasks/{id}/reopen` → Task（回退经验/积分/属性；审计记录；只允许当天完成的任务）
- `POST /tasks/{id}/postpone` body `{to_date}` → Task

### 2.3 日记
- `GET /diary?date=YYYY-MM-DD` → DiaryEntry | null
- `GET /diary/entries?from=&to=&limit=` → `[DiaryEntry]`
- `PUT /diary/{date}` body `{mood?, content, tags?}` → DiaryEntry（upsert）
- `PATCH /diary/{date}` body `{mood?, content?, tags?}` → DiaryEntry
- `POST /diary/{date}/comment`（Hermes）body `{ai_comment}` → DiaryEntry

### 2.4 体重
- `GET /weight?days=30` → `{entries:[WeightEntry], latest:WeightEntry|null, change_7d:number|null}`
- `PUT /weight/{date}` body `{weight_kg, body_fat?, waist_cm?, note?, source?}` → WeightEntry
- `DELETE /weight/{date}` → `{deleted:true}`

### 2.5 额度与策略
- `GET /quota?date=` → Quota
- `GET /policy?date=` → Policy
- `PUT /policy`（Hermes）body `{date?, daily_base_minutes?, curfew_start?, curfew_end?, session_max_minutes?, blacklist?, notes?, reason?}` → Policy
- `GET /policies?limit=20` → `[Policy]`（审计历史）
- `POST /quota/grant` body `{minutes, reason, actor?, expires_at?}` → Quota（增加 bonus）
- `POST /quota/consume` body `{minutes, source, device_id?, detail?}` → Quota（PC/手机上报娱乐使用；minutes 最多 60/次）
- `POST /quota/lock` body `{reason, until?, device_id?}` → Quota
- `POST /quota/unlock` body `{reason}` → Quota
- `POST /quota/requests` body `{minutes, reason, device_id?}` → QuotaRequest
- `GET /quota/requests?status=pending&limit=20` → `[QuotaRequest]`
- `POST /quota/requests/{id}/decide`（Hermes）body `{approve, minutes?, note?}` → `{request:QuotaRequest, quota:Quota}`

QuotaRequest:
```json
{
  "id": "uuid",
  "date": "2026-11-02",
  "minutes": 30,
  "reason": "想打完这一把",
  "device_id": "pc-main",
  "status": "pending",
  "decided_by": null,
  "decision_note": null,
  "created_at": "...",
  "decided_at": null
}
```

### 2.6 设备、心跳与命令
- `GET /devices` → `[Device]`
- `POST /devices/{device_id}/heartbeat` body：
  ```json
  {
    "name": "FA608UH",
    "platform": "windows",
    "app_version": "1.0.0",
    "state": { "locked": false, "running_blacklist": ["steam.exe"], "last_violation_at": null }
  }
  ```
  → `{device:Device, quota:Quota, policy:Policy, commands:[Command]}`
- `POST /devices/{device_id}/events` body `{type, severity?, payload?, at?}` → `{id}`
  - type 示例：`violation`、`lock`、`unlock`、`app_start`、`network_blocked`、`error`
- `GET /events?limit=50&device_id=` → `[Event]`
- `GET /commands?device_id=&status=pending` → `[Command]`
- `POST /commands` body `{device_id, type, payload?, ttl_seconds?}` → Command
  - type: `lock | unlock | reload | kill`
- `POST /commands/{id}/ack` body `{status:"done"|"failed", note?}` → Command

Device:
```json
{
  "id": "pc-main",
  "name": "FA608UH",
  "platform": "windows",
  "app_version": "1.0.0",
  "last_seen": "...",
  "online": true,
  "state": { }
}
```

Command:
```json
{
  "id": "uuid",
  "device_id": "pc-main",
  "type": "lock",
  "payload": { "reason": "quota_exhausted" },
  "status": "pending",
  "created_at": "...",
  "acked_at": null
}
```

### 2.7 统计、报告、成就
- `GET /stats/summary?range=7d` → 
  ```json
  {
    "range": "7d",
    "study_minutes": 420,
    "tasks_done": 12,
    "completion_rate": 0.75,
    "quota_used_minutes": 300,
    "weight_start": 104.7,
    "weight_end": 104.2,
    "weight_change": -0.5,
    "streak": 4,
    "daily": [
      { "date": "2026-11-02", "study_minutes": 120, "tasks_done": 3, "quota_used": 45 }
    ],
    "attributes": { "intelligence": 12, "stamina": 3, "willpower": 8, "focus": 10, "order": 5 },
    "heatmap": [ { "date": "2026-11-02", "value": 120 } ]
  }
  ```
- `GET /stats/heatmap?days=90` → `[{date, value}]`
- `GET /reports?limit=20` → `[Report]`
- `POST /reports`（Hermes）body `{title, content, kind?}` → Report
- `GET /achievements` → `[Achievement]`
- `GET /notifications?unread=1` → `[Notification]`（供 App 展示系统消息）
- `GET /export` → 全部数据 JSON（备份用）

Report:
```json
{ "id":"uuid", "title":"早报", "content":"...", "kind":"morning|evening|system", "created_at":"..." }
```

Achievement:
```json
{ "id":"first_task", "title":"第一次睁眼", "description":"完成第一个任务", "unlocked":true, "unlocked_at":"..." }
```

## 3. 后端规则摘要

1. 每日 0 点按 `Asia/Shanghai` 结算：生成新的一天 quota，继承当日 policy 或 fallback；连续零学习检查。
2. 完成学习任务才计入“学习日”；生活任务不算。
3. 审计表只增不删：完成任务、改策略、grant、lock、PC 事件都写 `audit_logs`。
4. 所有金额/分钟数用整数；客户端不得传奖励值。
5. 后端单用户，不做注册登录；token 放服务端环境变量。
6. 服务器时间必须以 `Asia/Shanghai` 输出；App 以 `server_time` 校准倒计时。

## 4. 错误码

| code | HTTP | 含义 |
|---|---|---|
| invalid_argument | 400 | 参数错误 |
| unauthorized | 401 | token 错误 |
| not_found | 404 | 对象不存在 |
| conflict | 409 | 重复/状态冲突 |
| policy_violation | 422 | 触碰三条硬底线 |
| rate_limited | 429 | 请求过频 |
| internal_error | 500 | 服务器错误 |
