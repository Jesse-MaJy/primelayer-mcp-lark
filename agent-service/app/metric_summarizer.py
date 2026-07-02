"""MetricSummarizer: compute deterministic statistics and build management reports.

Each domain has a dedicated compute function that walks tool-result items,
counts by status/severity/type/area/owner, and returns a metrics dict.
"""

from __future__ import annotations

from collections import Counter
from typing import Any

from .models import DomainIntent


def compute_quality_metrics(data: dict[str, Any]) -> dict[str, Any]:
    """Compute quality domain metrics from form data.

    Args:
        data: A dict with ``total`` (int) and ``items`` (list of dicts).
              Each item may have keys: ``status``, ``severity``, ``type``,
              ``responsiblePerson``, ``area``.

    Returns:
        Metrics dict with keys like ``total_records``, ``unclosed_count``,
        ``high_severity_count``, ``status_distribution``,
        ``type_distribution``, ``responsible_distribution``,
        ``area_distribution``.
    """
    items: list[dict[str, Any]] = data.get("items", [])
    total = data.get("total", len(items))

    status_counter: Counter = Counter()
    severity_counter: Counter = Counter()
    type_counter: Counter = Counter()
    responsible_counter: Counter = Counter()
    area_counter: Counter = Counter()

    for item in items:
        status = item.get("status", "")
        severity = item.get("severity", "")
        item_type = item.get("type", "")
        responsible = item.get("responsiblePerson", "")
        area = item.get("area", "")

        if status:
            status_counter[status] += 1
        if severity:
            severity_counter[severity] += 1
        if item_type:
            type_counter[item_type] += 1
        if responsible:
            responsible_counter[responsible] += 1
        if area:
            area_counter[area] += 1

    # Unclosed = anything that is NOT "已完成" or "已关闭"
    unclosed_count = sum(
        count for status, count in status_counter.items()
        if "已" not in status or status in ("待整改", "逾期未整改")
    )

    # More precise: unclosed = total - completed
    completed_count = status_counter.get("已完成", 0) + status_counter.get("已关闭", 0)
    unclosed_count = total - completed_count

    high_severity_count = severity_counter.get("高", 0)

    return {
        "total_records": total,
        "unclosed_count": unclosed_count,
        "high_severity_count": high_severity_count,
        "status_distribution": dict(status_counter),
        "severity_distribution": dict(severity_counter),
        "type_distribution": dict(type_counter),
        "responsible_distribution": dict(responsible_counter),
        "area_distribution": dict(area_counter),
    }


def compute_safety_metrics(data: dict[str, Any]) -> dict[str, Any]:
    """Compute safety domain metrics from form data.

    Args:
        data: A dict with ``total`` (int) and ``items`` (list of dicts).
              Each item may have keys: ``riskLevel``, ``status``, ``area``.

    Returns:
        Metrics dict with ``total_records``, ``high_risk_count``,
        ``unrectified_count``, and distributions.
    """
    items: list[dict[str, Any]] = data.get("items", [])
    total = data.get("total", len(items))

    risk_counter: Counter = Counter()
    status_counter: Counter = Counter()
    area_counter: Counter = Counter()

    for item in items:
        risk = item.get("riskLevel", "")
        status = item.get("status", "")
        area = item.get("area", "")

        if risk:
            risk_counter[risk] += 1
        if status:
            status_counter[status] += 1
        if area:
            area_counter[area] += 1

    high_risk_count = risk_counter.get("高", 0)

    # Unrectified = not "已整改" or "已关闭"
    unrectified_count = sum(
        count for status, count in status_counter.items()
        if "已" not in status
    )

    return {
        "total_records": total,
        "high_risk_count": high_risk_count,
        "unrectified_count": unrectified_count,
        "risk_level_distribution": dict(risk_counter),
        "status_distribution": dict(status_counter),
        "area_distribution": dict(area_counter),
    }


