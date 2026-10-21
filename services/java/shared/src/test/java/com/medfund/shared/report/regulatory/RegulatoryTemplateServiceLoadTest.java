package com.medfund.shared.report.regulatory;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused tests for {@link RegulatoryTemplateService#load(UUID, String, String, java.time.LocalDate)}
 * — the Phase 4 extension that consults tenant overrides before falling back to bundled resources.
 * The bundled-only pure functions live in {@link RegulatoryTemplateServiceTest}.
 */
class RegulatoryTemplateServiceLoadTest {

    private static byte[] tinyXlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("hello");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void load_returnsTenantOverrideWhenPresent() {
        var reader = mock(TenantRegulatoryTemplateOverrideReader.class);
        when(reader.findEffective(any(UUID.class), anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(reactor.core.publisher.Mono.just(
                        new TenantRegulatoryTemplateOverrideReader.Overlay(tinyXlsx(), "custom-1")));

        var service = new RegulatoryTemplateService(Optional.of(reader));

        var resolution = service.load(UUID.randomUUID(), "ipec", "ipec-quarterly-return", LocalDate.of(2026, 1, 1))
                .block();

        assertThat(resolution).isNotNull();
        assertThat(resolution.source()).isEqualTo(TemplateSource.TENANT_OVERRIDE);
        assertThat(resolution.versionLabel()).isEqualTo("custom-1");
        assertThat(resolution.workbook().getSheet("Sheet1").getRow(0).getCell(0).getStringCellValue()).isEqualTo("hello");
        assertThat(resolution.isTenantOverride()).isTrue();
        assertThat(resolution.isSynthetic()).isFalse();
    }

    @Test
    void load_fallsBackToBundled_throwsWhenNothingMatches() {
        var reader = mock(TenantRegulatoryTemplateOverrideReader.class);
        when(reader.findEffective(any(UUID.class), anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(reactor.core.publisher.Mono.empty());

        var service = new RegulatoryTemplateService(Optional.of(reader));

        // No bundle under report-templates/nonexistent-regulator in classpath, and no override.
        assertThatThrownBy(() -> service.load(UUID.randomUUID(), "nonexistent-regulator", "x",
                LocalDate.of(2026, 1, 1)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No bundled regulator template found");
    }

    @Test
    void load_noOverrideReader_stillLooksUpBundled_thenThrows() {
        var service = new RegulatoryTemplateService(); // no-arg → no reader
        assertThatThrownBy(() -> service.load(UUID.randomUUID(), "nonexistent-regulator", "x",
                LocalDate.of(2026, 1, 1)).block())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extractVersionLabel_syntheticMarkerPreserved() {
        assertThat(RegulatoryTemplateService.extractVersionLabel(
                "report-templates/cms/cms-asr-v_SYNTHETIC_2026-08-30.xlsx"))
                .isEqualTo("SYNTHETIC_2026-08-30");
        assertThat(RegulatoryTemplateService.extractVersionLabel(
                "report-templates/ipec/ipec-quarterly-return-v_2024-06-01.xlsx"))
                .isEqualTo("2024-06-01");
        assertThat(RegulatoryTemplateService.extractVersionLabel("nonsense")).isNull();
        assertThat(RegulatoryTemplateService.extractVersionLabel(null)).isNull();
    }
}
