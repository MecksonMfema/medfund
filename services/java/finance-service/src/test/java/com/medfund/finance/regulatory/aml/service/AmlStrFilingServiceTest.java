package com.medfund.finance.regulatory.aml.service;

import com.medfund.finance.regulatory.aml.AmlFilingBlobStore;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader;
import com.medfund.finance.regulatory.aml.AmlStrFilingShaperTest;
import com.medfund.finance.regulatory.aml.AmlStrFilingXlsxService;
import com.medfund.finance.regulatory.aml.AmlStrFilingXlsxService.AmlStrFilingRenderResult;
import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import com.medfund.shared.report.regulatory.TemplateSource;
import com.medfund.shared.tenant.TenantMetadataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AmlStrFilingServiceTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock AmlStrFilingXlsxService xlsxService;
    @Mock AmlFilingIdentityReader identityReader;
    @Mock TenantMetadataReader tenantMetadataReader;
    @Mock AmlFilingBlobStore blobStore;

    @Test
    void render_zipsTenantCountryAndIdentity_beforeXlsx() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        when(tenantMetadataReader.load(TENANT))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata("ZA", "ZA")));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new AmlFilingIdentityReader.AmlFilingIdentity(
                        "Acme ZA", "FIC-REG-42")));
        AmlStrFilingRenderResult rendered = new AmlStrFilingRenderResult(
                "bytes".getBytes(), TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-01-01");
        when(xlsxService.render(eq(TENANT), eq(alert),
                eq("Acme ZA"), eq("FIC-REG-42"), eq("ZA")))
                .thenReturn(Mono.just(rendered));

        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.empty());

        StepVerifier.create(service.render(TENANT, alert))
                .assertNext(r -> assertThat(r).isSameAs(rendered))
                .verifyComplete();
    }

    @Test
    void renderAndStore_withBlobStore_uploadsAndReturnsRef() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        when(tenantMetadataReader.load(TENANT))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata("ZA", "ZA")));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new AmlFilingIdentityReader.AmlFilingIdentity(
                        "Acme ZA", "FIC-REG-42")));
        byte[] xlsxBytes = "the-xlsx-bytes".getBytes();
        when(xlsxService.render(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new AmlStrFilingRenderResult(
                        xlsxBytes, TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-01-01")));
        when(blobStore.upload(eq(TENANT), eq(alert.getId()), eq(xlsxBytes)))
                .thenReturn("s3://medfund-aml-filings/aml/str-filings/T/A/20260403-143000.xlsx");

        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.of(blobStore));

        StepVerifier.create(service.renderAndStore(TENANT, alert))
                .assertNext(result -> {
                    assertThat(result.filedXlsxRef()).isPresent();
                    assertThat(result.filedXlsxRef().get())
                            .startsWith("s3://medfund-aml-filings/aml/str-filings/");
                    assertThat(result.rendered().bytes()).isEqualTo(xlsxBytes);
                })
                .verifyComplete();
    }

    @Test
    void renderAndStore_withoutBlobStore_returnsEmptyRef_butStillRenders() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        when(tenantMetadataReader.load(TENANT))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata("US", "US")));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new AmlFilingIdentityReader.AmlFilingIdentity(
                        "Acme US", "FINCEN-1")));
        when(xlsxService.render(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new AmlStrFilingRenderResult(
                        "bytes".getBytes(), TemplateSource.BUNDLED_SYNTHETIC, "SYNTHETIC_2024-01-01")));

        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.empty());

        StepVerifier.create(service.renderAndStore(TENANT, alert))
                .assertNext(result -> {
                    assertThat(result.filedXlsxRef()).isEmpty();
                    assertThat(result.rendered().bytes()).isNotEmpty();
                })
                .verifyComplete();
    }

    @Test
    void renderAndStore_blobStoreThrows_propagatesSoCallerCanSwallow() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        when(tenantMetadataReader.load(TENANT))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata("ZA", "ZA")));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new AmlFilingIdentityReader.AmlFilingIdentity(
                        "Acme", "REG-1")));
        when(xlsxService.render(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new AmlStrFilingRenderResult(
                        "bytes".getBytes(), TemplateSource.BUNDLED_SYNTHETIC, "v1")));
        when(blobStore.upload(any(), any(), any()))
                .thenThrow(new AmlFilingBlobStore.AmlFilingBlobStoreException(
                        "MinIO unavailable", new RuntimeException()));

        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.of(blobStore));

        StepVerifier.create(service.renderAndStore(TENANT, alert))
                .expectError(AmlFilingBlobStore.AmlFilingBlobStoreException.class)
                .verify();
    }

    @Test
    void render_nullTenant_isRejected() {
        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.empty());
        StepVerifier.create(service.render(null, AmlStrFilingShaperTest.filedAlert()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void render_nullAlert_isRejected() {
        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.empty());
        StepVerifier.create(service.render(TENANT, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void renderAndStore_capturesUploadCall_forEvidence() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        when(tenantMetadataReader.load(TENANT))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata("ZA", "ZA")));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new AmlFilingIdentityReader.AmlFilingIdentity(
                        "Acme ZA", "FIC-REG-42")));
        byte[] xlsxBytes = "xlsx".getBytes();
        when(xlsxService.render(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new AmlStrFilingRenderResult(
                        xlsxBytes, TemplateSource.BUNDLED_SYNTHETIC, "v1")));
        when(blobStore.upload(any(), any(), any())).thenReturn("s3://b/k.xlsx");

        AmlStrFilingService service = new AmlStrFilingService(
                xlsxService, identityReader, tenantMetadataReader, Optional.of(blobStore));

        service.renderAndStore(TENANT, alert).block();

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<UUID> alertIdCap = ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.verify(blobStore).upload(eq(TENANT), alertIdCap.capture(), bytesCap.capture());
        assertThat(alertIdCap.getValue()).isEqualTo(alert.getId());
        assertThat(bytesCap.getValue()).isEqualTo(xlsxBytes);
    }
}
