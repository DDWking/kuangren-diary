"""FastAPI 入口。

启动：uvicorn app.main:app --host 0.0.0.0 --port 8090
所有业务路由挂在 /api/v1 下（Caddy 会把 /kuangren-api 前缀剥掉，后端不再重复加）。
"""

from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from datetime import timedelta

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException
from sqlalchemy import delete

from . import timeutil
from .db import SessionLocal, init_db
from .errors import ApiError
from .models import IdempotencyRecord
from .responses import envelope, error_payload
from .routers import devices, diary, health, meta, quota, stats, tasks, weight
from .routers.deps import require_token
from .services import settlement
from .settings import settings

logger = logging.getLogger("kuangren")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
IDEMPOTENCY_TTL_DAYS = 7


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    with SessionLocal() as db:
        settlement.ensure_day(db)
        cutoff = timeutil.date_str(timeutil.today() - timedelta(days=IDEMPOTENCY_TTL_DAYS))
        db.execute(delete(IdempotencyRecord).where(IdempotencyRecord.created_at < cutoff))
        db.commit()
    if settings.uses_dev_token:
        logger.warning(
            "未设置 KUANGREN_TOKEN，已退化为 dev-token（仅本地开发可用）。生产环境必须配置 KUANGREN_TOKEN。"
        )
    logger.info("数据目录：%s；数据库：%s", settings.data_dir, settings.database_url)
    yield
    logger.info("服务退出")


def _validation_message(exc: RequestValidationError) -> str:
    errors = exc.errors()
    if not errors:
        return "参数错误"
    first = errors[0]
    loc = ".".join(str(part) for part in first.get("loc", []) if part not in ("body", "query", "path"))
    type_map = {
        "missing": "缺少必填字段",
        "int_parsing": "应为整数",
        "float_parsing": "应为数字",
        "bool_parsing": "应为布尔值",
        "list_type": "应为数组",
        "dict_type": "应为对象",
        "string_type": "应为字符串",
        "json_invalid": "JSON 格式错误",
    }
    friendly = type_map.get(str(first.get("type", "")), str(first.get("msg", "参数错误")))
    return f"参数错误：{loc} {friendly}".strip() if loc else f"参数错误：{friendly}"


def _http_detail(exc: StarletteHTTPException) -> tuple[str, str]:
    status = exc.status_code
    detail = exc.detail if isinstance(exc.detail, str) else ""
    if status == 401:
        return "unauthorized", detail or "未认证"
    if status == 404:
        return "not_found", detail or "接口不存在"
    if status == 409:
        return "conflict", detail or "状态冲突"
    if status == 422:
        return "policy_violation", detail or "参数不受支持"
    if status == 429:
        return "rate_limited", detail or "请求过于频繁"
    if status == 400:
        return "invalid_argument", detail or "参数错误"
    if status >= 500:
        return "internal_error", detail or "服务器内部错误"
    return "invalid_argument", detail or f"请求失败（HTTP {status}）"


def create_app() -> FastAPI:
    app = FastAPI(
        title="狂人日记 · 云端后端",
        version=settings.version,
        lifespan=lifespan,
        docs_url="/api/v1/docs",
        redoc_url=None,
        openapi_url="/api/v1/openapi.json",
    )

    @app.middleware("http")
    async def idempotency_middleware(request: Request, call_next):
        """同一 Idempotency-Key 的写请求只执行一次，重复请求直接返回首次响应。"""
        key = (request.headers.get("Idempotency-Key") or "").strip()
        if not key or request.method not in WRITE_METHODS or len(key) > 200:
            return await call_next(request)
        with SessionLocal() as db:
            record = db.get(IdempotencyRecord, key)
            if record is not None:
                try:
                    cached = json.loads(record.response_body)
                except (TypeError, json.JSONDecodeError):
                    cached = None
                if cached is not None:
                    return JSONResponse(cached, status_code=record.status_code)

        response = await call_next(request)
        status = response.status_code
        if status >= 400:
            return response
        body = b""
        async for chunk in response.body_iterator:
            body += chunk
        parsed = None
        try:
            parsed = json.loads(body.decode("utf-8"))
        except Exception:
            parsed = None
        if parsed is not None:
            with SessionLocal() as db:
                try:
                    if db.get(IdempotencyRecord, key) is None:
                        db.add(
                            IdempotencyRecord(
                                key=key,
                                method=request.method,
                                path=request.url.path,
                                status_code=status,
                                response_body=json.dumps(parsed, ensure_ascii=False),
                                created_at=timeutil.now_iso(),
                            )
                        )
                        db.commit()
                except Exception:  # pragma: no cover - 并发下主键冲突等
                    db.rollback()
        headers = {
            name: value for name, value in response.headers.items() if name.lower() != "content-length"
        }
        return Response(
            content=body,
            status_code=status,
            headers=headers,
            media_type=response.media_type,
        )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["*"],
    )

    # ---- 异常处理：统一 {ok:false,error,server_time} ----
    @app.exception_handler(ApiError)
    async def api_error_handler(_request: Request, exc: ApiError):
        return JSONResponse(error_payload(exc.code, exc.message), status_code=exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(_request: Request, exc: RequestValidationError):
        return JSONResponse(error_payload("invalid_argument", _validation_message(exc)), status_code=400)

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(_request: Request, exc: StarletteHTTPException):
        code, message = _http_detail(exc)
        return JSONResponse(error_payload(code, message), status_code=exc.status_code)

    @app.exception_handler(Exception)
    async def unhandled_error_handler(_request: Request, exc: Exception):  # pragma: no cover
        logger.exception("未处理异常: %s", exc)
        return JSONResponse(error_payload("internal_error", "服务器内部错误"), status_code=500)

    # ---- 路由 ----
    app.include_router(health.router)

    api = FastAPI  # noqa: F841  占位说明：所有业务接口都在 /api/v1 且需要鉴权
    from fastapi import APIRouter

    api_router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_token)])
    for module in (meta, tasks, diary, weight, quota, devices, stats):
        api_router.include_router(module.router)
    app.include_router(api_router)

    @app.get("/")
    def root() -> dict:
        return envelope(
            {
                "service": settings.app_name,
                "version": settings.version,
                "api": "/api/v1",
                "health": "/api/v1/health",
            }
        )

    return app


app = create_app()


if __name__ == "__main__":  # pragma: no cover
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
