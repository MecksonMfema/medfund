"""AI-assisted claims adjudication service with Claude integration."""
import logging

from app.core.gemini_client import GeminiClient
from app.models.prediction import AIPrediction

logger = logging.getLogger(__name__)

ADJUDICATION_SYSTEM_PROMPT = """You are a healthcare claims adjudication AI assistant. \
Analyze the claim data and provide a recommendation.

Respond with a JSON object containing:
- "recommendation": one of "APPROVE", "REJECT", or "REVIEW"
- "confidence": float between 0.0 and 1.0
- "reasoning": brief explanation (1-2 sentences)
- "flags": list of flag strings (e.g., "HIGH_VALUE", "MISSING_DIAGNOSIS", "UNUSUAL_PATTERN")

Consider:
- Claims under $1000 with valid diagnoses are typically approved
- High-value claims (>$10,000) should be flagged for review
- Missing diagnosis codes require review
- Multiple procedures on the same day may need review"""


class AdjudicationService:
    """AI-assisted claims adjudication service."""

    def __init__(self, gemini_client: GeminiClient | None = None):
        self.model_version = "1.0.0"
        self.gemini_client = gemini_client

    async def analyze_claim(self, claim_data: dict, tenant_id: str) -> AIPrediction:
        """Analyze a claim using Claude AI with rule-based fallback."""
        logger.info(f"Analyzing claim {claim_data.get('claim_id')} for tenant {tenant_id}")

        # Try Claude first
        if self.gemini_client and self.gemini_client.available:
            result = await self._analyze_with_claude(claim_data)
            if result:
                return AIPrediction(
                    tenant_id=tenant_id,
                    entity_type="claim",
                    entity_id=claim_data.get("claim_id", "unknown"),
                    prediction_type="adjudication",
                    model_version=self.model_version,
                    input_features=claim_data,
                    output=result,
                    confidence=result.get("confidence", 0.5),
                )

        # Fallback to rule-based
        return self._analyze_rule_based(claim_data, tenant_id)

    async def _analyze_with_claude(self, claim_data: dict) -> dict | None:
        """Use Claude for claim analysis."""
        try:
            result = await self.gemini_client.complete_json(
                system_prompt=ADJUDICATION_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": f"Analyze this claim:\n{claim_data}"}],
            )
            if result and "recommendation" in result:
                return result
        except Exception as e:
            logger.warning(f"Claude adjudication failed, falling back to rules: {e}")
        return None

    def _analyze_rule_based(self, claim_data: dict, tenant_id: str) -> AIPrediction:
        """Rule-based fallback when Claude is unavailable."""
        confidence = 0.85
        recommendation = "APPROVE"
        flags = []
        approved_amount = claim_data.get("claimed_amount", 0)

        amount = claim_data.get("claimed_amount", 0)
        if amount > 10000:
            flags.append("HIGH_VALUE_CLAIM")
            recommendation = "REVIEW"
            confidence = 0.65

        if not claim_data.get("diagnosis_codes"):
            flags.append("MISSING_DIAGNOSIS")
            recommendation = "REVIEW"
            confidence = 0.50

        reasoning = f"Rule-based: {len(claim_data.get('procedure_codes', []))} procedures, "
        reasoning += f"amount={amount}. "
        if flags:
            reasoning += f"Flags: {', '.join(flags)}."
        else:
            reasoning += "No flags raised."

        return AIPrediction(
            tenant_id=tenant_id,
            entity_type="claim",
            entity_id=claim_data.get("claim_id", "unknown"),
            prediction_type="adjudication",
            model_version=self.model_version,
            input_features=claim_data,
            output={
                "recommendation": recommendation,
                "confidence": confidence,
                "reasoning": reasoning,
                "flags": flags,
                "approved_amount": approved_amount if recommendation == "APPROVE" else None,
            },
            confidence=confidence,
        )
