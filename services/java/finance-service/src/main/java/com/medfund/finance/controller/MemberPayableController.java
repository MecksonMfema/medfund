package com.medfund.finance.controller;

import com.medfund.finance.dto.MemberPayableDtos.MemberPayableResponse;
import com.medfund.finance.dto.MemberPayableDtos.OutstandingBalanceResponse;
import com.medfund.finance.service.MemberPayableService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/member-payables")
@RequiredArgsConstructor
@Tag(name = "Member Payables",
     description = "Approved claim amounts owed to members (payee_type=MEMBER). "
                 + "Written by the finance-side consumer of medfund.claims.adjudicated; "
                 + "consumed by CTC (Claims-to-Contributions) transfers.")
@SecurityRequirement(name = "bearer-jwt")
public class MemberPayableController {

    private final MemberPayableService service;

    @GetMapping("/member/{memberId}")
    @RequiresPermission({Permissions.FINANCE_VIEW_MEMBER_PAYABLES, Permissions.FINANCE_MANAGE_CTC_PAYMENTS})
    @Operation(summary = "List open payables for a member",
               description = "Used by the CTC form to let an operator pick a specific payable to offset.")
    public Flux<MemberPayableResponse> listForMember(@PathVariable UUID memberId) {
        return service.findOpenByMember(memberId).map(MemberPayableResponse::from);
    }

    @GetMapping("/member/{memberId}/balance")
    @RequiresPermission({Permissions.FINANCE_VIEW_MEMBER_PAYABLES, Permissions.FINANCE_MANAGE_CTC_PAYMENTS})
    @Operation(summary = "Outstanding payable balance per currency for a member")
    public Flux<OutstandingBalanceResponse> balance(@PathVariable UUID memberId) {
        return service.outstandingByMember(memberId)
                .map(b -> new OutstandingBalanceResponse(b.currencyCode(), b.outstanding()));
    }
}
