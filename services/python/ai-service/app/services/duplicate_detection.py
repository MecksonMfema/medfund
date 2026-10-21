"""Duplicate claim detection using fuzzy matching."""
import logging
from datetime import datetime

logger = logging.getLogger(__name__)


class DuplicateDetector:
    """Detects potential duplicate claims using fuzzy matching."""

    def check_duplicate(
        self, claim: dict, recent_claims: list[dict],
        date_tolerance_days: int = 3, amount_tolerance_pct: float = 0.10
    ) -> dict:
        """Check if a claim is a potential duplicate of recent claims."""
        matches = []

        claim_member = claim.get("member_id", "")
        claim_provider = claim.get("provider_id", "")
        claim_amount = float(claim.get("claimed_amount", 0))
        claim_date = self._parse_date(claim.get("service_date", ""))
        claim_procedures = set(claim.get("procedure_codes", []))

        for recent in recent_claims:
            score = 0.0
            reasons = []

            # Exact member match
            if recent.get("member_id") == claim_member and claim_member:
                score += 0.3
                reasons.append("same_member")

            # Exact provider match
            if recent.get("provider_id") == claim_provider and claim_provider:
                score += 0.2
                reasons.append("same_provider")

            # Date proximity
            recent_date = self._parse_date(recent.get("service_date", ""))
            if claim_date and recent_date:
                days_diff = abs((claim_date - recent_date).days)
                if days_diff <= date_tolerance_days:
                    score += 0.2
                    reasons.append(f"service_date_within_{days_diff}_days")

            # Amount similarity
            recent_amount = float(recent.get("claimed_amount", 0))
            if claim_amount > 0 and recent_amount > 0:
                diff_pct = abs(claim_amount - recent_amount) / max(claim_amount, recent_amount)
                if diff_pct <= amount_tolerance_pct:
                    score += 0.15
                    reasons.append(f"amount_within_{diff_pct:.0%}")

            # Procedure overlap (Jaccard similarity)
            recent_procedures = set(recent.get("procedure_codes", []))
            if claim_procedures and recent_procedures:
                jaccard = len(claim_procedures & recent_procedures) / len(claim_procedures | recent_procedures)
                if jaccard > 0.5:
                    score += 0.15
                    reasons.append(f"procedure_overlap_{jaccard:.0%}")

            if score >= 0.5:
                matches.append({
                    "claim_id": recent.get("claim_id", ""),
                    "similarity_score": round(score, 2),
                    "reasons": reasons,
                })

        is_duplicate = len(matches) > 0 and any(m["similarity_score"] >= 0.7 for m in matches)

        return {
            "is_duplicate": is_duplicate,
            "matches": sorted(matches, key=lambda m: m["similarity_score"], reverse=True),
            "highest_score": matches[0]["similarity_score"] if matches else 0.0,
        }

    def _parse_date(self, date_str: str):
        if not date_str:
            return None
        try:
            return datetime.fromisoformat(date_str).date()
        except (ValueError, TypeError):
            return None
