"""Small compatibility helpers so the app works across Streamlit versions.

Newer Streamlit releases replace ``use_container_width=True`` with
``width="stretch"``. ``stretch(fn)`` returns the right keyword for whichever
signature the installed version has.
"""
from __future__ import annotations

import inspect
from typing import Any, Callable

_cache: dict[int, dict[str, Any]] = {}


def stretch(fn: Callable[..., Any]) -> dict[str, Any]:
    key = id(fn)
    if key in _cache:
        return dict(_cache[key])
    kwargs: dict[str, Any] = {}
    try:
        params = inspect.signature(fn).parameters
        if "width" in params:
            kwargs = {"width": "stretch"}
        elif "use_container_width" in params:
            kwargs = {"use_container_width": True}
    except (TypeError, ValueError):
        pass
    _cache[key] = kwargs
    return dict(kwargs)
