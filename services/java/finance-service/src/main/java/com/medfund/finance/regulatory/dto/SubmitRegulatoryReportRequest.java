package com.medfund.finance.regulatory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Record a regulator report submission. XLSX bytes carried as base64 on the
 * JSON body, matching the tenant-regulatory-template upload shape — 2-5MB
 * fits comfortably; a multipart variant can be added alongside if needed.
 */
public record SubmitRegulatoryReportRequest(
        @NotBlank @Size(max = 80)  String reportKey,
        @NotNull                   LocalDate periodStart,
        @NotNull                   LocalDate periodEnd,
        @NotNull                   UUID sourceRunId,
        @NotBlank                  String xlsxBase64,
        @Size(max = 4000)          String attestationNote,
        @Size(max = 4000)          String reasonNote
) {}
