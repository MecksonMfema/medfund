package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportWorkbook;
import com.medfund.user.reports.lifecycle.dto.GroupCensusResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 13 §C Phase 8 workbook renderer for GROUP_CENSUS — one row per
 * group at {@code asOf} with per-status member counts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupCensusWorkbookService {

    private final GroupCensusReportService reportService;

    public Mono<byte[]> workbook(LocalDate asOf, UUID groupId, String statusFilter,
                                 String overrideCurrency) {
        return reportService.generate(asOf, groupId, statusFilter, overrideCurrency)
                .map(response -> render(asOf, response.data()));
    }

    private byte[] render(LocalDate asOf, GroupCensusResult result) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Group Census — as of " + asOf;

        ReportWorkbook.SheetWriter detail = book.sheet("Groups");
        detail.titleMerged(title, 9)
                .meta("As of", asOf.toString())
                .meta("Groups", String.valueOf(result.groups().size()))
                .blankRow();
        detail.header("Group name", "Registration #", "Contact person", "Contact email",
                "Active", "Suspended", "Lapsed", "Terminated", "Total");
        detail.forEach(result.groups(), (sw, r) -> sw
                .text(nz(r.groupName()))
                .text(nz(r.registrationNumber()))
                .text(nz(r.contactPerson()))
                .text(nz(r.contactEmail()))
                .number(r.activeMembers())
                .number(r.suspendedMembers())
                .number(r.lapsedMembers())
                .number(r.terminatedMembers())
                .number(r.totalMembers()));
        detail.freezeAtHeader().autoSize();

        return book.toBytes();
    }

    private static String nz(String s) { return s != null ? s : ""; }
}
