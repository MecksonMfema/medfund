package com.medfund.finance.regulatory.aml.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.regulatory.aml.dto.AmlAlertResponse;
import com.medfund.finance.regulatory.aml.dto.CloseAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.FileAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.RaiseAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.ReviewAmlAlertRequest;
import com.medfund.finance.regulatory.aml.service.AmlAlertService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the AML/STR alert workflow (Phase 22, REG8).
 *
 * <pre>
 *   POST /                     raise  → RAISED           compliance:aml_raise
 *   GET  /                     queue                     compliance:aml_review
 *   GET  /{id}                 detail                    compliance:aml_review
 *   PUT  /{id}/review          RAISED → REVIEWED         compliance:aml_review
 *   PUT  /{id}/file            REVIEWED → FILED          compliance:aml_file
 *   POST /{id}/close           RAISED|REVIEWED → CLOSED  compliance:aml_close
 * </pre>
 *
 * <p>Every transition is audit-logged with the friendly {@code transactionRef}
 * as {@code entityName} (feedback_audit_entity_name).
 *
 * <p>Not country-gated at the endpoint — every tenant runs AML controls. The
 * XLSX filing in Phase 26 is country-gated because only ZW/ZA/US have shipped
 * templates today.
 */
@RestController
@RequestMapping("/api/v1/regulatory/aml/alerts")
@RequiredArgsConstructor
@Tag(name = "Regulatory - AML/STR alerts",
        description = "Suspicious Transaction Alert workflow. Compliance staff raise → review → "
                + "file (regulator submission) or close (not reportable). Filing XLSX generation "
                + "is Phase 26; this surface only covers the DB workflow + audit trail.")
@SecurityRequirement(name = "bearer-jwt")
public class AmlAlertController {

    private final AmlAlertService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.COMPLIANCE_AML_RAISE)
    @Operation(summary = "Raise a new AML/STR alert (RAISED)",
            description = "Any compliance staff can raise. Description ≥ 20 chars (narrative "
                    + "expected). Emits AuditEvent action=RAISE with friendly entityName = "
                    + "'AML alert #{txnRef}'.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Alert raised"),
            @ApiResponse(responseCode = "400", description = "Validation failure (short description, "
                    + "unknown transaction type, non-positive amount, malformed currency)")
    })
    public Mono<AmlAlertResponse> raise(@Valid @RequestBody RaiseAmlAlertRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.raise(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @GetMapping
    @RequiresPermission(Permissions.COMPLIANCE_AML_REVIEW)
    @Operation(summary = "Paginated AML alert queue",
            description = "Defaults to RAISED + REVIEWED (active-work slice). Pass ?status=FILED "
                    + "or ?status=CLOSED to view terminal rows. Sorted newest-raised-first.")
    public Mono<PageResponse<AmlAlertResponse>> queue(
            @Parameter(description = "Optional single-status filter; omit for RAISED+REVIEWED")
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(200) int size) {
        List<String> statuses = (status != null && !status.isBlank())
                ? List.of(status)
                : List.of(AmlAlertService.STATUS_RAISED, AmlAlertService.STATUS_REVIEWED);
        return service.queue(statuses, page, size).collectList()
                .zipWith(service.queueCount(statuses),
                        (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.COMPLIANCE_AML_REVIEW)
    @Operation(summary = "Fetch a single AML alert by id")
    public Mono<AmlAlertResponse> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PutMapping("/{id}/review")
    @RequiresPermission(Permissions.COMPLIANCE_AML_REVIEW)
    @Operation(summary = "Review a RAISED alert (RAISED → REVIEWED)",
            description = "Compliance reviewer attaches a note. Emits AuditEvent action=REVIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reviewed"),
            @ApiResponse(responseCode = "409", description = "Alert is not RAISED")
    })
    public Mono<AmlAlertResponse> review(@PathVariable UUID id,
                                         @Valid @RequestBody ReviewAmlAlertRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return service.review(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/file")
    @RequiresPermission(Permissions.COMPLIANCE_AML_FILE)
    @Operation(summary = "File a REVIEWED alert (REVIEWED → FILED)",
            description = "Captures the regulator-assigned filing reference (FIU/FIC/FinCEN). "
                    + "XLSX generation is a separate call (Phase 26). Emits AuditEvent action=FILE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Filed"),
            @ApiResponse(responseCode = "409", description = "Alert is not REVIEWED")
    })
    public Mono<AmlAlertResponse> file(@PathVariable UUID id,
                                       @Valid @RequestBody FileAmlAlertRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return service.file(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/close")
    @RequiresPermission(Permissions.COMPLIANCE_AML_CLOSE)
    @Operation(summary = "Close a RAISED|REVIEWED alert (not reportable)",
            description = "Closed reason is mandatory. FILED alerts cannot be re-closed - the "
                    + "workflow assumes a filed alert stays filed. Emits AuditEvent action=CLOSE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Closed"),
            @ApiResponse(responseCode = "400", description = "Missing closedReason"),
            @ApiResponse(responseCode = "409", description = "Alert is FILED or already CLOSED")
    })
    public Mono<AmlAlertResponse> close(@PathVariable UUID id,
                                        @Valid @RequestBody CloseAmlAlertRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.close(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
