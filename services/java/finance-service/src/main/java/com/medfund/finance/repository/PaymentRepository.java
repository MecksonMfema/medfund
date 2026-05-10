package com.medfund.finance.repository;

import com.medfund.finance.entity.Payment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface PaymentRepository extends R2dbcRepository<Payment, UUID> {

    @Query("SELECT * FROM payments WHERE payment_number = :paymentNumber")
    Mono<Payment> findByPaymentNumber(String paymentNumber);

    @Query("SELECT * FROM payments WHERE provider_id = :providerId ORDER BY created_at DESC")
    Flux<Payment> findByProviderId(UUID providerId);

    @Query("SELECT * FROM payments WHERE status = :status ORDER BY created_at DESC")
    Flux<Payment> findByStatus(String status);

    @Query("SELECT * FROM payments ORDER BY created_at DESC")
    Flux<Payment> findAllOrderByCreatedAtDesc();

    @Query("SELECT EXISTS(SELECT 1 FROM payments WHERE payment_number = :paymentNumber)")
    Mono<Boolean> existsByPaymentNumber(String paymentNumber);

    /**
     * Sum of paid payment amounts in the given currency on or before the
     * cutoff. Used by reconciliation to auto-resolve the system-side
     * amount for a bank statement entry. Returns 0 when there are no
     * matching payments.
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM payments " +
           "WHERE status = 'paid' AND currency_code = :currencyCode " +
           "AND paid_at IS NOT NULL AND paid_at <= :cutoff")
    Mono<BigDecimal> sumPaidUpTo(String currencyCode, Instant cutoff);
}
