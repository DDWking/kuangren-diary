==========================================================
 狂人日记 PC 执行器（KuangrenPC）使用说明
==========================================================

一、这是什么
----------------------------------------------------------
这是“狂人日记”自律系统在 Windows 电脑上的执行器：
  * 托盘常驻，不显示控制台窗口；
  * 每 15 秒和后端同步一次状态；
  * 监控黑名单里的娱乐程序（Steam、WeGame、无畏契约、
    Deadlock、Minecraft、B 站/抖音桌面端等，可自定义）；
  * 到点或娱乐额度用完时：先断掉这些程序的出站网络，
    再结束进程，并弹托盘气泡告诉你；
  * 同一自然日第 3 次违规，会锁定 Windows 工作站（锁屏）；
  * 所有动作都会上报后端审计，不能偷偷删记录；
  * 托盘菜单可“申请延时”（填分钟和理由，提交给管理员审批）。

系统要求：Windows 10/11 + Windows PowerShell 5.1（系统自带），
不需要 Python，也不需要 .NET SDK。

二、安装（解压后一次完成）
----------------------------------------------------------
1. 把 zip 解压到一个固定目录（例如 D:\KuangrenPC）；
2. 右键 Install.ps1 →“使用 PowerShell 运行”
   （会弹一次 UAC，选“是”）；
3. 安装程序会把程序复制到 C:\ProgramData\KuangrenPC，
   并提示输入后端 token（输入时不回显，直接回车可跳过）；
4. 完成后任务栏右下角出现“狂”字托盘图标，计划任务
   “KuangrenPC”会在每次登录时自动启动它。

如果不想立刻启动，可加 -SkipStart 参数：
  powershell -ExecutionPolicy Bypass -File .\Install.ps1 -SkipStart

三、日常使用
----------------------------------------------------------
托盘图标（右键）：
  * 状态：查看额度、剩余分钟、违规次数、在线状态、日志路径；
  * 立即刷新：马上同步一次后端；
  * 申请延时：填 5-120 分钟和理由，提交审批；
  * 打开日志：用记事本打开日志文件；
  * 退出：完全退出托盘（退出后不监控，请谨慎）。

气泡提示出现“已介入”，说明检测到黑名单程序在额度用完后
还在运行，已断网并结束它。请回去学习，不要在电脑前内耗。

四、配置（config.json）
----------------------------------------------------------
安装后配置文件：C:\ProgramData\KuangrenPC\config.json
改完保存即可，下一次心跳自动生效；也可在托盘点“立即刷新”，
或重启托盘。

主要字段：
  api_base              后端地址，默认 http://159.75.236.90/kuangren-api
  token                 登录凭证（安装时注入；不要发给别人）
  device_id             设备标识，默认 pc-main
  device_name           设备显示名，默认计算机名
  heartbeat_seconds     心跳间隔秒数，默认 15
  enforcement_check_seconds  检测间隔秒数，默认 5
  usage_report_seconds  娱乐时长上报间隔秒数，默认 60
  blacklist             黑名单进程名数组，支持 * 通配符，可自行增删
  enforcement.block_network      违规时是否断网（默认 true）
  enforcement.kill_process       违规时是否结束进程（默认 true）
  enforcement.lock_workstation   是否允许锁屏（默认 true）
  enforcement.lock_on_violations 当日第几次违规锁屏（默认 3）
  tray.show_balloon     是否弹托盘气泡（默认 true）
  log.max_size_mb       单个日志文件上限 MB（默认 5）
  log.max_files         保留几个滚动日志（默认 2）

注意：修改 blacklist 时写进程名（不带路径），例如 "steam.exe"。
不确定进程名？打开任务管理器→详细信息，看“名称”列。

五、演练 / 排障
----------------------------------------------------------
1) 演练模式（只打印将执行的动作，不断网/不杀进程/不锁屏）：
   powershell -ExecutionPolicy Bypass -File "C:\ProgramData\KuangrenPC\KuangrenPC.ps1" -DryRun -Once

2) 托盘冒烟测试（只启动图标 3 秒后退出）：
   powershell -ExecutionPolicy Bypass -File "C:\ProgramData\KuangrenPC\KuangrenPC.ps1" -TraySmokeTest -NoTray

3) 查看日志（默认位置）：
   %LOCALAPPDATA%\KuangrenPC\logs\kuangren-pc.log

4) 常见问题：
   * 托盘不见了：任务管理器里找 powershell.exe，或重新登录；
     也可以手动运行 C:\ProgramData\KuangrenPC\Start-KuangrenPC.vbs。
   * 一直显示“离线”：检查 config.json 的 token、api_base，
     以及电脑能不能访问后端；日志里会有具体错误。
   * 游戏没有被断网：确认游戏进程名在 blacklist 里；防火墙规则
     组名是 KuangrenPC，可用 netsh advfirewall firewall show rule
     name=all group=KuangrenPC 查看。
   * 安装计划任务失败：Install.ps1 会用 HKCU Run 启动项兜底，
     但仍建议重跑 Install.ps1（管理员）。

六、卸载
----------------------------------------------------------
以管理员身份运行 C:\ProgramData\KuangrenPC\Uninstall.ps1。
加 -KeepConfig 可保留 config.json 和日志：
  powershell -ExecutionPolicy Bypass -File .\Uninstall.ps1 -KeepConfig

七、局限（请诚实面对）
----------------------------------------------------------
这不是防黑客系统，而是“提高摩擦”的自律工具：
  * 拥有管理员权限的人可以绕过（改配置、删计划任务、重装系统）；
  * 只监控本机 Windows，手机/平板/主机不在范围内；
  * 只对黑名单进程生效，未列入的程序不会被处理；
  * 断网用的是本机 Windows 防火墙出站规则，重启后可被清理；
  * 请把 token 当作密码保管，不要截图/外发 config.json。

最终目标不是“赢过工具”，而是把省下的时间拿去学习。
