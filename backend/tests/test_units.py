"""纯函数单测：奖励/等级公式、宵禁窗口、时间工具。"""

from __future__ import annotations

from datetime import datetime, time

import pytest

from app import timeutil
from app.errors import ApiError
from app.services import analytics, rules


def test_reward_formula():
    rewards = rules.compute_rewards(3, 0)
    assert rewards["exp"] == 40
    assert rewards["coins"] == 15
    assert rewards["attribute_delta"] == 6
    assert rewards["streak_multiplier"] == 1.0

    rewards = rules.compute_rewards(5, 10)
    assert rewards["streak_multiplier"] == 1.5
    assert rewards["exp"] == 90
    assert rewards["coins"] == 38

    rewards = rules.compute_rewards(1, 4)
    assert rewards["exp"] == 24
    assert rewards["coins"] == 6
    assert rewards["attribute_delta"] == 2


def test_streak_multiplier_cap():
    assert rules.streak_multiplier(0) == 1.0
    assert rules.streak_multiplier(3) == pytest.approx(1.15)
    assert rules.streak_multiplier(10) == pytest.approx(1.5)
    assert rules.streak_multiplier(100) == pytest.approx(1.5)


def test_round_and_exp_required():
    assert rules.round_half_up(0.5) == 1
    assert rules.round_half_up(1.5) == 2
    assert rules.round_half_up(2.49) == 2
    assert rules.exp_required_next(1) == 100
    assert rules.exp_required_next(2) == 150
    assert rules.exp_required_next(5) == 300


def test_curfew_window_helpers():
    moment = datetime(2026, 11, 2, 4, 30, tzinfo=timeutil.TZ)
    assert timeutil.is_in_window(time(1, 0), time(7, 0), moment) is True
    assert timeutil.is_in_window(time(1, 0), time(7, 0), datetime(2026, 11, 1, 12, 0, tzinfo=timeutil.TZ)) is False
    assert timeutil.is_in_window(time(23, 0), time(7, 0), datetime(2026, 11, 2, 23, 30, tzinfo=timeutil.TZ)) is True
    assert timeutil.is_in_window(time(23, 0), time(7, 0), datetime(2026, 11, 2, 12, 0, tzinfo=timeutil.TZ)) is False

    assert timeutil.window_covers(time(1, 0), time(7, 0), time(3, 0), time(7, 0)) is True
    assert timeutil.window_covers(time(3, 0), time(7, 0), time(3, 0), time(7, 0)) is True
    assert timeutil.window_covers(time(23, 0), time(7, 0), time(3, 0), time(7, 0)) is True
    assert timeutil.window_covers(time(5, 0), time(9, 0), time(3, 0), time(7, 0)) is False
    assert timeutil.window_covers(time(1, 0), time(6, 30), time(3, 0), time(7, 0)) is False


def test_parse_range():
    assert analytics.parse_range(None) == 7
    assert analytics.parse_range("7d") == 7
    assert analytics.parse_range("30d") == 30
    assert analytics.parse_range("90d") == 90
    assert analytics.parse_range("14") == 14
    with pytest.raises(ApiError):
        analytics.parse_range("oops")
    with pytest.raises(ApiError):
        analytics.parse_range("0d")
    with pytest.raises(ApiError):
        analytics.parse_range("400d")


def test_time_parse_helpers():
    assert timeutil.date_str(timeutil.parse_date("2026-11-02")) == "2026-11-02"
    assert timeutil.parse_hhmm("03:00") == time(3, 0)
    assert timeutil.parse_hhmm("23:59") == time(23, 59)
    with pytest.raises(ValueError):
        timeutil.parse_hhmm("25:00")
    with pytest.raises(ValueError):
        timeutil.parse_date("2026/11/02")
    assert timeutil.to_iso(datetime(2026, 11, 2, 2, 30, tzinfo=timeutil.TZ)).endswith("+08:00")
