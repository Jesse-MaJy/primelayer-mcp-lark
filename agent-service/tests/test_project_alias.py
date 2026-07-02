"""Tests for project alias resolver (Task P4)."""

from app.models import ProjectRef, ProjectMatch
from app.domain_config import load_domain_config
from app.project_alias import resolve_project_ids


def _roche_config():
    return load_domain_config()


def test_roche_alias_luozhen():
    """罗诊 matches roche project via alias."""
    projects = [ProjectRef(projectId="roche", projectName="罗氏诊断项目")]
    result = resolve_project_ids("罗诊项目的质量情况", projects, _roche_config())
    assert "roche" in result.projectIds
    assert result.confidence > 0.8
    assert result.needClarification is False


def test_roche_alias_english():
    """Roche matches roche project via English alias."""
    projects = [ProjectRef(projectId="roche", projectName="罗氏诊断项目")]
    result = resolve_project_ids("Roche项目质量", projects, _roche_config())
    assert "roche" in result.projectIds
    assert result.confidence > 0.8


def test_direct_project_id_match():
    """Direct projectId match in question."""
    projects = [
        ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
        ProjectRef(projectId="abc", projectName="ABC项目"),
    ]
    result = resolve_project_ids("roche的质量", projects, _roche_config())
    assert "roche" in result.projectIds


def test_direct_project_name_match():
    """Direct project name match in question."""
    projects = [ProjectRef(projectId="roche", projectName="罗氏诊断项目")]
    result = resolve_project_ids("罗氏诊断项目的质量", projects, _roche_config())
    assert "roche" in result.projectIds
    assert result.confidence > 0.8


def test_multi_project_conflict_needs_clarification():
    """Multiple projects matching same alias need clarification."""
    projects = [
        ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
        ProjectRef(projectId="roche2", projectName="罗氏二期项目"),
    ]
    result = resolve_project_ids("罗氏的质量情况", projects, _roche_config())
    # Both projects match "罗氏", should need clarification or pick highest confidence
    assert result.needClarification is True or len(result.projectIds) > 1


def test_single_project_context_default():
    """Single project in context should be defaulted to."""
    projects = [ProjectRef(projectId="roche", projectName="罗氏诊断项目")]
    result = resolve_project_ids("质量情况怎么样", projects, _roche_config())
    # Single project in context, should default to it even without explicit mention
    assert result.projectIds == ["roche"]


def test_no_match_returns_empty():
    """No match should return needClarification with a question."""
    projects = [ProjectRef(projectId="roche", projectName="罗氏诊断项目")]
    result = resolve_project_ids("ABC项目的质量", projects, _roche_config())
    assert result.needClarification is True
    assert result.clarificationQuestion is not None


def test_empty_projects():
    """Empty project list should return empty with needClarification."""
    result = resolve_project_ids("质量情况", [], _roche_config())
    assert result.projectIds == []
    assert result.needClarification is True


def test_partial_name_match():
    """Partial name match via alias."""
    projects = [ProjectRef(projectId="roche", projectName="罗氏诊断项目")]
    result = resolve_project_ids("罗诊", projects, _roche_config())
    assert "roche" in result.projectIds
