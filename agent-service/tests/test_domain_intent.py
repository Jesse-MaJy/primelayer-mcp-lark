"""Tests for domain intent detector (Task P5)."""

from app.domain_intent import detect_domain_intent
from app.domain_config import load_domain_config


def _config():
    return load_domain_config()


def test_quality_keywords():
    """质量关键词命中quality"""
    result = detect_domain_intent("质量缺陷情况", _config())
    assert "quality" in result.domains


def test_safety_keywords():
    """安全隐患关键词命中safety"""
    result = detect_domain_intent("安全隐患排查", _config())
    assert "safety" in result.domains


def test_progress_keywords():
    """进度关键词命中progress"""
    result = detect_domain_intent("当前施工进度", _config())
    assert "progress" in result.domains


def test_risk_keywords():
    """风险关键词命中risk"""
    result = detect_domain_intent("逾期风险和阻塞项", _config())
    assert "risk" in result.domains


def test_multi_domain():
    """进度风险同时命中progress和risk"""
    result = detect_domain_intent("当前进度风险是什么", _config())
    assert "progress" in result.domains
    assert "risk" in result.domains
    assert len(result.domains) >= 2


def test_defect_keyword_maps_to_quality():
    """缺陷关键词映射到质量"""
    result = detect_domain_intent("帮我看下缺陷整改情况", _config())
    assert "quality" in result.domains


def test_delay_keyword_maps_to_risk():
    """延期关键词映射到风险"""
    result = detect_domain_intent("有哪些延期项", _config())
    assert "risk" in result.domains


def test_no_match_falls_to_general():
    """无匹配时返回general"""
    result = detect_domain_intent("帮我看一下数据", _config())
    assert "general" in result.domains


def test_empty_question():
    """空问题返回general"""
    result = detect_domain_intent("", _config())
    assert "general" in result.domains
    assert result.confidence == 0.0


def test_confidence_is_set():
    """置信度正确设置"""
    result = detect_domain_intent("上个月的质量缺陷情况", _config())
    assert result.confidence > 0.0
    assert result.depth == "standard"
