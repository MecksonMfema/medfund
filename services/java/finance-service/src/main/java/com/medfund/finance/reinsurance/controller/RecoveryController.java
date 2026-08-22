package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.reinsurance.dto.MarkRecoveryReceivedRequest;
import com.medfund.finance.reinsurance.dto.RecoveryResponse;
import com.medfund.finance.reinsurance.dto.WriteOffRecoveryRequest;
import com.medfund.finance.reinsurance.service.RecoveryService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Recovery lifecycle surfaces (Phase 8). The EXPECTED → INVOICED
 * transition is automatic (recoveries bordereau export, Phase 4);
 * this controller owns only the manual downstream transitions:
 * mark-received when the reinsurer's money lands, or write-off when
 * the fund gives up on collecting.
 */
@RestController
@RequestMapping("/api/v1/reinsurance/recoveries")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Recoveries",
     description = "Recovery lifecycle for cessions previously invoiced to a reinsurer. "
                 + "Mark received when funds land; write off with a reason when the recovery "
                 + "will not be paid.")
@SecurityRequirement(name = "bearer-jwt")
public class RecoveryController {

    private final RecoveryService service;

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "Get a recovery by id")
    public Mono<RecoveryResponse> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/mark-received")
    @RequiresPermission(Permissions.REINSURANCE_RECORD_RECOVERY_RECEIVED)
    @Operation(summary = "Record that the reinsurer's payment has landed",
            description = "Transitions the recovery to RECEIVED with the given receivedAmount + "
                        + "receivedAt (defaults to now when omitted). Valid from EXPECTED or "
                        + "INVOICED — any terminal state returns 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded"),
            @ApiResponse(responseCode = "400", description = "Missing or negative receivedAmount"),
            @ApiResponse(responseCode = "404", description = "Recovery not found"),
            @ApiResponse(responseCode = "409", description = "Recovery already terminal")
    })
    public Mono<RecoveryResponse> markReceived(@PathVariable UUID id,
                                               @Valid @RequestBody MarkRecoveryReceivedRequest body,
                                               @AuthenticationPrincipal Jwt jwt) {
        return service.markReceived(id, body.receivedAmount(), body.receivedAt(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}/write-off")
    @RequiresPermission(Permissions.REINSURANCE_WRITEOFF_RECOVERY)
    @Operation(summary = "Write off an outstanding recovery with a mandatory reason",
            description = "Transitions the recovery to WRITTEN_OFF with the given reason. Valid "
                        + "from EXPECTED or INVOICED — RECEIVED or already-WRITTEN_OFF returns 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Written off"),
            @ApiResponse(responseCode = "400", description = "Missing reason"),
            @ApiResponse(responseCode = "404", description = "Recovery not found"),
            @ApiResponse(responseCode = "409", description = "Recovery already terminal")
    })
    public Mono<RecoveryResponse> writeOff(@PathVariable UUID id,
                                           @Valid @RequestBody WriteOffRecoveryRequest body,
                                           @AuthenticationPrincipal Jwt jwt) {
        return service.writeOff(id, body.reason(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
