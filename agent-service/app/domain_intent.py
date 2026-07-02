"""Domain intent detector for classifying user questions into business domains.

Uses keyword matching against the domain configuration to detect which
business domains (quality, safety, progress, risk) a question relates to.
"""

from __future__ import annotations

from app.domain_config import AppDomainConfig
from app.models import DomainIntent


def detect_domain_intent(question: str, config: AppDomainConfig) -> DomainIntent:
    """Classify a user question into business domains based on keyword matching.

    Args:
        question: The user's natural language question.
        config: The application domain configuration containing domain keywords.

    Returns:
        DomainIntent with matched domains, confidence, project hints, and depth.
    """
    if not question or not question.strip():
        return DomainIntent(
            domains=["general"],
            confidence=0.0,
            depth="standard",
        )

    domains = list(config.domains.keys())
    question_lower = question.lower()

    matched_domains: list[str] = []
    total_keywords = 0
    matched_keywords = 0

    for domain_name in domains:
        domain_config = config.domains[domain_name]
        keywords = domain_config.keywords
        total_keywords += len(keywords)

        for keyword in keywords:
            if keyword.lower() in question_lower:
                matched_keywords += 1
                if domain_name not in matched_domains:
                    matched_domains.append(domain_name)

    if not matched_domains:
        matched_domains = ["general"]

    # Confidence: ratio of matched keywords to total possible keywords, capped at 1.0
    confidence = min(matched_keywords / max(total_keywords, 1), 1.0)

    # Extract project hints from the question based on configured project aliases
    project_hints: list[str] = []
    for project in config.projects:
        for alias in project.aliases:
            if alias.lower() in question_lower:
                project_hints.append(alias)
                break  # one alias per project is enough

    return DomainIntent(
        domains=matched_domains,
        projectHints=project_hints,
        timeRange=None,
        depth="standard",
        confidence=confidence,
    )
