package com.medfund.finance.service;

import com.medfund.finance.dto.CreditorFilterParams;
import com.medfund.finance.dto.CreditorRow;
import com.medfund.finance.dto.MemberBalanceResponse;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.ProviderBalanceResponse;
import com.medfund.finance.repository.CreditorQueryRepository;
import com.medfund.finance.repository.MemberBalanceQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Façade over the two per-subject balance stacks (provider + member).
 * Owns the read-path composition for the unified /api/v1/creditors
 * surface — pagination, detail lookups, Excel export.
 *
 * <p>No writes live here; each subject-type still owns its own writer
 * path ({@link ProviderBalanceService#updateBalance} and
 * {@link MemberBalanceService#updateBalance}). This service exists
 * purely to combine reads for the unified UI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditorService {

    private final CreditorQueryRepository queryRepository;
    private final MemberBalanceQueryRepository memberBalanceQueryRepository;
    private final ProviderBalanceService providerBalanceService;
    private final CreditorsExcelService excelService;

    public Mono<PageResponse<CreditorRow>> searchPaged(CreditorFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        var effective = new CreditorFilterParams(
                params.subjectType(), params.currencyCode(), params.q(),
                params.sortKey(), params.sortDirection(), page, size);
        return queryRepository.search(effective, size, offset)
                .collectList()
                .zipWith(queryRepository.count(effective))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Mono<ProviderBalanceResponse> providerDetail(UUID providerId) {
        return providerBalanceService.findByProviderId(providerId).map(ProviderBalanceResponse::from);
    }

    public Flux<MemberBalanceResponse> memberDetail(UUID memberId) {
        return memberBalanceQueryRepository.findByMemberId(memberId);
    }

    public Mono<byte[]> exportExcel(String subjectType, String currencyCode, String q) {
        return excelService.generate(subjectType, currencyCode, q);
    }
}
