package com.medfund.finance.regulatory.aml.dto;

import java.util.UUID;

public record AmlSummaryJobSubmissionResponse(UUID jobId, String status, boolean deduplicated) {}
