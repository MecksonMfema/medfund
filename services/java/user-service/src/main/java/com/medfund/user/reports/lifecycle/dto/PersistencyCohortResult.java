package com.medfund.user.reports.lifecycle.dto;

import java.util.List;

/**
 * Phase 13 §C Phase 8 wire shape for PERSISTENCY_COHORT. The result
 * carries the cohort rows plus a {@code freshnessWarning} line the
 * report service copies in from {@code earning_schedule_run
 * .contrib_presence_refresh_at} — populated only when the newest run row
 * is more than 24h stale (grill note 4 resolution).
 */
public record PersistencyCohortResult(
    List<PersistencyCohortRow> rows,
    String freshnessWarning
) {}
