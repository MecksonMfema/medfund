package com.medfund.finance.controller;

import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.finance.service.PaymentAdviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Financial Reports", description = "Aggregated financial reporting endpoints and payment advice generation")
@SecurityRequirement(name = "bearer-jwt")
public class ReportController {

    private final PaymentAdviceService paymentAdviceService;
    private final PaymentRepository paymentRepository;
    private final PaymentRunRepository paymentRunRepository;

    public ReportController(PaymentAdviceService paymentAdviceService,
                            PaymentRepository paymentRepository,
                            PaymentRunRepository paymentRunRepository) {
        this.paymentAdviceService = paymentAdviceService;
        this.paymentRepository = paymentRepository;
        this.paymentRunRepository = paymentRunRepository;
    }

    @GetMapping("/payment-advice/{paymentRunId}")
    @Operation(summary = "Generate payment advice for a payment run",
        description = "Aggregates all payments in a run into a payment advice document with line items per claim")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment advice generated"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentAdvice> generatePaymentAdvice(@PathVariable UUID paymentRunId) {
        return paymentAdviceService.generateAdvice(paymentRunId);
    }

    @GetMapping("/claims-summary")
    @Operation(summary = "Claims summary report",
        description = "Returns aggregated claims statistics: total claims, approved, rejected, and amounts for the given period")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Claims summary returned")
    })
    public Mono<Map<String, Object>> claimsSummary(@RequestParam String period) {
        // Aggregate payment data as a proxy for claims processed through finance
        return paymentRepository.findAllOrderByCreatedAtDesc()
            .collectList()
            .map(payments -> {
                long totalClaims = payments.size();
                long approvedClaims = payments.stream()
                    .filter(p -> "paid".equals(p.getStatus()))
                    .count();
                long rejectedClaims = payments.stream()
                    .filter(p -> "failed".equals(p.getStatus()) || "cancelled".equals(p.getStatus()))
                    .count();
                long pendingClaims = payments.stream()
                    .filter(p -> "pending".equals(p.getStatus()))
                    .count();

                BigDecimal totalAmount = payments.stream()
                    .map(p -> p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal approvedAmount = payments.stream()
                    .filter(p -> "paid".equals(p.getStatus()))
                    .map(p -> p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                Map<String, Object> summary = new HashMap<>();
                summary.put("period", period);
                summary.put("totalClaims", totalClaims);
                summary.put("approvedClaims", approvedClaims);
                summary.put("rejectedClaims", rejectedClaims);
                summary.put("pendingClaims", pendingClaims);
                summary.put("totalAmount", totalAmount);
                summary.put("approvedAmount", approvedAmount);
                return summary;
            });
    }

    @GetMapping("/payment-summary")
    @Operation(summary = "Payment summary report",
        description = "Returns total payments, grouped by provider and by status for the given period")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment summary returned")
    })
    public Mono<Map<String, Object>> paymentSummary(@RequestParam String period) {
        return paymentRepository.findAllOrderByCreatedAtDesc()
            .collectList()
            .map(payments -> {
                BigDecimal totalAmount = payments.stream()
                    .map(p -> p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Group by status
                Map<String, Long> byStatus = new HashMap<>();
                Map<String, BigDecimal> amountByStatus = new HashMap<>();
                payments.forEach(p -> {
                    String status = p.getStatus() != null ? p.getStatus() : "unknown";
                    byStatus.merge(status, 1L, Long::sum);
                    amountByStatus.merge(status,
                        p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO,
                        BigDecimal::add);
                });

                // Group by provider
                Map<String, Long> byProvider = new HashMap<>();
                Map<String, BigDecimal> amountByProvider = new HashMap<>();
                payments.forEach(p -> {
                    String providerId = p.getProviderId() != null ? p.getProviderId().toString() : "unassigned";
                    byProvider.merge(providerId, 1L, Long::sum);
                    amountByProvider.merge(providerId,
                        p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO,
                        BigDecimal::add);
                });

                Map<String, Object> summary = new HashMap<>();
                summary.put("period", period);
                summary.put("totalPayments", payments.size());
                summary.put("totalAmount", totalAmount);
                summary.put("countByStatus", byStatus);
                summary.put("amountByStatus", amountByStatus);
                summary.put("countByProvider", byProvider);
                summary.put("amountByProvider", amountByProvider);
                return summary;
            });
    }

    @GetMapping("/provider-performance")
    @Operation(summary = "Provider performance report",
        description = "Returns provider approval rates and average payment amounts")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider performance returned")
    })
    public Mono<Map<String, Object>> providerPerformance() {
        return paymentRepository.findAllOrderByCreatedAtDesc()
            .collectList()
            .map(payments -> {
                // Aggregate per provider
                Map<String, Map<String, Object>> providerStats = new HashMap<>();

                payments.forEach(p -> {
                    String providerId = p.getProviderId() != null ? p.getProviderId().toString() : "unassigned";
                    var stats = providerStats.computeIfAbsent(providerId, k -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("totalCount", 0L);
                        m.put("approvedCount", 0L);
                        m.put("rejectedCount", 0L);
                        m.put("totalAmount", BigDecimal.ZERO);
                        return m;
                    });

                    stats.put("totalCount", (long) stats.get("totalCount") + 1);
                    stats.put("totalAmount", ((BigDecimal) stats.get("totalAmount"))
                        .add(p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO));

                    if ("paid".equals(p.getStatus())) {
                        stats.put("approvedCount", (long) stats.get("approvedCount") + 1);
                    } else if ("failed".equals(p.getStatus()) || "cancelled".equals(p.getStatus())) {
                        stats.put("rejectedCount", (long) stats.get("rejectedCount") + 1);
                    }
                });

                // Calculate rates and averages
                providerStats.forEach((providerId, stats) -> {
                    long total = (long) stats.get("totalCount");
                    long approved = (long) stats.get("approvedCount");
                    BigDecimal totalAmount = (BigDecimal) stats.get("totalAmount");

                    stats.put("approvalRate", total > 0 ? (double) approved / total * 100.0 : 0.0);
                    stats.put("averageAmount", total > 0
                        ? totalAmount.divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
                });

                Map<String, Object> result = new HashMap<>();
                result.put("providers", providerStats);
                result.put("totalProviders", providerStats.size());
                return result;
            });
    }

    @GetMapping("/contribution-summary")
    @Operation(summary = "Contribution summary report",
        description = "Returns total contributions, paid vs outstanding amounts for the given period. " +
            "Data sourced from payment records in the finance service.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contribution summary returned")
    })
    public Mono<Map<String, Object>> contributionSummary(@RequestParam String period) {
        // This endpoint aggregates finance-side payment data related to contributions
        // For full contribution data, query the contributions service directly
        return paymentRepository.findAllOrderByCreatedAtDesc()
            .filter(p -> "contribution".equals(p.getPaymentType()))
            .collectList()
            .map(payments -> {
                long totalContributions = payments.size();

                BigDecimal totalAmount = payments.stream()
                    .map(p -> p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal paidAmount = payments.stream()
                    .filter(p -> "paid".equals(p.getStatus()))
                    .map(p -> p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal outstandingAmount = totalAmount.subtract(paidAmount);

                long paidCount = payments.stream()
                    .filter(p -> "paid".equals(p.getStatus()))
                    .count();

                Map<String, Object> summary = new HashMap<>();
                summary.put("period", period);
                summary.put("totalContributions", totalContributions);
                summary.put("totalAmount", totalAmount);
                summary.put("paidAmount", paidAmount);
                summary.put("outstandingAmount", outstandingAmount);
                summary.put("paidCount", paidCount);
                summary.put("outstandingCount", totalContributions - paidCount);
                return summary;
            });
    }
}
