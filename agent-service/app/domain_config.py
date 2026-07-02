from __future__ import annotations

from pathlib import Path

import yaml
from pydantic import BaseModel


class PaginationConfig(BaseModel):
    defaultPageSize: int
    maxPages: int
    maxItems: int


class ProjectAliasConfig(BaseModel):
    projectId: str
    projectName: str
    aliases: list[str]


class DomainConfig(BaseModel):
    keywords: list[str]
    coreForms: list[str]
    supplementalForms: list[str]
    excludeForms: list[str]


class AppDomainConfig(BaseModel):
    pagination: PaginationConfig
    projects: list[ProjectAliasConfig]
    domains: dict[str, DomainConfig]


_cached_config: AppDomainConfig | None = None


def load_domain_config(path: str | None = None) -> AppDomainConfig:
    """Load domain config from a YAML file.

    Args:
        path: Path to the YAML config file. Defaults to ``domain_config.yaml``
              in the same directory as this module.

    Returns:
        Parsed AppDomainConfig.
    """
    if path is None:
        path = str(Path(__file__).resolve().parent / "domain_config.yaml")

    with open(path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f)

    return AppDomainConfig(**raw)


def get_domain_config() -> AppDomainConfig:
    """Return the cached singleton domain config, loading it on first call."""
    global _cached_config
    if _cached_config is None:
        _cached_config = load_domain_config()
    return _cached_config
