package com.medfund.claims.service;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.repository.TariffCodeRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CoPaymentService {

    private final TariffCodeRepository tariffCodeRepository;

    public CoPaymentService(TariffCodeRepository tariffCodeRepository) {
        this.tariffCodeRepository = tariffCodeRepository;
    }

    /**
     * Calculate co-payment for each claim line.
     * Co-payment = claimed amount - tariff allowed amount.
     * If claimed <= tariff, co-payment is zero (fully covered).
     */
    public Mono<CoPaymentResult> calculate(Claim claim, List<ClaimLine> lines) {
        return Flux.fromIterable(lines)
            .flatMap(line -> tariffCodeRepository.findByCode(line.getTariffCode())
                .map(tariff -> calculateLineCoPay(line, tariff))
                .defaultIfEmpty(new LineCoPayment(
                    line.getTariffCode(), line.getClaimedAmount(), BigDecimal.ZERO, line.getClaimedAmount(),
                    "Tariff not found - full amount is co-payment"
                )))
            .collectList()
            .map(lineResults -> {
                BigDecimal totalApproved = lineResults.stream()
                    .map(LineCoPayment::approvedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal totalCoPay = lineResults.stream()
                    .map(LineCoPayment::coPayment)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                return new CoPaymentResult(totalApproved, totalCoPay, lineResults);
            });
    }

    private LineCoPayment calculateLineCoPay(ClaimLine line, TariffCode tariff) {
        BigDecimal tariffAllowed = tariff.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
        BigDecimal claimed = line.getClaimedAmount();

        if (claimed.compareTo(tariffAllowed) <= 0) {
            // Fully covered by tariff
            return new LineCoPayment(line.getTariffCode(), claimed, claimed, BigDecimal.ZERO,
                "Fully covered");
        }

        // Excess is co-payment
        BigDecimal coPay = claimed.subtract(tariffAllowed);
        return new LineCoPayment(line.getTariffCode(), claimed, tariffAllowed, coPay,
            "Excess of " + coPay + " above tariff limit " + tariffAllowed);
    }

    public record CoPaymentResult(
        BigDecimal totalApproved,
        BigDecimal totalCoPayment,
        List<LineCoPayment> lineDetails
    ) {}

    public record LineCoPayment(
        String tariffCode,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        BigDecimal coPayment,
        String details
    ) {}
}
