# 狂人日记 · Hermes 插件 + 技能安装说明

本目录包含 Hermes（呆）的狂人日记插件与技能，让呆能读写狂人日记后端：看面板、建任务、完成任务、记体重、写日记点评、调策略、批额度、写早晚报。

安装目标（生产服务器）：

- Hermes 安装目录：`/home/ubuntu/.hermes/hermes-agent`
- `HERMES_HOME=/home/ubuntu/.hermes`
- CLI：`~/.local/bin/hermes`（裸 ssh 没有 PATH，用全路径）
- 插件目录：`~/.hermes/plugins/kuangren-diary/`
- 技能目录：`~/.hermes/skills/kuangren/kuangren-diary/SKILL.md`
- gateway 是 user systemd 服务 `hermes-gateway`，装完必须重启

**部署由 Lead 执行；本说明只读、可回滚。不要改 `~/.hermes` 里除本文列出以外的任何文件。**

## 0. 前置检查（服务器上）

```bash
curl -s http://159.75.236.90/kuangren-api/api/v1/health
# 期望 {"ok":true,"data":{"status":"ok",...}}

~/.local/bin/hermes --version
systemctl --user is-active hermes-gateway
```

## 1. 上传文件到服务器

在开发机（Windows）项目根目录执行：

```powershell
scp -i C:/Users/32098/.ssh/id_ed25519 -r hermes ubuntu@159.75.236.90:/tmp/kuangren-hermes
```

如果已经用 `scripts/deploy-server.ps1` 把整个仓库同步到服务器，也可以直接在服务器上用仓库里的 `hermes/` 目录。

## 2. 备份（必须先做）

```bash
ts=$(date +%Y%m%d-%H%M%S)
cp ~/.hermes/SOUL.md    ~/.hermes/SOUL.md.bak-$ts
cp ~/.hermes/config.yaml ~/.hermes/config.yaml.bak-$ts
cp ~/.hermes/.env        ~/.hermes/.env.bak-$ts
ls -l ~/.hermes/*.bak-$ts
```

> SOUL.md 是呆的人格设定，只备份、不改。插件与技能不覆盖 SOUL.md。

## 3. 安装插件与技能

```bash
mkdir -p ~/.hermes/plugins/kuangren-diary
cp /tmp/kuangren-hermes/plugin/plugin.yaml  ~/.hermes/plugins/kuangren-diary/
cp /tmp/kuangren-hermes/plugin/__init__.py  ~/.hermes/plugins/kuangren-diary/

mkdir -p ~/.hermes/skills/kuangren/kuangren-diary
cp /tmp/kuangren-hermes/skill/SKILL.md ~/.hermes/skills/kuangren/kuangren-diary/SKILL.md

chmod 644 ~/.hermes/plugins/kuangren-diary/* ~/.hermes/skills/kuangren/kuangren-diary/SKILL.md
ls -la ~/.hermes/plugins/kuangren-diary/
```

## 4. 配置环境变量

编辑 `~/.hermes/.env`（追加，不要覆盖已有内容）：

```bash
# --- 狂人日记 ---
KUANGREN_API_URL=http://159.75.236.90/kuangren-api
KUANGREN_TOKEN=<生产 KUANGREN_TOKEN，与后端 .env 中的一致>
```

token 来源：开发机项目 `.secrets/env` 里的 `KUANGREN_TOKEN`（与 `KUANGREN_HERMES_TOKEN` 相同）。不要把 token 写进仓库、SKILL.md、聊天记录或本文档。

```bash
chmod 600 ~/.hermes/.env
grep -c '^KUANGREN_' ~/.hermes/.env   # 期望 2
```

说明：

- `KUANGREN_API_URL` 只写到 `/kuangren-api`，插件会自动补 `/api/v1`；写全了 `/kuangren-api/api/v1` 也兼容。
- gateway 用 `EnvironmentFile=-/home/ubuntu/.hermes/.env`，CLI 与 cron 也会加载 `~/.hermes/.env`。
- 可选：`KUANGREN_TIMEOUT=15`（秒，3–60）。

## 5. 启用插件

```bash
~/.local/bin/hermes plugins list --plain | grep -i kuangren || echo 'NOT FOUND'
~/.local/bin/hermes plugins enable kuangren-diary
```

`plugins enable` 会做两件事：

1. 把 `kuangren-diary` 加入 `config.yaml` 的 `plugins.enabled`；
2. 把 `kuangren` toolset 加入 `platform_toolsets`，让 12 个工具进入会话。

```bash
grep -A4 'plugins:' ~/.hermes/config.yaml
~/.local/bin/hermes plugins list --enabled --plain
```

