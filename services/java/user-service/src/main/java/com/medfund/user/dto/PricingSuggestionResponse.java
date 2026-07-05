package com.medfund.user.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI pricing suggestion output. Structured so the operator UI can:
 * <ul>
 *   <li>Pre-fill the Custom-premium amount with {@link #suggestedAmount}.</li>
 *   <li>Render {@link #rationale} as a tooltip or side-panel so the
 *       operator understands why the model landed on that number.</li>
 *   <li>Surface {@link #factors} for a more granular breakdown when
 *       the tenant wants an audit-friendly trail.</li>
 * </ul>
 *
 * <p>{@link #stub} is true whenever the underlying computation is a
 * hand-written placeholder — the frontend can render a "Stub" pill so
 * operators don't mistake the number for a real AI recommendation.
 */
public record PricingSuggestionResponse(
    BigDecimal suggestedAmount,
    String currencyCode,
    String rationale,
    List<String> factors,
    boolean stub
) {}
