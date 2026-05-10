package com.medfund.finance.repository;

import com.medfund.finance.entity.MascaBankAccount;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MascaBankAccountRepository extends R2dbcRepository<MascaBankAccount, UUID> {

    @Query("SELECT * FROM masca_bank_accounts ORDER BY currency_code, bank_name")
    Flux<MascaBankAccount> findAllOrdered();

    @Query("SELECT * FROM masca_bank_accounts WHERE currency_code = :currencyCode ORDER BY bank_name")
    Flux<MascaBankAccount> findByCurrencyCode(String currencyCode);

    @Query("SELECT * FROM masca_bank_accounts WHERE currency_code = :currencyCode AND is_nominated = TRUE LIMIT 1")
    Mono<MascaBankAccount> findNominatedForCurrency(String currencyCode);

    /**
     * Clears the nominated flag for every other account in the same currency.
     * Called before flipping a new account to nominated so the partial unique
     * index on (currency_code) WHERE is_nominated stays satisfied.
     */
    @Modifying
    @Query("UPDATE masca_bank_accounts SET is_nominated = FALSE, updated_at = NOW() " +
           "WHERE currency_code = :currencyCode AND id <> :exceptId AND is_nominated = TRUE")
    Mono<Integer> clearNominationsForCurrencyExcept(String currencyCode, UUID exceptId);
}
