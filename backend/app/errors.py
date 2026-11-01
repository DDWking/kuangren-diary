from __future__ import annotations


class ApiError(Exception):
    """业务异常：由全局 handler 转成统一的失败响应包。

    code 使用 docs/api.md 第 4 节的错误码：
    invalid_argument(400) / unauthorized(401) / not_found(404) /
    conflict(409) / policy_violation(422) / rate_limited(429) / internal_error(500)
    """

    def __init__(self, code: str, message: str, status_code: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def bad_request(message: str) -> ApiError:
    return ApiError("invalid_argument", message, 400)


def not_found(message: str = "对象不存在") -> ApiError:
    return ApiError("not_found", message, 404)


def conflict(message: str) -> ApiError:
    return ApiError("conflict", message, 409)


def policy_violation(message: str) -> ApiError:
    return ApiError("policy_violation", message, 422)
