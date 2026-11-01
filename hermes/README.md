# Hermes 插件（呆 · 狂人日记）

让呆能通过 12 个工具操作狂人日记后端，并带一份使用手册（技能）。

## 文件

```
hermes/
├── plugin/
│   ├── plugin.yaml     # Hermes 插件清单：name=kuangren-diary，12 个工具，2 个环境变量
│   └── __init__.py     # register(ctx) 注册工具；urllib 调后端 /api/v1，X-Actor: hermes
├── skill/
│   └── SKILL.md        # 呆的使用手册：动态额度原则、三条底线、早晚报流程、回复风格
├── tests/
│   ├── test_plugin.py  # 49 项离线自测（stdlib mock HTTP server + stub tools.registry）
│   └── live_smoke.py   # 生产只读冒烟（get_panel/list_tasks/get_stats/list_pending_requests）
├── install.md          # 服务器安装/启用/验证/cron/回滚步骤
└── README.md
```

## 工具（toolset = `kuangren`）

| 工具 | 后端接口 |
|---|---|
| get_panel | GET /panel |
| list_tasks | GET /tasks |
| create_task | POST /tasks |
| complete_task | POST /tasks/{id}/complete |
| log_weight | PUT /weight/{date} |
| write_diary_comment | POST /diary/{date}/comment |
| set_daily_policy | PUT /policy |
| grant_quota | POST /quota/grant |
| get_stats | GET /stats/summary |
| list_pending_requests | GET /quota/requests?status=pending |
| decide_request | POST /quota/requests/{id}/decide |
| write_report | POST /reports |

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `KUANGREN_API_URL` | 是 | 后端基址，默认 `http://159.75.236.90/kuangren-api`；插件自动补 `/api/v1` |
| `KUANGREN_TOKEN` | 是 | Bearer token，与后端一致；插件用它拼 `Authorization` 与 `X-Actor: hermes` |
| `KUANGREN_TIMEOUT` | 否 | HTTP 超时秒数，默认 15（3–60） |

没有 token 时 `check_fn` 返回 False，工具不会暴露给模型（避免会话里出现一堆必然失败的工具）。

## 本地测试（开发机）

```powershell
# 离线自测：49 项，不需要网络
backend/.venv/Scripts/python.exe -X utf8 hermes/tests/test_plugin.py

# 生产只读冒烟：先设置环境变量再跑
$env:KUANGREN_API_URL='http://159.75.236.90/kuangren-api'
$env:KUANGREN_TOKEN=(Select-String -Path .secrets/env -Pattern '^KUANGREN_TOKEN=').Line.Split('=',2)[1]
backend/.venv/Scripts/python.exe -X utf8 hermes/tests/live_smoke.py
```

验收结果：离线 49/49 通过；生产冒烟 4 个只读工具全部 PASS（2026-11-02）。

## 安装

见 [install.md](install.md)：备份 SOUL.md/config.yaml/.env → 拷插件与技能 → 写 `~/.hermes/.env` → `hermes plugins enable kuangren-diary` → 重启 `hermes-gateway` → 建 07:30/22:00 两个 cron。

## 设计要点

- 只调 docs/api.md 定义过的接口，不读写数据库、不改 `~/.hermes` 其他配置。
- 三条硬底线写进工具描述：审计不可删、03:00–07:00 禁娱乐、连续 3 天零学习次日基础额度减半；遇到 `policy_violation` 原样返回，不重试绕过。
- 额度是动态的：`get_panel` 描述里明确要求“先查面板再决定”，避免模型凭记忆回答。
- 写操作带 `Idempotency-Key`（complete/grant/decide），网络重试不会重复记账。
