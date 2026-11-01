# 部署与发布

本文记录「狂人日记」在宿主现有腾讯云服务器（159.75.236.90）上的实际部署方式。所有路径与端口都按当前线上环境写死；换服务器时改对应变量即可。

## 0. 拓扑

```
Android App / PC 执行器 / Hermes 插件
        │  HTTP Bearer Token
        ▼
http://159.75.236.90/kuangren-api/*   （Caddy，裸 IP :80）
        │  handle_path 剥掉 /kuangren-api
        ▼
kuangren-api:8090  （Docker，FastAPI + SQLite）
        │
        └─ /data/kuangren.db  ← 挂载 /srv/kuangren/data

下载文件： http://159.75.236.90/deaddata/kuangren/*
        → Caddy file_server → /srv/duola-dmeng/data/deaddata/kuangren
下载页：   http://159.75.236.90/downloads/  （WordPress 主题 duola-pocket）
```

## 1. 服务器目录与密钥

```bash
# 后端代码与数据（ubuntu 用户可写）
/srv/kuangren/backend/         # docker build context
/srv/kuangren/data/kuangren.db # SQLite，容器挂载为 /data

# 下载文件（Caddy 容器只读挂载）
/srv/duola-dmeng/data/deaddata/kuangren/

# Caddy 配置（实际挂载文件，不是根目录的旧副本）
/srv/duola-dmeng/docker/Caddyfile

# compose override（自动与 compose.yaml 合并）
/srv/duola-dmeng/compose.override.yaml

# 环境变量（含 KUANGREN_TOKEN，权限 600）
/srv/duola-dmeng/.env
```

首次准备：

```bash
sudo mkdir -p /srv/kuangren/{backend,data}
sudo chown -R ubuntu:ubuntu /srv/kuangren
sudo mkdir -p /srv/duola-dmeng/data/deaddata/kuangren
sudo chown -R www-data:www-data /srv/duola-dmeng/data/deaddata/kuangren
```

在 `/srv/duola-dmeng/.env` 追加（值从本地 `.secrets/env` 取，不要提交）：

```env
KUANGREN_TOKEN=<64 位随机 hex>
KUANGREN_HERMES_TOKEN=<同值或单独值>
```

## 2. Caddy

在 `/srv/duola-dmeng/docker/Caddyfile` 的 `ddwtech.online` 块与 `:80` 块中，`/deaddata` 之后、`handle { ... }` 之前各加：

```caddy
handle_path /kuangren-api/* {
    reverse_proxy kuangren-api:8090
}
```

校验并重载：

```bash
docker exec duola-caddy caddy validate --config /etc/caddy/Caddyfile
docker exec duola-caddy caddy reload --config /etc/caddy/Caddyfile
```

仓库里的 `deploy/server/Caddyfile` 是线上文件的副本，改动后同步过去。

## 3. Compose override

`deploy/server/compose.override.yaml` 定义 `kuangren-api` 服务，上传到 `/srv/duola-dmeng/compose.override.yaml` 后由 compose 自动合并。

关键点：

- build context: `/srv/kuangren/backend`
- 端口只暴露给 Docker 网络（不映射宿主端口，由 Caddy 反代）
- `KUANGREN_TOKEN` 缺失时 compose 直接报错，避免裸奔
- 数据卷：`/srv/kuangren/data:/data`
- 日志 json-file，10m × 3

## 4. 部署后端

在仓库根目录：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-server.ps1
```

脚本做三件事：

1. 上传 `backend/` 到 `/srv/kuangren/backend`。
2. 上传 compose override，执行 `docker compose up -d --build kuangren-api`，等健康检查。
3. 如果 `dist/` 有 APK/ZIP/update.json，上传到 `/srv/duola-dmeng/data/deaddata/kuangren/`。

只传下载文件：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-server.ps1 -SkipBackend
```

验证：

```bash
curl http://159.75.236.90/kuangren-api/api/v1/health
# {"ok":true,"data":{"status":"ok","db":"ok",...}}

docker exec kuangren-api curl -fsS http://127.0.0.1:8090/api/v1/health
docker logs --tail 50 kuangren-api
```

## 5. Android APK

前置：`android/keystore/kuangren-release.jks` 与 `android/keystore.properties` 已在本地（已 gitignore），签名为 release。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1
```

产物：

- `dist/KuangrenDiary-1.0.0.apk`
- `dist/update.json`（versionName / versionCode / apkUrl / sha256 / size / updated / releaseNotes）

再跑 `deploy-server.ps1 -SkipBackend` 上传，下载页与 App 内更新都会读到新的 `update.json`。

如果改了 `versionName`/`versionCode`，同时改 `android/app/build.gradle.kts` 的 `defaultConfig`，并给脚本传参：

```powershell
powershell -File scripts\build-android.ps1 -VersionName 1.0.1 -VersionCode 2
```

> App 的 `BuildConfig.API_BASE` 默认是 `http://159.75.236.90/kuangren-api`，token 从 `android/local.properties` 的 `kuangren.token` 注入。

