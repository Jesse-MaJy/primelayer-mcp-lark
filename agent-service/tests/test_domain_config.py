from app.domain_config import load_domain_config


def test_load_default_config():
    """Can load the default domain config without errors."""
    config = load_domain_config()
    assert config is not None
    assert config.pagination is not None
    assert config.projects is not None
    assert config.domains is not None


def test_roche_project_config():
    """Roche project config has the expected display name and aliases."""
    config = load_domain_config()
    roche = next(p for p in config.projects if p.projectId == "roche")
    assert roche.projectName == "罗氏诊断项目"
    assert "罗诊" in roche.aliases
    assert "Roche" in roche.aliases


def test_quality_domain_config():
    """Quality domain config has the two required core forms."""
    config = load_domain_config()
    quality = config.domains["quality"]
    assert "质量缺陷清单" in quality.coreForms
    assert "重点质量关注项" in quality.coreForms


def test_pagination_defaults():
    """Pagination config has the expected defaults."""
    config = load_domain_config()
    assert config.pagination.defaultPageSize == 100
    assert config.pagination.maxPages == 50
    assert config.pagination.maxItems == 5000


def test_all_four_domains_exist():
    """All four expected domains are present in the config."""
    config = load_domain_config()
    assert set(config.domains.keys()) == {"quality", "safety", "progress", "risk"}
