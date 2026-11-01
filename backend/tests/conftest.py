"""pytest 公共装置：隔离数据目录、清库、可用 token、可控时钟。"""

from __future__ import annotations

import os
import sys
import tempfile
from datetime import datetime, time as dtime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

# 必须在 import app.* 之前设置环境变量（settings 在导入时读取）
_TMP = Path(tempfile.mkdtemp(prefix="kuangren-backend-tests-"))
os.environ["KUANGREN_DATA_DIR"] = str(_TMP)
os.environ["KUANGREN_TOKEN"] = "test-token"
os.environ.pop("KUANGREN_DB_PATH", None)
os.environ.pop("KUANGREN_DATABASE_URL", None)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import timeutil  # noqa: E402
from app.db import Base, SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture(autouse=True)
def clean_db():
    """每个用例一套干净的表。"""
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture()
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def db():
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture()
def freeze_time(monkeypatch):
    """把服务器时钟固定到某个时刻（默认今天 14:00，非宵禁时段）。"""

    def _freeze(hour: int = 14, minute: int = 0, day=None) -> datetime:
        target_day = day or timeutil.today()
        moment = datetime.combine(target_day, dtime(hour, minute), tzinfo=timeutil.TZ)
        monkeypatch.setattr(timeutil, "now", lambda: moment)
        return moment

    return _freeze