def compute_progress_metrics(data: dict[str, Any]) -> dict[str, Any]:
    """Compute progress domain metrics from form data.

    Args:
        data: A dict with ``total`` (int) and ``items`` (list of dicts).
              Each item may have keys: ``planProgress``, ``actualProgress``,
              ``status``.

    Returns:
        Metrics dict with ``total_nodes``, ``lagging_count``,
        ``status_distribution``, and average deviation.
    """
    items: list[dict[str, Any]] = data.get("items", [])
    total = data.get("total", len(items))

    status_counter: Counter = Counter()
    lagging_count = 0
    deviations: list[float] = []

    for item in items:
        status = item.get("status", "")
        if status:
            status_counter[status] += 1

        plan = item.get("planProgress")
        actual = item.get("actualProgress")
        if plan is not None and actual is not None:
            try:
                plan_val = float(plan)
                actual_val = float(actual)
                deviation = plan_val - actual_val
                deviations.append(deviation)
                if deviation > 0:
                    lagging_count += 1
            except (ValueError, TypeError):
                pass

    # Also count explicit "滞后" status
    explicit_lagging = status_counter.get("滞后", 0)
    if explicit_lagging > lagging_count:
        lagging_count = explicit_lagging

    avg_deviation = sum(deviations) / len(deviations) if deviations else 0.0

    return {
        "total_nodes": total,
        "lagging_count": lagging_count,
        "avg_plan_vs_actual_deviation": round(avg_deviation, 1),
        "status_distribution": dict(status_counter),
    }


def compute_risk_metrics(data: dict[str, Any]) -> dict[str, Any]:
    """Compute risk domain metrics from form data.

    Args:
        data: A dict with ``total`` (int) and ``items`` (list of dicts).
              Each item may have keys: ``riskLevel``, ``status``,
              ``isOverdue``.

    Returns:
        Metrics dict with ``total_risks``, ``high_risk_count``,
        ``overdue_count``, ``unresolved_count``.
    """
    items: list[dict[str, Any]] = data.get("items", [])
    total = data.get("total", len(items))

    risk_counter: Counter = Counter()
    status_counter: Counter = Counter()
    overdue_count = 0

    for item in items:
        risk = item.get("riskLevel", "")
        status = item.get("status", "")
        is_overdue = item.get("isOverdue", False)

        if risk:
            risk_counter[risk] += 1
        if status:
            status_counter[status] += 1
        if is_overdue is True or str(is_overdue).lower() == "true":
            overdue_count += 1

    high_risk_count = risk_counter.get("高", 0)

    # Unresolved = explicitly "未处理" status only (not "处理中" or "已关闭")
    unresolved_count = status_counter.get("未处理", 0)

    return {
        "total_risks": total,
        "high_risk_count": high_risk_count,
        "overdue_count": overdue_count,
        "unresolved_count": unresolved_count,
        "risk_level_distribution": dict(risk_counter),
        "status_distribution": dict(status_counter),
    }


def build_management_report(
    domain: str,
    metrics: dict[str, Any],
    data_sources: list[str],
    failed_sources: list[str],
    domain_intent: DomainIntent,
    use_model: bool = True,
) -> str:
    """Build a management-report-style answer.

    The report is structured with these sections:
    1. 结论 -- 1-2 sentence summary
    2. 关键指标 -- counts and distributions
    3. 问题分布 -- type/area/responsible breakdowns (domain-dependent)
    4. 趋势与风险 -- high-frequency issues and risk assessment
    5. 建议动作 -- actionable recommendations
    6. 数据范围 -- time range + data sources
    7. 未覆盖/失败说明 -- if any failures

    Args:
        domain: Domain name (quality, safety, progress, risk).
        metrics: Metrics dict returned by the corresponding compute function.
        data_sources: List of data source descriptions.
        failed_sources: List of descriptions for failed data sources.
        domain_intent: The detected DomainIntent (provides timeRange, etc.).
        use_model: If False, produce a deterministic fallback report via
                   string formatting. If True, the report is still produced
                   deterministically but the caller may use it as an LLM prompt.

    Returns:
        Formatted management report string.
    """
    lines: list[str] = []
    domain_label = _domain_label(domain)

    # 1. 结论
    conclusion = _build_conclusion(domain, metrics, domain_label)
    lines.append(f"结论：{conclusion}")
    lines.append("")

    # 2. 关键指标
    lines.append("关键指标：")
    lines.extend(_build_key_metrics(domain, metrics))
    lines.append("")

    # 3. 问题分布
    distribution_lines = _build_distribution(domain, metrics)
    if distribution_lines:
        lines.append("问题分布：")
        lines.extend(distribution_lines)
        lines.append("")

    # 4. 趋势与风险
    trend_risk = _build_trend_risk(domain, metrics)
    if trend_risk:
        lines.append("趋势与风险：")
        lines.append(trend_risk)
        lines.append("")

    # 5. 建议动作
    lines.append("建议动作：")
    lines.extend(_build_recommendations(domain, metrics))
    lines.append("")

    # 6. 数据范围
    lines.append("数据范围：")
    lines.extend(_build_data_range(domain_intent, data_sources))
    lines.append("")

    # 7. 未覆盖/失败说明
    if failed_sources:
        lines.append("未覆盖/失败说明：")
        lines.append("以下数据源暂时无法读取，报告中未包含其数据：")
        for source in failed_sources:
            lines.append(f"  - {source}")
        lines.append("建议联系管理员查看后台审计日志定位失败原因。")

    return "\n".join(lines)


