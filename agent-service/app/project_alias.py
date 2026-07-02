"""Project alias resolver for matching user questions to configured projects.

Resolves project references in natural language questions using a multi-stage
matching strategy: exact projectId, projectName substring, alias lookup, and
partial name tokens. Returns structured match results with confidence scores
and clarification prompts when needed.
"""

from __future__ import annotations

from app.domain_config import AppDomainConfig
from app.models import ProjectMatch, ProjectRef

# Confidence thresholds
CONFIDENCE_ID_MATCH = 0.95
CONFIDENCE_NAME_MATCH = 0.9
CONFIDENCE_ALIAS_MATCH = 0.85
CONFIDENCE_PARTIAL_NAME = 0.75
CONFIDENCE_DEFAULT_SINGLE = 0.6

# When multiple projects match, if the top match beats the second by this
# margin we treat it as unambiguous.
CONFIDENCE_GAP_THRESHOLD = 0.15

# Common Chinese suffixes / words that are not useful for distinguishing
# projects during partial-name matching.
_SKIP_BIGRAMS = frozenset({"项目", "工程"})


def _partial_name_match(project_name: str, question: str) -> bool:
    """Check that a meaningful bigram of *project_name* appears in *question*.

    Filters out overly-generic bigrams (e.g. ``项目``) to avoid false
    positives.
    """
    if len(project_name) < 2:
        return False
    for i in range(len(project_name) - 1):
        bigram = project_name[i : i + 2]
        if bigram in _SKIP_BIGRAMS:
            continue
        if bigram in question:
            return True
    return False


def resolve_project_ids(
    question: str,
    projects: list[ProjectRef],
    config: AppDomainConfig,
) -> ProjectMatch:
    """Match projects referenced in a user question.

    Matching strategy (in priority order):

    1. **Exact projectId** – case-insensitive substring of the question.
    2. **Project name** – full name appears in the question, or a meaningful
       bigram of the name appears (partial match).
    3. **Alias** – any configured alias appears in the question (case-insensitive).

    Decision logic after scoring:

    - Exactly 1 match → return it with the associated confidence.
    - Multiple matches → if the top match leads the second by more than
      ``CONFIDENCE_GAP_THRESHOLD`` it is returned alone; otherwise return all
      candidates with ``needClarification=True``.
    - No matches & single project → default to it (group-chat context).
    - No matches & zero projects → ``needClarification``.
    - No matches & multiple projects → ``needClarification`` with a prompt
      listing the available projects.
    """
    question_lower = question.lower()

    # Fast lookup helpers
    project_map: dict[str, ProjectRef] = {p.projectId: p for p in projects}

    alias_map: dict[str, list[str]] = {}
    for ac in config.projects:
        if ac.projectId in project_map:
            alias_map[ac.projectId] = ac.aliases

    # ---- Scoring phase ----
    matches: dict[str, float] = {}

    # 1. Exact projectId (case-insensitive substring)
    for pid in project_map:
        if pid.lower() in question_lower:
            matches[pid] = max(matches.get(pid, 0), CONFIDENCE_ID_MATCH)

    # 2. Project name
    for pid, pref in project_map.items():
        pname = pref.projectName
        if not pname:
            continue
        # 2a. Full name in question
        if pname in question:
            matches[pid] = max(matches.get(pid, 0), CONFIDENCE_NAME_MATCH)
        # 2b. Partial (bigram) name match
        elif _partial_name_match(pname, question):
            matches[pid] = max(matches.get(pid, 0), CONFIDENCE_PARTIAL_NAME)

    # 3. Configured aliases
    for pid, aliases in alias_map.items():
        for alias in aliases:
            if alias.lower() in question_lower:
                matches[pid] = max(matches.get(pid, 0), CONFIDENCE_ALIAS_MATCH)

    # ---- Decision phase ----
    if len(matches) == 1:
        pid, confidence = next(iter(matches.items()))
        return ProjectMatch(
            projectIds=[pid],
            confidence=confidence,
            needClarification=False,
        )

    if len(matches) > 1:
        # Sort by descending confidence
        sorted_pids = sorted(matches, key=lambda p: matches[p], reverse=True)
        top_conf = matches[sorted_pids[0]]
        second_conf = matches[sorted_pids[1]]

        if top_conf > second_conf + CONFIDENCE_GAP_THRESHOLD:
            return ProjectMatch(
                projectIds=[sorted_pids[0]],
                confidence=top_conf,
                needClarification=False,
            )

        # Build clarification question listing candidate project names
        names: list[str] = []
        for pid in sorted_pids:
            pref = project_map.get(pid)
            names.append(pref.projectName if (pref and pref.projectName) else pid)

        return ProjectMatch(
            projectIds=sorted_pids,
            confidence=0.5,
            needClarification=True,
            clarificationQuestion=f"以下项目都匹配，请问是{'、'.join(names)}中的哪一个？",
        )

    # ---- No match ----
    if len(projects) == 0:
        return ProjectMatch(
            projectIds=[],
            confidence=0.0,
            needClarification=True,
            clarificationQuestion="当前没有可用的项目，请先配置项目。",
        )

    if len(projects) == 1:
        # If the question appears to name a different project explicitly,
        # ask for clarification instead of silently defaulting.
        if "项目" in question:
            return ProjectMatch(
                projectIds=[],
                confidence=0.0,
                needClarification=True,
                clarificationQuestion=(
                    f"未找到匹配的项目，当前可用项目为"
                    f"{projects[0].projectName or projects[0].projectId}。"
                ),
            )
        # Group-chat context – exactly one project, default to it.
        return ProjectMatch(
            projectIds=[projects[0].projectId],
            confidence=CONFIDENCE_DEFAULT_SINGLE,
            needClarification=False,
        )

    # Multiple projects, no match
    name_list = "、".join(
        p.projectName or p.projectId for p in projects
    )
    return ProjectMatch(
        projectIds=[],
        confidence=0.0,
        needClarification=True,
        clarificationQuestion=f"请问您问的是哪个项目？可选：{name_list}",
    )
