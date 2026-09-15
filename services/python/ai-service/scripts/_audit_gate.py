"""Anonymization-audit gate (Phase 0.5, per grilling decision G4).

Every training script that writes a model artifact intended for production
service MUST call ``require_training_audit_signoff()`` before its first
write. If the auditor has not signed off, the flag is unset and the
script exits with code 3 and a pointer to the audit doc.

The check is centralised so a new training script cannot skip it by
accident — the pattern is `require_training_audit_signoff()` at the top
of ``main()`` before any file/S3 write.
"""
from __future__ import annotations

import os
import sys

from app.core.config import settings

AUDIT_DOC_PATH = "thoughts/shared/audit/2026-09-15-ai-training-corpus-anonymization.md"
EXIT_CODE_AUDIT_MISSING = 3


def is_training_audit_signed_off() -> bool:
    """Return True iff the auditor has signed off on the corpus anonymization.

    The env var wins over the pydantic-settings default so an operator
    can flip it for a one-off run without redeploying, and the settings
    default keeps the check consistent when the env var is unset.
    """
    env = os.getenv("MEDFUND_TRAINING_AUDIT_SIGNED_OFF")
    if env is not None:
        return env.strip().lower() in {"1", "true", "yes", "y", "on"}
    return bool(settings.training_audit_signed_off)


def require_training_audit_signoff(*, script_name: str) -> None:
    """Exit with code 3 if the audit gate is not open.

    Args:
        script_name: caller identity, printed in the error message so an
            operator running a corrupted CI job can figure out what tried
            to bypass the gate.
    """
    if is_training_audit_signed_off():
        return
    sys.stderr.write(
        f"[{script_name}] refusing to run: anonymization audit not signed off.\n"
        f"Set MEDFUND_TRAINING_AUDIT_SIGNED_OFF=true after the auditor signs "
        f"the doc at {AUDIT_DOC_PATH}.\n"
    )
    sys.exit(EXIT_CODE_AUDIT_MISSING)
