from __future__ import annotations

import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

try:  # python-dotenv 是可选的：没有安装也不影响用环境变量启动
    from dotenv import load_dotenv

    load_dotenv(BASE_DIR / ".env", override=False)
except Exception:  # pragma: no cover - 容错
    pass


def _env(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip()


class Settings:
    """集中管理配置。所有路径/端口/token 都可通过环境变量覆盖。"""

    def __init__(self) -> None:
        self.app_name = "kuangren-diary-backend"
        self.version = "1.0.0"
        self.timezone = "Asia/Shanghai"

        # 鉴权：Bearer token（生产必填；本地未配置时退化为 dev-token 并打警告）
        self.token = _env("KUANGREN_TOKEN", "")

        # 监听：默认 0.0.0.0:8090（给 Caddy 反代），本地自测可用 KUANGREN_HOST 覆盖
        self.host = _env("KUANGREN_HOST", "0.0.0.0")
        self.port = int(_env("KUANGREN_PORT", "8090") or 8090)

        # 数据目录：默认 ./data，SQLite 文件落在 <data_dir>/kuangren.db；重启不丢数据
        self.data_dir = _env("KUANGREN_DATA_DIR", "") or str(BASE_DIR / "data")
        db_path = _env("KUANGREN_DB_PATH", "") or str(Path(self.data_dir) / "kuangren.db")
        self.db_path = db_path
        database_url = _env("KUANGREN_DATABASE_URL", "")
        if not database_url:
            database_url = "sqlite:///" + Path(db_path).resolve().as_posix()
        self.database_url = database_url

        # 设备在线判定
        self.online_seconds = int(_env("KUANGREN_ONLINE_SECONDS", "90") or 90)

        # 默认值 / 硬底线
        self.default_exam_date = "2026-12-19"
        self.default_daily_study_target_minutes = 300
        self.default_daily_base_minutes = 180
        self.default_curfew_start = "01:00"
        self.default_curfew_end = "07:00"
        self.default_session_max_minutes = 60

        # 三条硬底线之一：03:00-07:00 禁止娱乐，不可缩短
        self.hard_curfew_start = "03:00"
        self.hard_curfew_end = "07:00"

        # 策略参数范围
        self.min_daily_base_minutes = 0
        self.max_daily_base_minutes = 480
        self.min_session_max_minutes = 20
        self.max_session_max_minutes = 180
        self.max_consume_minutes = 60
        self.max_grant_minutes = 1440

        # 连击/奖励
        self.streak_cap = 10
        self.streak_step = 0.05
        self.streak_multiplier_cap = 1.5

    @property
    def effective_token(self) -> str:
        # 本地开发兜底：没有配置 token 时用 dev-token，启动日志会警告。
        return self.token or "dev-token"

    @property
    def uses_dev_token(self) -> bool:
        return not self.token


settings = Settings()
