"""Conservative image admission shared by ingestion and retrieved assets.

Legacy names are matched in full: a product photo, a labelled measurement or
an arrow inside a wiring diagram must not be rejected for mentioning a digit
or arrow. New VLM results can explicitly reject other layout-only fragments.
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Mapping
from typing import Any

IMAGE_SKIP_REASONS = frozenset(
    {"page_number", "navigation", "decoration", "blank", "text_fragment"}
)

_PAGE_NUMBER = re.compile(
    r"(?:(?:page(?:number)?|digits?|number)\d{1,4}(?:closeup|image)?"
    r"|(?:数字|页码|页数|页面编号)\d{1,4}(?:特写|图像|图片|标识|编号)?)"
)
_ARROW = re.compile(
    r"(?:(?:(?:orange|blue|red|green|grey|gray|black|white|yellow|right|left|up|down|"
    r"circle|circular|round|navigation))*arrow"
    r"(?:(?:right|left|up|down|circle|circular|round|icon|symbol|button))*"
    r"|(?:橙色|蓝色|红色|绿色|灰色|黑色|白色|黄色)?"
    r"(?:圆形|圆圈|圆环)?(?:向?[左右上下]|[左右上下]向)?箭头"
    r"(?:圆形|圆圈|圆环)?(?:图标|符号|按钮)?)"
)
_BLANK = frozenset(
    {
        "blankpage",
        "blankimage",
        "emptyimage",
        "solidcolorbackground",
        "空白页",
        "空白图片",
        "纯色背景",
    }
)
_DECORATION = frozenset(
    {
        "decorativeseparator",
        "horizontalseparator",
        "decorativeborder",
        "装饰分隔线",
        "装饰边框",
        "装饰花纹",
        "redrectanglesonbluebackground",
        "orangetrianglewithwhiteborder",
    }
)


def image_skip_reason(analysis: Mapping[str, Any]) -> str | None:
    """Return a layout-noise reason, preserving ambiguous legacy images.

    Descriptions and surrounding text are deliberately not keyword-matched:
    they often mention arrows, numbers or blank areas in informative figures.
    """
    if analysis.get("should_index") is False:
        reason = analysis.get("skip_reason")
        return (
            reason
            if isinstance(reason, str) and reason in IMAGE_SKIP_REASONS
            else "decoration"
        )
    if analysis.get("should_index") is True:
        return None
    name = analysis.get("name")
    if not isinstance(name, str):
        return None
    name = re.sub(r"[\s_-]+", "", unicodedata.normalize("NFKC", name)).casefold()
    if _PAGE_NUMBER.fullmatch(name):
        return "page_number"
    if _ARROW.fullmatch(name):
        return "navigation"
    if name in _BLANK:
        return "blank"
    if name in _DECORATION:
        return "decoration"
    return None
