package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Upload a tenant-side override of a bundled regulator XLSX template.
 *
 * <p>The XLSX itself is carried as a base64 string on the JSON body — the
 * plan sketched a multipart endpoint, but base64 keeps the tenancy-service
 * controller shape consistent with every other tenant-config CRUD (JSON in,
 * JSON out) and templates cap at 2MB so the payload overhead is acceptable.
 * A future multipart endpoint can be added alongside without breaking this
 * one.
 *
 * <p>Validation of the payload as a real XSSFWorkbook happens in
 * {@link com.medfund.tenancy.service.TenantRegulatoryTemplateService} — a
 * JSON schema check would only catch structural garbage; parsing catches
 * the case where the file is a valid ZIP but not a well-formed XLSX.
 */
public record AddTenantRegulatoryTemplateRequest(
        @NotBlank @Size(max = 40)  String regulator,
        @NotBlank @Size(max = 80)  String reportKey,
        @NotBlank @Size(max = 80)  String versionLabel,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @NotNull @NotBlank         String xlsxBase64,
        @Size(max = 4000)          String notes
) {}
