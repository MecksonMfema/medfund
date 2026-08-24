package com.medfund.user.reports.lifecycle.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 13 §C Phase 8 wire shape for GROUP_CENSUS at a given asOf date.
 */
public record GroupCensusResult(
    LocalDate asOf,
    List<GroupCensusRow> groups
) {}