> 服务器上的 `hermes-config-guard.timer` 每 5 分钟重写 config.yaml，但只维护固定的 8 个开关，不碰 `plugins` 段，所以插件启用状态不会被冲掉（已确认）。

## 6. 重启 gateway 并验证加载

```bash
systemctl --user restart hermes-gateway
sleep 3
systemctl --user status hermes-gateway --no-pager | head -15
journalctl --user -u hermes-gateway -n 100 --no-pager | grep -iE 'kuangren|plugin|error' | tail -20
```

期望：服务 active (running)，日志里没有 kuangren 相关 traceback。插件加载失败时 `hermes plugins list` 会显示 invalid / 原因。

## 7. 功能验证

在 Hermes 会话里对呆说：

- 「看下今天的狂人日记面板」→ 应调用 `get_panel`，回报剩余额度、今日任务、考试倒计时。
- 「列出我待办任务」→ `list_tasks`。
- 「这周学习怎么样」→ `get_stats`。

也可以只看工具是否注册：

```bash
~/.local/bin/hermes plugins list --json | python3 -m json.tool | grep -A3 kuangren
```

如果想不经过对话做只读冒烟，在开发机上（用生产 token）执行：

```powershell
$env:KUANGREN_API_URL='http://159.75.236.90/kuangren-api'
$env:KUANGREN_TOKEN=(Select-String -Path .secrets/env -Pattern '^KUANGREN_TOKEN=').Line.Split('=',2)[1]
backend/.venv/Scripts/python.exe -X utf8 hermes/tests/live_smoke.py
```

期望输出 `ALL LIVE SMOKE CHECKS PASSED`。

## 8. 安装两个 cron（早 07:30 / 晚 22:00）

先看现有 cron 的投递目标，挑一个能收到消息的（例如微信/ntfy）。**不要**把真实 chat_id 写进仓库或本文档。

```bash
~/.local/bin/hermes cron list --all
```

创建（把 `<DELIVER>` 换成上一步看到的 -> 后面的目标；先单测可改成 `--deliver local`）：

```bash
~/.local/bin/hermes cron create '30 7 * * *' '现在是早报时间。先用 get_panel 查今天的额度、今日任务、逾期任务和考试倒计时，再用 get_stats(range=7d) 看最近一周；用 3-5 句话给今日安排（先做什么、预计多久、今天还能玩多久，引用 remaining_minutes），然后调用 write_report(kind=morning) 写下早报。数字必须来自工具返回，不要编造。' --name kuangren-morning --deliver '<DELIVER>' --skill kuangren-diary

~/.local/bin/hermes cron create '0 22 * * *' '现在是晚报时间。先用 get_panel 和 get_stats(range=7d) 汇总今天完成的任务数、学习分钟、娱乐使用分钟和违规次数；如果今天零学习且 forced_tighten=true，提醒后果并给一个 25 分钟的最小动作；然后调用 write_report(kind=evening) 写晚报。最后用 list_pending_requests 查看待批额度申请，有的话逐条 decide_request 并在 note 写理由。数字必须来自工具返回。' --name kuangren-evening --deliver '<DELIVER>' --skill kuangren-diary
```

验证：

```bash
~/.local/bin/hermes cron list
~/.local/bin/hermes cron run <job-id>   # 手动触发一次，确认能投递
```

> cron prompt 里不能出现 gateway restart / stop 之类字样（create 会拒绝）；上面两条是安全的。

## 9. 回滚

```bash
~/.local/bin/hermes plugins disable kuangren-diary
rm -rf ~/.hermes/plugins/kuangren-diary ~/.hermes/skills/kuangren
# 恢复 env 与 config（若需要）
cp ~/.hermes/.env.bak-<ts>        ~/.hermes/.env
cp ~/.hermes/config.yaml.bak-<ts> ~/.hermes/config.yaml
systemctl --user restart hermes-gateway
# 删 cron：~/.local/bin/hermes cron remove <job-id>
```

只卸载插件、保留技能时，可只删 `~/.hermes/plugins/kuangren-diary` 并 disable。

## 10. 已知限制

- 工具只在 Hermes 会话里可用；cron 任务需要 `--skill kuangren-diary` 才会带上使用手册。
- 插件返回的工具结果是精简摘要（面板截断、任务只给关键字段），完整数据请用 App 或直接看报告。
- 后端三条硬底线（审计不可删、03:00–07:00 禁娱乐、连续 3 天零学习次日减半）由后端强制；插件遇到 `policy_violation` 会原样返回错误，不会绕过。
