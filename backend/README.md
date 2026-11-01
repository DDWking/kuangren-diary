# 狂人日记 · 云端后端

FastAPI + SQLAlchemy 2 + SQLite + Pydantic v2 实现的单用户后端，契约严格遵循仓库根目录 docs/api.md。

## 快速开始（Windows / PowerShell）

    cd backend
    .\run_local.ps1                        # 127.0.0.1:8090，自动建 .venv 并装依赖
    # 或手动：
    python -m venv .venv
    .\.venv\Scripts\python.exe -m pip install -r requirements.txt
    $env:KUANGREN_TOKEN = "test-token"
    .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8090

启动后：

- 健康检查（免鉴权）：http://127.0.0.1:8090/api/v1/health
- OpenAPI 文档：http://127.0.0.1:8090/api/v1/docs
- 其余接口统一前缀 /api/v1，需要请求头 Authorization: Bearer <KUANGREN_TOKEN>
- 生产基址：http://159.75.236.90/kuangren-api/api/v1（Caddy 反代到 127.0.0.1:8090）

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| KUANGREN_TOKEN | 无（本地兜底 dev-token） | Bearer Token，生产必填 |
| KUANGREN_DATA_DIR | backend/data | 数据目录，SQLite 文件为 <data_dir>/kuangren.db |
| KUANGREN_DB_PATH | <data_dir>/kuangren.db | 直接指定 SQLite 文件（可选） |
| KUANGREN_DATABASE_URL | 由上面推导 | 完整 SQLAlchemy 连接串（可选） |
| KUANGREN_HOST | 0.0.0.0 | 监听地址；本地自测用 127.0.0.1 |
| KUANGREN_PORT | 8090 | 监听端口 |
| KUANGREN_ONLINE_SECONDS | 90 | 心跳后多久算在线（秒） |

可复制 .env.example 为 .env（已被 .gitignore 忽略）。

## 统一响应

成功：

    {"ok": true, "data": ..., "server_time": "2026-11-02T02:50:34+08:00"}

失败：

    {"ok": false, "error": {"code": "policy_violation", "message": "..."}, "server_time": "..."}

错误码：invalid_argument(400) / unauthorized(401) / not_found(404) / conflict(409) / policy_violation(422) / rate_limited(429) / internal_error(500)。
时间一律 Asia/Shanghai，日期字段 YYYY-MM-DD，时间 ISO-8601 带 +08:00。

## 目录结构

    backend/
      app/
        main.py          # 应用装配、异常处理、CORS、Idempotency-Key 中间件
        settings.py      # 环境变量配置
        db.py            # SQLAlchemy engine/session、SQLite PRAGMA
        models.py        # 全部表（profiles/tasks/diary/weight/policies/quota/...）
        schemas.py       # Pydantic 请求模型 + 序列化函数
        timeutil.py      # 时区/日期/宵禁窗口工具
        errors.py, responses.py
        routers/         # health、meta、tasks、diary、weight、quota、devices、stats
        services/        # rules（奖励/额度/硬底线）、achievements、analytics、audit、settlement
      tests/             # pytest + TestClient，与 app 隔离的临时数据库
      requirements.txt
      Dockerfile
      run_local.ps1
      TESTS.md

## 服务端规则要点

- 奖励：exp = round((10 + 10 * difficulty) * 连击倍率)，coin = round(5 * difficulty * 连击倍率)，
  连击倍率 = 1 + 0.05 * min(streak, 10)，上限 1.5；属性 +difficulty*2。
- 等级：exp_next = 100 + 50 * (level - 1)；升级后循环结转。
- 完成任务幂等：已完成任务重复 complete 返回首次奖励，不重复加经验/积分/学习分钟；
  Idempotency-Key 可防网络重试造成的重复写。
- 娱乐额度：base（policy.daily_base_minutes，被强制收紧时减半）+ 当日有效 grants - used；
  每日 0 点（Asia/Shanghai）惰性结算生成新一天额度。
- 时区：所有“今天/日期”按 Asia/Shanghai 计算；宵禁窗口支持跨午夜。

## 三条硬底线（代码级拦截，测试见 TESTS.md）

1. 审计只增：audit_logs 只有写入/查询，没有删除接口；任务/体重删除是软删或仅业务数据，审计留存。
2. 03:00–07:00 必禁娱乐：PUT /policy 若让 curfew 未完整覆盖 03:00–07:00（调短、
   curfew_start 晚于 03:00、curfew_end 早于 07:00）直接 422 policy_violation；
   处于宵禁时 POST /quota/consume 一律 422，即使还有剩余额度。
3. 连续 3 个自然日零学习 → 次日基础娱乐额度减半（forced_tighten=true），
   完成任意学习任务后立即解除；新账号建立不满 3 天不追溯。

## Docker / 生产部署

    cd backend
    docker build -t kuangren-backend .
    docker run -d --name kuangren-backend ^
      -e KUANGREN_TOKEN=<生产 token> ^
      -v C:\data\kuangren:/app/data ^
      -p 127.0.0.1:8090:8090 kuangren-backend

Caddy 裸 IP 反代（示例，由运维执行，本仓库不包含生产配置）：

    http://159.75.236.90 {
        handle_path /kuangren-api/* {
            reverse_proxy 127.0.0.1:8090
        }
        handle /downloads/* {
            file_server {
                root /srv/downloads
            }
        }
    }

## 测试

    cd backend
    .\.venv\Scripts\python.exe -m pytest tests -q

覆盖与最近一次结果见 TESTS.md。