## 6. PC 免安装包

```powershell
powershell -ExecutionPolicy Bypass -File scripts\package-pc.ps1
```

产出 `dist/KuangrenDiary-PC-1.0.0.zip`，解压后：

1. 编辑 `config.json` 的 `token`（或运行 `Install.ps1` 时注入）。
2. 双击 `Start-KuangrenPC.vbs` 启动托盘。
3. 需要管理员权限的断网/杀进程/锁屏操作，由 `Install.ps1` 创建计划任务时提权一次。

> 本机调试可用 `powershell -File pc\KuangrenPC.ps1 -DryRun -Once -NoTray`，只看决策不执行。

## 7. Hermes 插件

插件与技能位于 `hermes/`，线上安装路径：

```bash
mkdir -p ~/.hermes/plugins/kuangren-diary ~/.hermes/skills/kuangren/kuangren-diary
cp hermes/plugin/plugin.yaml hermes/plugin/__init__.py ~/.hermes/plugins/kuangren-diary/
cp hermes/skill/SKILL.md ~/.hermes/skills/kuangren/kuangren-diary/
chmod 644 ~/.hermes/plugins/kuangren-diary/*
~/.local/bin/hermes plugins enable kuangren-diary
```

在 `~/.hermes/.env`（权限 600）写入：

```env
KUANGREN_API_URL=http://159.75.236.90/kuangren-api
KUANGREN_TOKEN=<与后端一致的 token>
```

重启 gateway（SSH 非登录 session 下 `systemctl --user` 需要先导出 runtime 目录）：

```bash
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/$(id -u)/bus
systemctl --user restart --no-block hermes-gateway
```

验证：

```bash
~/.local/bin/hermes tools list | grep kuangren
~/.local/bin/hermes -z "只调用 get_panel 看今天面板" -t kuangren --cli
```

工具（12 个）：`get_panel`、`list_tasks`、`create_task`、`complete_task`、`log_weight`、`write_diary_comment`、`set_daily_policy`、`grant_quota`、`get_stats`、`list_pending_requests`、`decide_request`、`write_report`。写操作带 `Idempotency-Key` 与 `X-Actor: hermes`，全部进审计。

已配置两个 cron（`hermes cron list`）：

- `kuangren-morning`：`30 7 * * *`，skill `kuangren-diary`，早报。
- `kuangren-evening`：`0 22 * * *`，skill `kuangren-diary`，晚报 + 批额度申请。

> iLink 微信通道只在宿主先发过消息后有一个出站窗口；沉呆时的主动投递可能报 `session not ready`，所以 cron 的 deliver 同时挂了 ntfy 兜底。

## 8. 验收清单

- [ ] `/kuangren-api/api/v1/health` 返回 ok/db=ok
- [ ] `/downloads/` 出现「狂人日记」与「狂人日记 · PC」两张卡
- [ ] APK 链接可下载，SHA-256 与 `update.json` 一致，GitHub Release 可选
- [ ] 安装 APK 后首屏能拉到面板（无 token 报错）
- [ ] PC zip 解压后 `-DryRun -Once` 能连上后端并打印额度/策略
- [ ] Hermes 里发「我现在能玩多久」能返回额度
- [ ] `hermes tools list` 显示 kuangren 已启用；`hermes cron list` 有早晚报两个 job
- [ ] 服务器重启后容器自动拉起（restart: unless-stopped）

## 9. 回滚

- 后端：`docker compose stop kuangren-api`；需要回旧版本时，把 `/srv/kuangren/backend` 换成旧代码后 `docker compose up -d --build kuangren-api`。
- Caddy：每次修改前脚本会生成 `Caddyfile.bak.<时间戳>`，覆盖回去后 reload。
- 下载页：`functions.php.bak.<时间戳>`、`downloads.php.bak.<时间戳>` 在主题目录。
- 数据库：先停容器，复制 `/srv/kuangren/data/kuangren.db`，再动数据。

## 10. 安全边界

- 单用户系统，没有注册/登录；token 泄露等于全部数据泄露，只适合私人分发。
- APK 内嵌 token，不要公开仓库或公开下载链接给陌生人。
- 服务器只开 22/80/443 等既有端口；8090 不映射到公网。
- PC 执行器需要管理员权限，能改防火墙/杀进程/锁屏；它不是安全软件，管理员/安全模式可以绕过，定位是「高摩擦」。
