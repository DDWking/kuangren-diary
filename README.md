# 狂人日记（Kuangren Diary）

给**宿主**做的学习自律系统：Android App + Windows PC 执行器 + 云端后端 + Hermes 插件 + 下载页。

> 它不负责让你变好。它只负责让你没法假装今天还没开始。

## 是什么

- **面板**：考研倒计时、等级/经验、五维、今日娱乐额度、今日任务。
- **任务**：学习/身体/生活/心境任务，难度 1–5，完成给 EXP/积分/属性，不搞拍照审查。
- **日记**：每天一篇，系统（呆）回一句。
- **身体**：体重/体脂/腰围趋势。
- **额度**：没有固定规则，由 Hermes 按对话动态给；后端只守三条底线（审计不可关、03:00–07:00 不能玩、连续 3 天零学习强制收紧）。
- **执行**：PC 托盘程序到点断网/关游戏，3 次违规锁屏；手机 App 软锁（悬浮窗 + 使用统计）。
- **Hermes**：微信里直接说“今天背了 50 个单词”“记一下 75.2”“我现在能玩多久”，呆会调工具完成。

## 组成

| 目录 | 内容 |
|---|---|
| `android/` | Kotlin + Jetpack Compose App，含 5 个桌面小组件 |
| `backend/` | FastAPI + SQLite 云端后端，Caddy 反代到 `/kuangren-api` |
| `pc/` | Windows PowerShell 托盘执行器，免安装 zip |
| `hermes/` | Hermes 插件与技能，给“呆”用 |
| `site/` | WordPress 下载页主题改动 |
| `docs/` | API 契约、产品规格、Android UI、部署说明 |
| `scripts/` | 构建/部署/打包脚本 |

## 快速开始（开发）

### 后端
```bash
cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # 改 TOKEN
uvicorn app.main:app --host 0.0.0.0 --port 8090 --reload
```
打开 `http://127.0.0.1:8090/api/v1/health`。

### Android
```bash
cd android
# local.properties 里写：
#   kuangren.apiBase=http://159.75.236.90/kuangren-api
#   kuangren.token=<你的 token>
./gradlew :app:assembleDebug
```
APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`。

### PC
```powershell
cd pc
# 编辑 config.json（apiBase/token/deviceId）
powershell -ExecutionPolicy Bypass -File .\Install.ps1
```

## 生产部署

- 后端：Docker compose 服务 `kuangren-api`，代码在 `/srv/kuangren/backend`，SQLite 在 `/srv/kuangren/data`；Caddy 裸 IP `:80` 增加 `/kuangren-api/*` 反代。
- APK：`scripts/build-android.ps1` → 上传到 `/srv/duola-dmeng/data/deaddata/kuangren/`（URL 为 `/deaddata/kuangren/`）。
- PC zip：`scripts/package-pc.ps1` → 上传到 `/srv/duola-dmeng/data/deaddata/kuangren/`。
- 下载页：`site/theme/` 修改后同步到服务器主题目录。

详见 `docs/deploy.md`。

## 安全与隐私

- 单用户系统，没有注册登录；所有请求用 Bearer token。
- 数据保存在宿主自己的腾讯云服务器 SQLite 中，不接第三方。
- APK 内含访问 token，只适合私人分发；仓库不提交真实 token（`local.properties` / `.secrets/` 已忽略）。
- PC 执行器需要管理员权限来改防火墙/杀进程；安装时 UAC 一次。它不是安全软件，管理员/安全模式可以绕过——这是“高摩擦”，不是“不可破解”。

## 三条底线

1. 审计/事件/App 不可通过 API 删除或停用。
2. 03:00–07:00 不能娱乐（策略可以更严，不能更松）。
3. 连续 3 个自然日没有完成任何学习任务，第 4 天基础额度强制减半。

## License

MIT © 2026 DDWking