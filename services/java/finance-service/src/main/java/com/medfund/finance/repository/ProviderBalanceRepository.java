package com.medfund.finance.repository;

import com.medfund.finance.entity.ProviderBalance;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProviderBalanceRepository extends R2dbcRepository<ProviderBalance, UUID> {

    @Query("SELECT * FROM provider_balances WHERE provider_id = :providerId")
    Mono<ProviderBalance> findByProviderId(UUID providerId);

    @Query("SELECT * FROM provider_balances WHERE provider_id = :providerId AND currency_code = :currencyCode")
    Mono<ProviderBalance> findByProviderIdAndCurrencyCode(UUID providerId, String currencyCode);

    @Query("SELECT * FROM provider_balances WHERE outstanding_balance > 0 ORDER BY outstanding_balance DESC")
    Flux<ProviderBalance> findAllByOutstandingBalanceGreaterThanZero();

    @Query("SELECT * FROM provider_balances WHERE currency_code = :currencyCode AND outstanding_balance > 0 ORDER BY provider_id")
    Flux<ProviderBalance> findOutstandingByCurrency(String currencyCode);

    @Query("SELECT * FROM provider_balances ORDER BY provider_id")
    Flux<ProviderBalance> findAllOrderByProviderId();
}
