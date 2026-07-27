#!/usr/bin/env python3
"""Module-identity alias for the neutral Quick evidence security contract."""

from __future__ import annotations

import sys

if __package__:
    from scripts import quick_evidence_security as _neutral
else:
    import quick_evidence_security as _neutral

sys.modules[__name__] = _neutral
