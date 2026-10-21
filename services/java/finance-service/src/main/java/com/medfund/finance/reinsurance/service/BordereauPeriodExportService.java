package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.entity.BordereauPeriodExport;
import com.medfund.finance.reinsurance.repository.BordereauPeriodExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Soft-lock registry for quarter-aligned bordereau exports. First export
 * of a {@code (reinsurer, treaty, reportKey, year, quarter)} tuple inserts
 * a row and records {@code firstExportedAt}; subsequent re-exports of the
 * same tuple bump {@code exportCount}. Cessions created after
 * {@code firstExportedAt} inside the quarter are flagged as
 * {@code isPriorPeriodAdjustment} on subsequent exports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BordereauPeriodExportService {

    private final BordereauPeriodExportRepository repository;

    /**
     * Upsert semantics: first export inserts a row with {@code exportCount = 1};
     * every subsequent export for the same key increments {@code exportCount}.
     * Returns the row after the write.
     */
    @Transactional
    public Mono<BordereauPeriodExport> markExported(UUID reinsurerId, UUID treatyId,
                                                    String reportKey, int year, int quarter,
                                                    String actorId, String actorEmail) {
        return findExisting(reinsurerId, treatyId, reportKey, year, quarter)
                .flatMap(existing -> {
                    existing.setExportCount(existing.getExportCount() + 1);
                    return repository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> insertNew(reinsurerId, treatyId, reportKey,
                        year, quarter, actorId, actorEmail)));
    }

    /**
     * Returns {@code firstExportedAt} for the given tuple if this quarter has
     * been exported at least once, else empty. Callers use this to decide the
     * {@code isPriorPeriodAdjustment} flag per row.
     */
    public Mono<OffsetDateTime> firstExportedAt(UUID reinsurerId, UUID treatyId,
                                                String reportKey, int year, int quarter) {
        return findExisting(reinsurerId, treatyId, reportKey, year, quarter)
                .map(BordereauPeriodExport::getFirstExportedAt);
    }

    private Mono<BordereauPeriodExport> findExisting(UUID reinsurerId, UUID treatyId,
                                                     String reportKey, int year, int quarter) {
        return treatyId == null
                ? repository.findByCompositeKeyNoTreaty(reinsurerId, reportKey, year, quarter)
                : repository.findByCompositeKey(reinsurerId, treatyId, reportKey, year, quarter);
    }

    private Mono<BordereauPeriodExport> insertNew(UUID reinsurerId, UUID treatyId,
                                                  String reportKey, int year, int quarter,
                                                  String actorId, String actorEmail) {
        BordereauPeriodExport row = new BordereauPeriodExport();
        row.setReinsurerId(reinsurerId);
        row.setTreatyId(treatyId);
        row.setReportKey(reportKey);
        row.setYear(year);
        row.setQuarter(quarter);
        row.setFirstExportedAt(OffsetDateTime.now());
        row.setExportCount(1);
        row.setActorId(parseUuid(actorId));
        row.setActorEmail(actorEmail);
        return repository.save(row);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
