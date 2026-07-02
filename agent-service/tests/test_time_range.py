from datetime import date

from app.time_range import resolve_time_range


def test_last_month_july():
    """2026-07-02 + '上个月' => 2026-06-01 to 2026-06-30"""
    result = resolve_time_range("上个月的质量情况", today=date(2026, 7, 2))
    assert result.start == "2026-06-01 00:00:00"
    assert result.end == "2026-06-30 23:59:59"
    assert result.label == "上个月"


def test_last_month_january():
    """2026-01-15 + '上个月' => 2025-12-01 to 2025-12-31"""
    result = resolve_time_range("上个月", today=date(2026, 1, 15))
    assert result.start == "2025-12-01 00:00:00"
    assert result.end == "2025-12-31 23:59:59"


def test_this_week():
    """2026-07-02 (Thursday) + '本周' => Mon 06-29 to Sun 07-05"""
    result = resolve_time_range("本周安全隐患", today=date(2026, 7, 2))
    assert result.start == "2026-06-29 00:00:00"  # Monday
    assert result.end == "2026-07-05 23:59:59"    # Sunday


def test_last_week():
    """上周"""
    result = resolve_time_range("上周", today=date(2026, 7, 2))
    assert result.start == "2026-06-22 00:00:00"
    assert result.end == "2026-06-28 23:59:59"


def test_today():
    result = resolve_time_range("今天的日报", today=date(2026, 7, 2))
    assert result.start == "2026-07-02 00:00:00"
    assert result.end == "2026-07-02 23:59:59"


def test_yesterday():
    result = resolve_time_range("昨天", today=date(2026, 7, 2))
    assert result.start == "2026-07-01 00:00:00"
    assert result.end == "2026-07-01 23:59:59"


def test_this_month():
    result = resolve_time_range("本月", today=date(2026, 7, 2))
    assert result.start == "2026-07-01 00:00:00"
    assert result.end == "2026-07-31 23:59:59"


def test_last_7_days():
    result = resolve_time_range("近7天", today=date(2026, 7, 2))
    assert result.start == "2026-06-25 00:00:00"
    assert result.end == "2026-07-02 23:59:59"


def test_this_quarter():
    result = resolve_time_range("本季度", today=date(2026, 7, 2))
    assert result.start == "2026-07-01 00:00:00"
    assert result.end == "2026-09-30 23:59:59"


def test_last_quarter():
    result = resolve_time_range("上季度", today=date(2026, 7, 2))
    assert result.start == "2026-04-01 00:00:00"
    assert result.end == "2026-06-30 23:59:59"


def test_default_quality_is_last_30_days():
    """未指定时间 + quality => 近30天"""
    result = resolve_time_range("质量情况", today=date(2026, 7, 2), default_domain="quality")
    assert result.start == "2026-06-02 00:00:00"
    assert result.end == "2026-07-02 23:59:59"
    assert result.source == "default_domain"


def test_default_progress_is_this_week():
    """未指定时间 + progress => 本周"""
    result = resolve_time_range("进度怎么样", today=date(2026, 7, 2), default_domain="progress")
    assert result.start == "2026-06-29 00:00:00"
    assert result.end == "2026-07-05 23:59:59"


def test_explicit_date_parse():
    """Explicit date ranges bypass relative parsing"""
    result = resolve_time_range("2026-03-01到2026-03-31的质量情况", today=date(2026, 7, 2))
    assert result.start == "2026-03-01 00:00:00"
    assert result.end == "2026-03-31 23:59:59"
