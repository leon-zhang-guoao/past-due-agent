"""EDN load helpers — reuse scripts/validate_bundle parser."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from typing import Any

_ROOT = Path(__file__).resolve().parents[2]
_SCRIPTS = _ROOT / "scripts"


def _load_validate_bundle():
    path = _SCRIPTS / "validate_bundle.py"
    spec = importlib.util.spec_from_file_location("pastdue_validate_bundle", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


_vb = _load_validate_bundle()


def read_edn(path: Path | str) -> Any:
    return _vb.read_edn(Path(path))


def keyword_name(value: Any) -> str:
    if isinstance(value, str):
        return value.removeprefix(":")
    return str(value)