def _domain_label(domain: str) -> str:
    """Return a human-readable label for a domain."""
    labels: dict[str, str] = {
        "quality": "质量",
        "safety": "安全",
        "progress": "进度",
        "risk": "风险",
    }
    return labels.get(domain, domain)


def _build_conclusion(domain: str, metrics: dict[str, Any], label: str) -> str:
    """Build the conclusion section."""
    if domain == "quality":
        total = metrics.get("total_records", 0)
        unclosed = metrics.get("unclosed_count", 0)
        high = metrics.get("high_severity_count", 0)
        if unclosed > 0:
            return f"本次共查询到{total}条{label}记录，其中未整改完结{unclosed}条（含高严重度{high}条），需重点关注及时整改。"
        return f"本次共查询到{total}条{label}记录，当前无未整改问题，{label}状态良好。"
    elif domain == "safety":
        total = metrics.get("total_records", 0)
        high = metrics.get("high_risk_count", 0)
        unrectified = metrics.get("unrectified_count", 0)
        if high > 0:
            return f"本次共查询到{total}条{label}记录，其中高风险{high}条、未整改{unrectified}条，存在重大安全隐患，需立即处理。"
        return f"本次共查询到{total}条{label}记录，当前高风险项已清零，{label}态势整体受控。"
    elif domain == "progress":
        total = metrics.get("total_nodes", 0)
        lagging = metrics.get("lagging_count", 0)
        if lagging > 0:
            return f"本次共查询{total}个进度节点，其中滞后{lagging}个，需核实滞后原因并调整计划。"
        return f"本次共查询{total}个进度节点，各节点进度正常，整体推进有序。"
    elif domain == "risk":
        total = metrics.get("total_risks", 0)
        high = metrics.get("high_risk_count", 0)
        overdue = metrics.get("overdue_count", 0)
        if high > 0:
            return f"本次共查询到{total}条风险记录，其中高风险{high}条、逾期{overdue}条，需升级处理并及时通报。"
        return f"本次共查询到{total}条风险记录，当前无高风险逾期项，风险受控。"
    else:
        return f"基于{label}域数据完成查询，详见下方指标与分析。"


