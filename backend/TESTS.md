# 后端测试记录

## 运行方式

    cd backend
    .\.venv\Scripts\python.exe -m pytest tests -q          # 全量
    .\.venv\Scripts\python.exe -m pytest tests -v          # 详细
    .\.venv\Scripts\python.exe -m pytest tests/test_hard_lines.py -v   # 只跑三条硬底线

测试使用 FastAPI TestClient，并通过 conftest.py 在 import 应用前把 KUANGREN_DATA_DIR
指向临时目录、token 设为 test-token，每个用例自动 drop/create 全部表，互不影响。
服务器时钟由 freeze_time fixture 固定（默认当天 14:00，避开真实时间的宵禁时段）。

## 最近一次结果

    60 passed, 1 warning in 3.69s

（1 warning 为 fastapi TestClient 依赖 httpx 的 StarletteDeprecationWarning，不影响功能。）

## 覆盖清单

| 文件 | 覆盖内容 |
| --- | --- |
| tests/test_health_auth.py | /health 免鉴权与 envelope、业务接口 401、默认 profile、未知路由 404、参数校验 400、CORS、PATCH /profile、X-Actor、Idempotency-Key |
| tests/test_tasks.py | 任务增删改查/过滤、complete 奖励公式、重复 complete 不重复加经验、actual_minutes 与 estimated_minutes 计入学习分钟、非 study 不计、reopen 回退与隔天拒绝、软删与审计 |
| tests/test_diary_weight.py | 日记 PUT/PATCH/comment/entries、upsert 保留 ai_comment、tags 去重、体重 upsert/change_7d/DELETE、校验 |
| tests/test_policy_quota.py | policy fallback 默认值、更新/历史/审计、curfew 调短 422、合法收紧与放宽、范围校验、grant 追加上限、consume 正常/超限/单次上限、宵禁拦截、显式 lock/unlock、额度用尽锁定、额度申请审批/驳回与重复决策 409 |
| tests/test_devices.py | 心跳注册与在线判定、device_id 校验、violation 事件累计第 3 次自动下发 lock 命令、命令创建/拉取/ack 幂等/状态冲突、TTL 过期转 failed、心跳返回待执行命令 |
| tests/test_stats_meta.py | /panel 聚合、/stats/summary、/stats/heatmap、/reports、成就（含日记成就补评估）、通知未读/已读、/export |
| tests/test_hard_lines.py | 三条硬底线逐条验证（见下） |
| tests/test_units.py | 奖励/升级/连击公式、宵禁窗口（含跨午夜与 window_covers）、parse_range、时间解析 |

## 三条硬底线对应测试

1. 审计只增（不可删除）
   - test_hard_lines.py::test_hard_line_1_audit_is_append_only
   - 任务软删后 task.create/task.complete/task.delete 审计仍存在；DELETE /api/v1/audit_logs、
     /audit_logs/xxx、/events/xxx、/policies/xxx 全部 404；审计条数只增不减且 id 唯一；
     OpenAPI 中不存在 audit 路径，events/policies/devices 无 DELETE 方法。
2. 03:00–07:00 必禁娱乐
   - test_hard_lines.py::test_hard_line_2_curfew_cannot_be_shortened
   - test_hard_lines.py::test_hard_line_2_curfew_blocks_quota_use
   - test_hard_lines.py::test_hard_line_2_curfew_early_morning
   - test_policy_quota.py::test_policy_cannot_shorten_curfew
   - 调短 curfew（start 晚于 03:00 / end 早于 07:00 / 不覆盖）返回 422 policy_violation，
     且拒绝后不落库；00:30–08:00 收紧后凌晨 00:45 额度锁定、consume 返回 422。
3. 连续 3 天零学习次日额度减半
   - test_hard_lines.py::test_hard_line_3_new_account_not_tightened
   - test_hard_lines.py::test_hard_line_3_three_zero_study_days_half_quota
   - test_hard_lines.py::test_hard_line_3_any_study_within_three_days_prevents_tighten
   - test_hard_lines.py::test_hard_line_3_released_after_study
   - 建号 5 天且近 3 天无学习 → forced_tighten=true、base 180→90、消费上限 90；
     近 3 天有学习记录不收紧；完成任意学习任务后立即恢复 180。

## 本地真实进程冒烟（uvicorn 127.0.0.1:8090）

命令：

    $env:KUANGREN_TOKEN = "local-test-token"
    .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8090

记录（2026-11-02）：

- GET /api/v1/health → 200 {"status":"ok","version":"1.0.0","db":"ok"}（无鉴权）
- GET /api/v1/profile（无 token）→ 401 unauthorized
- GET /api/v1/profile（Bearer）→ 默认 profile（level 1 / exp 0 / exam_date 2026-12-19）
- POST /api/v1/tasks → 200，exp_reward 预览正确
- POST /api/v1/tasks/{id}/complete → exp 30 / coins 10 / total_study_minutes 20
- 再次 complete 同一任务 → exp 仍 30、total_study_minutes 仍 20（幂等，不刷分）
- PUT /api/v1/policy {"curfew_start":"05:00"} → 422 policy_violation（硬底线生效）
- GET /api/v1/panel、/stats/summary?range=7d、/export → 200 且字段齐全
- GET /api/v1/audit（不存在的接口）→ 404 not_found（没有审计查看/删除接口）

## 与 docs/api.md 的核对与补充实现

逐条核对 docs/api.md 第 192–357 行（2.1 系统 ~ 2.7 统计报告成就），**44 条正文端点全部实现**，
路径、方法、请求体、响应字段与契约一致；OpenAPI 共 38 个 path（含多个方法）。本地已验证
GET /api/v1/health、/profile、/tasks、/panel、/stats/summary、/export 等真实进程响应。

实现上的补充与取舍（均为兼容性补充，不改变契约字段）：

- `GET /health` 同时提供 `/api/v1/health`：Caddy 反代后验收地址为
  http://159.75.236.90/kuangren-api/api/v1/health（spec 验收项），两者都免鉴权。
- 额外便利接口：`GET /`（服务信息，无鉴权）、`POST /api/v1/notifications/read`（批量标记已读，
  契约只列了 GET /notifications）。
- `PATCH /tasks/{id}` 允许 status=pending|skipped，但 status=done 返回 400 并提示改用
  `POST /tasks/{id}/complete`：保证奖励/学习分钟/成就/审计走唯一入账路径，避免绕过奖励与幂等。
- `POST /devices/{device_id}/events` 在契约的 `{id}` 之外额外返回 `violations_today`
  （PC 端可直接判断是否该锁屏，多返回字段不影响契约）。
- `GET /achievements` 读取时先补评估成就（幂等），保证写日记/体重等非任务动作也能解锁对应成就。
- `PUT /policy` 的 curfew 校验按字面规则：curfew_start 必须不晚于 03:00、curfew_end 必须不早于
  07:00，因此 API 层不允许配置跨午夜 curfew（例如 23:00–07:00）；内部窗口判定函数支持跨午夜
  （见 tests/test_units.py::test_is_in_window_cross_midnight）。
- Idempotency-Key 中间件对所有写方法（POST/PUT/PATCH/DELETE）生效，范围大于契约要求的
  complete/consume/grant。
- 未设置 KUANGREN_TOKEN 时使用开发兜底 dev-token 并在启动日志告警；生产必须设置真实 token
  （见 .env.example）。

