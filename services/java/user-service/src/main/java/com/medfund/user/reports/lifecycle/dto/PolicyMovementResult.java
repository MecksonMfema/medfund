package com.medfund.user.reports.lifecycle.dto;

import java.util.List;

/**
 * Phase 13 §C Phase 8 wire shape for POLICY_MOVEMENT.
 * Native rows per parent-plan invariant #1; per-currency envelope totals live
 * on the surrounding {@code ReportResponse<PolicyMovementResult>}.
 */
public record PolicyMovementResult(List<PolicyMovementRow> rows) {}
