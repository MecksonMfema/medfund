package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantRegulatoryTemplateRequest;
import com.medfund.tenancy.dto.TenantRegulatoryTemplateResponse;
import com.medfund.tenancy.service.TenantRegulatoryTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Tenant regulatory template override CRUD (Phase 16 §0 REG3). Base64 upload
 * per {@link AddTenantRegulatoryTemplateRequest}; download returns raw bytes.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/regulatory-templates")
@RequiredArgsConstructor
@Tag(name = "Tenant Regulatory Templates",
     description = "Tenant-uploaded regulator XLSX overrides consumed by RegulatoryTemplateService.load(...).")
@SecurityRequirement(name = "bearer-jwt")
public class TenantRegulatoryTemplateController {

    private final TenantRegulatoryTemplateService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_regulatory_templates", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List regulatory template overrides for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantRegulatoryTemplateResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantRegulatoryTemplateResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_regulatory_templates"})
    @Operation(summary = "Upload a regulatory template override",
            description = "XLSX carried as base64 on the JSON body. Server validates the payload "
                        + "parses as a well-formed XSSFWorkbook and is ≤2MB. Uniqueness enforced on "
                        + "(tenant_id, regulator, report_key, effective_from) — conflicts surface as HTTP 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid XLSX or oversized payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (regulator, report_key, effective_from)")
    })
    public Mono<TenantRegulatoryTemplateResponse> add(@PathVariable UUID tenantId,
                                                      @Valid @RequestBody AddTenantRegulatoryTemplateRequest body,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantRegulatoryTemplateResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_regulatory_templates"})
    @Operation(summary = "Delete a regulatory template override")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Row deleted"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID tenantId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, id, AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/{id}/download")
    @RequiresPermission({"tenant.settings:manage_regulatory_templates", "admin:manage_settings", "finance:view"})
    @Operation(summary = "Download the XLSX bytes for an override",
            description = "Streams the raw XLSX for auditor / template-review use.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "XLSX bytes returned"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id).map(row -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment()
                    .filename(row.getRegulator() + "-" + row.getReportKey() + "-v_" + row.getVersionLabel() + ".xlsx")
                    .build());
            return new ResponseEntity<>(row.getXlsxBytes(), headers, HttpStatus.OK);
        });
    }
}
