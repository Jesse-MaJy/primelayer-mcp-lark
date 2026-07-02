from __future__ import annotations

import calendar
import re
from datetime import date, datetime, timedelta

from app.models import TimeRange

# Patterns ordered by priority — more specific before less specific
_RELATIVE_PATTERNS: list[tuple[str, str]] = [
    # Explicit date ranges: YYYY-MM-DD 到/至 YYYY-MM-DD
    (r"(\d{4}-\d{2}-\d{2})\s*[到至]\s*(\d{4}-\d{2}-\d{2})", "explicit_range"),
    # Relative time keywords
    (r"上个月", "last_month"),
    (r"上月", "last_month"),
    (r"本周", "this_week"),
    (r"上周", "last_week"),
    (r"今天", "today"),
    (r"昨天", "yesterday"),
    (r"本月", "this_month"),
    (r"近(\d+)天", "last_n_days"),
    (r"最近(\d+)天", "last_n_days"),
    (r"本季度", "this_quarter"),
    (r"上季度", "last_quarter"),
]

DOMAIN_DEFAULTS: dict[str, str] = {
    "quality": "last_30_days",
    "safety": "last_30_days",
    "risk": "last_30_days",
    "progress": "this_week",
}


def _month_end(year: int, month: int) -> int:
    """Return the last day of the given month."""
    return calendar.monthrange(year, month)[1]


def _quarter_start(month: int) -> int:
    """Return the first month of the quarter containing `month`."""
    return ((month - 1) // 3) * 3 + 1


def _start_of_week(d: date) -> date:
    """Return the Monday on or before `d`."""
    return d - timedelta(days=d.weekday())


def resolve_time_range(
    question: str,
    today: date | None = None,
    default_domain: str | None = None,
) -> TimeRange:
    """Convert a user question with relative time expressions into an absolute
    ``TimeRange``.

    Supported keywords: 今天/昨天/本周/上周/本月/上月/上个月/近N天/本季度/上季度.
    Also parses explicit ranges like ``YYYY-MM-DD 到 YYYY-MM-DD``.

    If no time expression is found, domain defaults are used:
    quality/safety/risk → last 30 days; progress → this week.
    """
    if today is None:
        today = date.today()

    for pattern, kind in _RELATIVE_PATTERNS:
        m = re.search(pattern, question)
        if not m:
            continue

        if kind == "explicit_range":
            start_str = m.group(1)
            end_str = m.group(2)
            return TimeRange(
                label=f"{start_str} 至 {end_str}",
                start=f"{start_str} 00:00:00",
                end=f"{end_str} 23:59:59",
                source="explicit_range",
            )

        if kind == "today":
            s = today.strftime("%Y-%m-%d")
            return TimeRange(
                label="今天",
                start=f"{s} 00:00:00",
                end=f"{s} 23:59:59",
                source="explicit_days",
            )

        if kind == "yesterday":
            y = today - timedelta(days=1)
            s = y.strftime("%Y-%m-%d")
            return TimeRange(
                label="昨天",
                start=f"{s} 00:00:00",
                end=f"{s} 23:59:59",
                source="explicit_days",
            )

        if kind == "this_week":
            mon = _start_of_week(today)
            sun = mon + timedelta(days=6)
            return TimeRange(
                label="本周",
                start=f"{mon.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{sun.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_days",
            )

        if kind == "last_week":
            mon = _start_of_week(today) - timedelta(days=7)
            sun = mon + timedelta(days=6)
            return TimeRange(
                label="上周",
                start=f"{mon.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{sun.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_days",
            )

        if kind == "this_month":
            s = today.replace(day=1)
            e = today.replace(day=_month_end(today.year, today.month))
            return TimeRange(
                label="本月",
                start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{e.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_month",
            )

        if kind == "last_month":
            if today.month == 1:
                year = today.year - 1
                month = 12
            else:
                year = today.year
                month = today.month - 1
            s = today.replace(year=year, month=month, day=1)
            e = today.replace(year=year, month=month, day=_month_end(year, month))
            return TimeRange(
                label="上个月",
                start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{e.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_month",
            )

        if kind == "last_n_days":
            n = int(m.group(1))
            s = today - timedelta(days=n)
            return TimeRange(
                label=f"近{n}天",
                start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{today.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_days",
            )

        if kind == "this_quarter":
            qs = _quarter_start(today.month)
            s = today.replace(month=qs, day=1)
            qe = qs + 2
            e = today.replace(month=qe, day=_month_end(today.year, qe))
            return TimeRange(
                label="本季度",
                start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{e.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_month",
            )

        if kind == "last_quarter":
            qs = _quarter_start(today.month)
            if qs == 1:
                year = today.year - 1
                qs = 10
            else:
                year = today.year
                qs = qs - 3
            qe = qs + 2
            s = today.replace(year=year, month=qs, day=1)
            e = today.replace(year=year, month=qe, day=_month_end(year, qe))
            return TimeRange(
                label="上季度",
                start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{e.strftime('%Y-%m-%d')} 23:59:59",
                source="explicit_month",
            )

    # No time expression found — use domain defaults
    if default_domain:
        default_kind = DOMAIN_DEFAULTS.get(default_domain)
        if default_kind == "this_week":
            mon = _start_of_week(today)
            sun = mon + timedelta(days=6)
            return TimeRange(
                label="本周",
                start=f"{mon.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{sun.strftime('%Y-%m-%d')} 23:59:59",
                source="default_domain",
            )
        if default_kind == "last_30_days":
            s = today - timedelta(days=30)
            return TimeRange(
                label="近30天",
                start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
                end=f"{today.strftime('%Y-%m-%d')} 23:59:59",
                source="default_domain",
            )

    # Fallback: last 30 days
    s = today - timedelta(days=30)
    return TimeRange(
        label="近30天",
        start=f"{s.strftime('%Y-%m-%d')} 00:00:00",
        end=f"{today.strftime('%Y-%m-%d')} 23:59:59",
        source="default_domain",
    )
