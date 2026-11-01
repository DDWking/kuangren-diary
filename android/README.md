# 狂人日记 · Android App

单 Activity + Jetpack Compose 的客户端，对应服务端 `/kuangren-api/api/v1`。

## 构建

环境：JDK 21（Gradle 用）、Android SDK platform 36、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.0。

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
cd android
& 'C:\Users\32098\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' :app:assembleDebug --console=plain
& 'C:\Users\32098\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' :app:assembleRelease --console=plain
```

产物：

- debug：`app/build/outputs/apk/debug/app-debug.apk`
- release：`app/build/outputs/apk/release/app-release.apk`

release 签名读取 `android/keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`，文件名 `keystore/kuangren-release.jks`）。文件缺失时自动回退 debug 签名，保证构建不失败。两者都在 `.gitignore` 中，不要提交。

## API 配置

`android/local.properties`（不提交）里可配置：

```properties
sdk.dir=C\:\\Users\\32098\\AppData\\Local\\Android\\Sdk
kuangren.apiBase=http://159.75.236.90/kuangren-api
kuangren.token=***
```

构建时写入 `BuildConfig.API_BASE` / `BuildConfig.API_TOKEN` / `BuildConfig.DEVICE_ID`。App 内也可在**设置 → 关于**连点版本号 5 次解锁高级设置后改服务器地址与 token（存 SharedPreferences，清空可恢复 BuildConfig 默认值）。

## 结构

```
com.ddw.kuangren
├── AppContainer.kt / KuangrenApp.kt / MainActivity.kt / Notifications.kt
├── data/       Models（kotlinx.serialization）、ApiClient（OkHttp）、CacheStore、
│               OfflineQueue、Repository、Util（Clocks 等）
├── ui/         AppNav（5 Tab + 详情/专注/统计/设置）、MainViewModel、Components、
│               Inputs、theme/Theme、五个主页面 + 焦点页 + 详情页 + 设置/统计
├── widget/     WidgetCache、WidgetPaint、WidgetUpdater、5 个 Glance 小组件
├── work/       SyncWorker（每小时同步）
├── focus/      FocusKeepAliveService（番茄计时前台服务）
└── lock/       LockPrefs、UsageStatsHelper、OverlayController、SoftLockService
```

## 功能

- **面板**：等级/经验环、距 2027 考研倒计时、五维雷达、今日娱乐额度环（申请延时）、今日任务 5 条、今日一句、快捷操作。
- **任务**：今日/本周/全部/已完成筛选，新建/编辑/完成/重开/推迟/删除，难度与分类色条，≥25 分钟任务完成时可选直接完成或开番茄。
- **专注**：25/45/60 分钟番茄，倒计时/正计时，暂停/继续/完成/放弃，雨声/咖啡馆/风扇本地合成白噪音，前台服务保证通知栏倒计时存活，绑定任务完成后自动 POST complete。
- **日记**：按日期读写、5 档心情、标签、本地草稿自动保存、呆的批注只读卡、历史列表。
- **身体**：最新体重/7 天变化、30 天折线图（目标 85kg 虚线）、体脂/腰围/BMI、记录弹窗、历史列表。
- **系统**：呆的消息、事件日志、本周统计、成就网格、设置入口。
- **统计**：7/30/90 天切换、四格汇总、每日柱状、热力图。
- **离线**：所有读接口落 SharedPreferences 缓存，写操作离线进本地队列（≤100 条，含 Idempotency-Key）联网后按序重放。
- **小组件**：4×2 面板 / 2×2 额度 / 1×1 等级 / 4×1 一句话 / 4×4 完整，读 WidgetCache，WorkManager 每小时 + App 前后台刷新，点击打开对应 Tab。

## 软锁（手机端自律）

设置里开启后需要两个权限：

1. **使用情况访问**：设置 → 软锁 → 使用情况访问（用于识别当前前台娱乐 App）。
2. **悬浮窗**：设置 → 软锁 → 悬浮窗（用于全屏覆盖层）。

开启后 `SoftLockService` 前台服务每 5 秒检查一次；当黑名单 App 在前台且（娱乐额度为 0 / 处于宵禁）时显示全屏 `系统锁定` 覆盖层，可「申请延时」（打开 App 面板提交申请）或「返回桌面」。返回/强关后 5 分钟内再打开同一 App 记 1 次违规，当天 3 次违规 → 通知 + 上报事件 + 30 分钟冷却（覆盖层显示「冷却中」）。

黑名单默认：王者荣耀、和平精英、原神、哔哩哔哩、抖音、快手、腾讯视频、爱奇艺；服务端 policy.blacklist 下发时优先使用服务端列表。

软锁不防卸载、不防安全模式，只是提醒工具。
