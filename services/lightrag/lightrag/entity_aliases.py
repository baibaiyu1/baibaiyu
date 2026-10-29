"""Entity aliases and complete device models for ingestion and cache replay."""

from __future__ import annotations

import json
import os
import re
from collections import defaultdict
from collections.abc import Mapping
from functools import lru_cache
from pathlib import Path
from typing import Any

from lightrag.constants import DEFAULT_ENTITY_NAME_MAX_LENGTH
from lightrag.utils import normalize_entity_name

_DEVICE_MODEL = re.compile(
    r"(?<![A-Za-z0-9])GMD[\s_-]*(?:[A-Za-z]{1,4}[\s_-]*)?"
    r"\d+[A-Za-z0-9]*(?:\s+pro)?(?:-[A-Za-z0-9]+)*(?:\+)?"
    r"(?![A-Za-z0-9+])",
    re.IGNORECASE,
)
_SEPARATE_OBJECT = re.compile(
    r"图纸|线稿|示意图|结构图|爆炸图|实物图|产品图|外观图|接线图|原理图|"
    r"参数表|数据表|说明书|手册|画册|彩页|照片|图片|图像|截图|表格|"
    r"技术参数|规格参数|适用型号[:：]|灯罩|光源面板|散热鳍片|电源线|安装卡扣|配套|设备柜|"
    r"支架|接口|按键|按钮|旋钮|控制面板|底座|线缆|系留箱|"
    r"电池|天线|镜头|外壳|保护壳|屏幕|螺丝|充电器|电源适配器|"
    r"drawing|diagram|manual|datasheet|image|screenshot|\.pdf\b|\.jpg\b|\.png\b",
    re.IGNORECASE,
)
_CONFIGURATION = re.compile(
    r"长款|短款|白光型|红光型|特配|定制|-导轨式|\d+\s*[Ww瓦]|"
    r"[（(][^）)]*(?:版|型号|新能源|自动|手动)[^）)]*[）)]|"
    r"(?<![A-Za-z0-9])(?:max|mini|plus|ultra|lite|se)(?![A-Za-z0-9])",
    re.IGNORECASE,
)


def _model_key(name: str) -> str:
    return re.sub(r"[\s_-]", "", name).casefold()


def canonical_device_model(name: str) -> str:
    """Format a complete GMD model while retaining its full variant suffix."""
    compact = re.sub(r"[\s_]", "", name).upper()
    compact = re.sub(r"^GMD-?", "", compact)
    prefix = "GMD-" if compact[0].isalpha() else "GMD"
    return prefix + re.sub(r"PRO(?=\+?$)", "pro", compact)


@lru_cache(maxsize=32)
def _device_models(targets: tuple[str, ...]) -> dict[str, str]:
    return {_model_key(name): name for name in targets if _DEVICE_MODEL.fullmatch(name)}


def canonical_entity_name(name: str, aliases: Mapping[str, str]) -> str:
    """Resolve explicit aliases and complete GMD model names.

    A complete model dominates descriptive device names, such as a recorder
    or handheld device. Parts, drawings, tables, configuration qualifiers,
    and multiple-model names retain their own identities. Newly seen GMD models
    use the same deterministic spelling without needing a catalog entry.
    Model suffixes including Pro, +, A and -X1 are matched in full.
    """
    normalized = normalize_entity_name(name)
    explicit = aliases.get(normalized.casefold())
    if explicit is not None:
        return explicit
    matches = list(_DEVICE_MODEL.finditer(normalized))
    if len(matches) != 1:
        return name
    match = matches[0]
    remainder = normalized[: match.start()] + normalized[match.end() :]
    if (
        "_" in remainder
        or "/" in remainder
        or "\\" in remainder
        or _SEPARATE_OBJECT.search(remainder)
        or _CONFIGURATION.search(remainder)
    ):
        return name
    models = _device_models(tuple(sorted(set(aliases.values()))))
    return models.get(_model_key(match.group()), canonical_device_model(match.group()))


def normalize_entity_aliases(aliases: Mapping[str, str]) -> dict[str, str]:
    """Validate explicit aliases and return a case-insensitive lookup.

    Only listed names and their canonical targets are case-insensitive. Keep
    units, model suffixes, parts and image identifiers distinct unless the
    mapping explicitly declares them equivalent. Chains and conflicting targets
    are rejected so applying the rule twice always produces the same result.
    """
    if not isinstance(aliases, Mapping):
        raise TypeError("entity_aliases must be a mapping of names to canonical names")
    lookup: dict[str, str] = {}
    targets: set[str] = set()
    for alias, canonical in aliases.items():
        if not isinstance(alias, str) or not isinstance(canonical, str):
            raise TypeError("Entity alias names and targets must be strings")
        alias = normalize_entity_name(alias)
        canonical = normalize_entity_name(canonical)
        if not alias or not canonical:
            raise ValueError("Entity alias names and targets cannot be empty")
        if max(len(alias), len(canonical)) > DEFAULT_ENTITY_NAME_MAX_LENGTH:
            raise ValueError(
                "Entity aliases cannot exceed the entity name length limit"
            )
        key = alias.casefold()
        if key in lookup and lookup[key] != canonical:
            raise ValueError(f"Conflicting canonical names for entity alias {alias!r}")
        lookup[key] = canonical
        targets.add(canonical)
    for canonical in sorted(targets):
        key = canonical.casefold()
        if key in lookup and lookup[key] != canonical:
            raise ValueError(f"Entity alias target {canonical!r} is also an alias")
        lookup[key] = canonical
    return lookup


def load_entity_aliases_from_env() -> dict[str, str]:
    """Load ENTITY_ALIASES_FILE; relative paths resolve beneath PROMPT_DIR.

    An unset or empty variable disables aliases. A configured file must exist
    and contain a JSON object mapping aliases to canonical names. Invalid
    configuration fails startup instead of silently admitting duplicate names.
    """
    filename = os.environ.get("ENTITY_ALIASES_FILE", "").strip()
    if not filename:
        return {}
    path = Path(filename)
    if not path.is_absolute():
        path = Path(os.environ.get("PROMPT_DIR", "./prompts")) / path
    aliases = json.loads(path.read_text(encoding="utf-8-sig"))
    return normalize_entity_aliases(aliases)


def canonicalize_extraction_names(
    maybe_nodes: dict[str, list[dict[str, Any]]],
    maybe_edges: dict[tuple[str, str], list[dict[str, Any]]],
    aliases: Mapping[str, str],
) -> tuple[dict, dict]:
    """Apply a validated lookup to entity keys and both relation endpoints.

    Retain every evidence record when names coalesce. A relation between two
    aliases of the same entity disappears as a self-loop. Source chunks,
    descriptions, file paths and weights are copied without alteration. Core
    multimodal identities are injected afterwards and never renamed here.
    """
    if not aliases:
        return maybe_nodes, maybe_edges

    def canonical(name: str) -> str:
        return canonical_entity_name(name, aliases)

    nodes: dict[str, list[dict[str, Any]]] = defaultdict(list)
    edges: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for name, records in maybe_nodes.items():
        target = canonical(name)
        nodes[target].extend({**row, "entity_name": target} for row in records)
    for (source, target), records in maybe_edges.items():
        source, target = canonical(source), canonical(target)
        if source == target:
            continue
        edges[(source, target)].extend(
            {**row, "src_id": source, "tgt_id": target} for row in records
        )
    return dict(nodes), dict(edges)