def _build_key_metrics(domain: str, metrics: dict[str, Any]) -> list[str]:
    """Build the key metrics section lines."""
    lines: list[str] = []

    if domain == "quality":
        total = metrics.get("total_records", 0)
        unclosed = metrics.get("unclosed_count", 0)
        high = metrics.get("high_severity_count", 0)
        closed = total - unclosed
        lines.append(f"  - 记录总数：{total}")
        lines.append(f"  - 未整改（含逾期）：{unclosed} 条")
        lines.append(f"  - 已完成整改：{closed} 条")
        lines.append(f"  - 高严重度：{high} 条")
        # Status distribution
        status_dist = metrics.get("status_distribution", {})
        if status_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(status_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 状态分布：{'，'.join(parts)}")

    elif domain == "safety":
        total = metrics.get("total_records", 0)
        high = metrics.get("high_risk_count", 0)
        unrectified = metrics.get("unrectified_count", 0)
        lines.append(f"  - 记录总数：{total}")
        lines.append(f"  - 高风险：{high} 条")
        lines.append(f"  - 未整改：{unrectified} 条")
        risk_dist = metrics.get("risk_level_distribution", {})
        if risk_dist:
            parts = [f"{k}风险：{v}条" for k, v in sorted(risk_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 风险等级分布：{'，'.join(parts)}")

    elif domain == "progress":
        total = metrics.get("total_nodes", 0)
        lagging = metrics.get("lagging_count", 0)
        avg_dev = metrics.get("avg_plan_vs_actual_deviation", 0)
        lines.append(f"  - 节点总数：{total}")
        lines.append(f"  - 滞后节点：{lagging} 个")
        lines.append(f"  - 计划与实际平均偏差：{avg_dev}%")
        status_dist = metrics.get("status_distribution", {})
        if status_dist:
            parts = [f"{k}：{v}个" for k, v in sorted(status_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 节点状态分布：{'，'.join(parts)}")

    elif domain == "risk":
        total = metrics.get("total_risks", 0)
        high = metrics.get("high_risk_count", 0)
        overdue = metrics.get("overdue_count", 0)
        unresolved = metrics.get("unresolved_count", 0)
        lines.append(f"  - 风险总数：{total}")
        lines.append(f"  - 高风险：{high} 条")
        lines.append(f"  - 逾期：{overdue} 条")
        lines.append(f"  - 未解决：{unresolved} 条")
        risk_dist = metrics.get("risk_level_distribution", {})
        if risk_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(risk_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 风险等级分布：{'，'.join(parts)}")

    else:
        for key, value in sorted(metrics.items()):
            if isinstance(value, dict):
                parts = [f"{k}：{v}" for k, v in sorted(value.items(), key=lambda x: -x[1])][:5]
                lines.append(f"  - {key}：{'，'.join(parts)}")
            else:
                lines.append(f"  - {key}：{value}")

    return lines


def _build_distribution(domain: str, metrics: dict[str, Any]) -> list[str]:
    """Build the distribution section lines."""
    lines: list[str] = []

    if domain == "quality":
        type_dist = metrics.get("type_distribution", {})
        if type_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(type_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 问题类型：{'，'.join(parts)}")
        area_dist = metrics.get("area_distribution", {})
        if area_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(area_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 区域分布：{'，'.join(parts)}")
        responsible_dist = metrics.get("responsible_distribution", {})
        if responsible_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(responsible_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 责任人分布：{'，'.join(parts)}")

    elif domain == "safety":
        area_dist = metrics.get("area_distribution", {})
        if area_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(area_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 区域分布：{'，'.join(parts)}")
        status_dist = metrics.get("status_distribution", {})
        if status_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(status_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 整改状态：{'，'.join(parts)}")

    elif domain == "risk":
        status_dist = metrics.get("status_distribution", {})
        if status_dist:
            parts = [f"{k}：{v}条" for k, v in sorted(status_dist.items(), key=lambda x: -x[1])]
            lines.append(f"  - 风险处理状态：{'，'.join(parts)}")

    return lines


def _build_trend_risk(domain: str, metrics: dict[str, Any]) -> str:
    """Build the trend and risk assessment section."""
    if domain == "quality":
        type_dist = metrics.get("type_distribution", {})
        if type_dist:
            top_type = max(type_dist, key=type_dist.get)
            top_count = type_dist[top_type]
            return f"'{top_type}'类问题占比最高（{top_count}条），为当前质量管控重点，建议开展专项排查治理。"
        return ""

    elif domain == "safety":
        high = metrics.get("high_risk_count", 0)
        unrectified = metrics.get("unrectified_count", 0)
        if high > 0:
            return f"当前仍有{high}条高风险隐患和{unrectified}条未整改项，存在安全管控漏洞，需建立整改跟踪闭环机制。"
        return "当前安全态势平稳，建议持续执行日常巡检和定期安全培训。"
    elif domain == "progress":
        lagging = metrics.get("lagging_count", 0)
        if lagging > 0:
            return f"{lagging}个节点存在滞后，建议分析滞后根因（资源、前置依赖、天气等），制定赶工计划并动态跟踪。"
        return "进度整体可控，建议保持当前节奏并关注关键路径节点。"
    elif domain == "risk":
        high = metrics.get("high_risk_count", 0)
        overdue = metrics.get("overdue_count", 0)
        if high > 0:
            return f"有{high}条高风险和{overdue}条逾期项未处理，建议升级至项目管理层周例会通报，明确责任人和完成时限。"
        return "风险项总体可控，建议按周Review风险清单，确保不遗漏。"
    return ""


def _build_recommendations(domain: str, metrics: dict[str, Any]) -> list[str]:
    """Build actionable recommendations."""
    lines: list[str] = []

    if domain == "quality":
        high = metrics.get("high_severity_count", 0)
        unclosed = metrics.get("unclosed_count", 0)
        if high > 0:
            lines.append(f"  1. 高严重度缺陷（{high}条）优先整改，设定明确整改完成时限")
        if unclosed > 0:
            lines.append(f"  2. 跟踪未整改缺陷（{unclosed}条）整改进展，纳入周例会Review")
        type_dist = metrics.get("type_distribution", {})
        if type_dist:
            top_type = max(type_dist, key=type_dist.get)
            lines.append(f"  3. 对'{top_type}'类高频问题进行根因分析，制定系统性预防措施")
        if not lines:
            lines.append("  1. 维持当前质量管理水平，定期巡检巩固成果")
    elif domain == "safety":
        high = metrics.get("high_risk_count", 0)
        unrectified = metrics.get("unrectified_count", 0)
        if high > 0:
            lines.append(f"  1. 高风险隐患（{high}条）立即制定整改方案并分配责任人")
        if unrectified > 0:
            lines.append(f"  2. 未整改项（{unrectified}条）限期完成，逾期升级通报")
        lines.append("  3. 每周安排安全检查巡检，对整改情况进行复核")
        if not lines:
            lines.append("  1. 定期开展安全培训与应急演练，保持安全管控意识")
    elif domain == "progress":
        lagging = metrics.get("lagging_count", 0)
        if lagging > 0:
            lines.append(f"  1. 对{lagging}个滞后节点逐一分析根因并制定赶工措施")
            lines.append("  2. 建立日跟踪机制，每日更新实际进度与偏差")
        lines.append("  3. 关注关键路径节点的资源保障，避免连锁滞后")
        if not lines:
            lines.append("  1. 保持当前节奏，每周Review关键路径进展")
    elif domain == "risk":
        high = metrics.get("high_risk_count", 0)
        overdue = metrics.get("overdue_count", 0)
        if high > 0:
            lines.append(f"  1. 高风险项（{high}条）需升级管理，明确处理策略与应急预案")
        if overdue > 0:
            lines.append(f"  2. 逾期风险（{overdue}条）立即评估影响，更新应对措施")
        unresolved = metrics.get("unresolved_count", 0)
        if unresolved > 0:
            lines.append(f"  3. 未解决风险（{unresolved}条）纳入周例会跟踪，设定解决时限")
        if not lines:
            lines.append("  1. 定期Review风险清单，保持风险敏感度")
    else:
        lines.append("  1. 基于以上指标制定改进计划")
        lines.append("  2. 定期跟踪指标变化趋势")
    return lines


def _build_data_range(domain_intent: DomainIntent, data_sources: list[str]) -> list[str]:
    """Build the data range section."""
    lines: list[str] = []

    if domain_intent.timeRange:
        tr = domain_intent.timeRange
        lines.append(f"  - 时间范围：{tr.start} 至 {tr.end}（{tr.label}）")
    else:
        lines.append("  - 时间范围：未指定")

    if data_sources:
        lines.append(f"  - 数据来源：{'，'.join(data_sources)}")
    else:
        lines.append("  - 数据来源：无")

    return lines
