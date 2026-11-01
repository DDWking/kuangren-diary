# 下载页改动说明

线上下载页是 WordPress 主题 `duola-pocket` 的 `/downloads/` 路由，不是后台文章。狂人日记只在主题里加了两处：

1. `functions.php` 的 `duola_pocket_apps()` 数组追加两个条目：
   - `slug=kuangren`：Android APK，`download=http://159.75.236.90/deaddata/kuangren/KuangrenDiary-1.0.0.apk`，`manifest=.../update.json`
   - `slug=kuangren-pc`：Windows 免安装 zip，`download=.../KuangrenDiary-PC-1.0.0.zip`
2. `downloads.php` 负责渲染这些条目（沿用主题原有卡片组件，无额外模板改动）。

主题源码本身包含站长的其他个人内容，不放进本仓库；部署时只把上述两处改动同步到服务器主题目录：

```bash
/srv/duola-dmeng/wordpress/wp-content/themes/duola-pocket/functions.php
/srv/duola-dmeng/wordpress/wp-content/themes/duola-pocket/downloads.php
```

改完用 `docker exec duola-wordpress php -l <file>` 检查语法，再刷新 `http://159.75.236.90/downloads/`。

产物上传目录：`/srv/duola-dmeng/data/deaddata/kuangren/`（宿主路径），对应 URL `http://159.75.236.90/deaddata/kuangren/`，属主 `www-data`。
