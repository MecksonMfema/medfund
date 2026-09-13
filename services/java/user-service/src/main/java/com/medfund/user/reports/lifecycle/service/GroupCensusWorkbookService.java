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
 * Workbook renderer for GROUP_CENSUS: one row per holder (corporate
 * group or ungrouped individual) at {@code asOf} with principal and
 * dependant status counts, plus a {@code Covered lives} total.
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
        String title = "Group Census - as of " + asOf;

        ReportWorkbook.SheetWriter detail = book.sheet("Holders");
        detail.titleMerged(title, 16)
                .meta("As of", asOf.toString())
                .meta("Holders", String.valueOf(result.groups().size()))
                .blankRow();
        detail.header(
                "Holder", "Type", "Registration #", "Contact person", "Contact email",
                "Active (principals)", "Suspended (principals)", "Lapsed (principals)",
                "Terminated (principals)", "Total principals",
                "Active (dependants)", "Suspended (dependants)", "Lapsed (dependants)",
                "Terminated (dependants)", "Total dependants",
                "Covered lives");
        detail.forEach(result.groups(), (sw, r) -> sw
                .text(nz(r.groupName()))
                .text(nz(r.holderType()))
                .text(nz(r.registrationNumber()))
                .text(nz(r.contactPerson()))
                .text(nz(r.contactEmail()))
                .number(r.activeMembers())
                .number(r.suspendedMembers())
                .number(r.lapsedMembers())
                .number(r.terminatedMembers())
                .number(r.totalMembers())
                .number(r.activeDependants())
                .number(r.suspendedDependants())
                .number(r.lapsedDependants())
                .number(r.terminatedDependants())
                .number(r.totalDependants())
                .number(r.coveredLives()));
        detail.freezeAtHeader().autoSize();

        return book.toBytes();
    }

    private static String nz(String s) { return s != null ? s : ""; }
}
