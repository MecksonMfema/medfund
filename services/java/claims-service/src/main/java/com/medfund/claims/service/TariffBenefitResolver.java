package com.medfund.claims.service;

import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.repository.TariffCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * V063 ingestion-time resolver. Given a claim line's tariff code and
 * the claim's scheme, returns the scheme_benefit UUID this line should
 * consume from (or empty for cap-only / unmapped).
 *
 * <p>Resolution chain (replaces the V062 tariff_benefit_mappings path):
 * <ol>
 *   <li>{@code line.tariffCode → tariff_codes.category_id}</li>
 *   <li>If {@code tariff_categories.is_cap_only = TRUE} → empty (line
 *       deducts from the cap only, no per-benefit ledger)</li>
 *   <li>Else {@code benefit_tariff_categories JOIN scheme_benefits
 *       WHERE scheme_id = :schemeId AND tariff_category_id = :categoryId
 *       → the scheme_benefit.id}</li>
 * </ol>
 *
 * <p>Returns empty when: the tariff has no row, the category is
 * cap-only, or the scheme has no benefit covering that category.
 * Stage 3 re-checks the mapping to distinguish "cap-only" from
 * "unmapped tariff for this scheme → reject".
 */
@Service
public class TariffBenefitResolver {

    private static final Logger log = LoggerFactory.getLogger(TariffBenefitResolver.class);

    private final TariffCodeRepository tariffCodeRepository;
    private final DatabaseClient databaseClient;

    public TariffBenefitResolver(TariffCodeRepository tariffCodeRepository,
                                 DatabaseClient databaseClient) {
        this.tariffCodeRepository = tariffCodeRepository;
        this.databaseClient = databaseClient;
    }

    public Mono<UUID> resolve(String tariffCode, UUID schemeId) {
        if (tariffCode == null || tariffCode.isBlank() || schemeId == null) {
            return Mono.empty();
        }
        return tariffCodeRepository.findByCode(tariffCode)
                .map(TariffCode::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .flatMap(categoryId -> databaseClient.sql("""
                        SELECT sb.id AS scheme_benefit_id,
                               tc.is_cap_only
                          FROM tariff_categories tc
                     LEFT JOIN benefit_tariff_categories btc
                            ON btc.tariff_category_id = tc.id
                     LEFT JOIN scheme_benefits sb
                            ON sb.id = btc.scheme_benefit_id
                           AND sb.scheme_id = :schemeId
                           AND (sb.status IS NULL OR sb.status = 'active')
                         WHERE tc.id = :categoryId
                         ORDER BY sb.id NULLS LAST
                         LIMIT 1
                        """)
                        .bind("categoryId", categoryId)
                        .bind("schemeId", schemeId)
                        .fetch().one()
                        .flatMap(row -> {
                            boolean capOnly = row.get("is_cap_only") != null
                                    && Boolean.parseBoolean(row.get("is_cap_only").toString());
                            if (capOnly) {
                                return Mono.<UUID>empty();
                            }
                            Object sbId = row.get("scheme_benefit_id");
                            if (sbId == null) {
                                log.debug("No scheme_benefit covers category {} for scheme {} — line unmapped",
                                        categoryId, schemeId);
                                return Mono.<UUID>empty();
                            }
                            return Mono.just((UUID) sbId);
                        }));
    }
}
