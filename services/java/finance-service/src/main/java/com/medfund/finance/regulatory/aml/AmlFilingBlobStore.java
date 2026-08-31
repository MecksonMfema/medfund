package com.medfund.finance.regulatory.aml;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO-backed persistence for AML per-STR filing XLSX blobs (Phase 26).
 * Separate from {@code MinIOPayloadStore} because that class is scoped to
 * the report-job chunk-payload upload path (fixed {@code jobId-chunkId-kind.json}
 * key + size-band gating); per-STR blobs need an arbitrary
 * {@code aml/str-filings/{tenantId}/{alertId}/{yyyyMMdd-HHmmss}.xlsx} key
 * with no size threshold — every FILED alert produces a blob.
 *
 * <p>The bean is {@code @ConditionalOnBean(MinioClient.class)} so it only
 * exists when the MinIO client is wired. Callers inject via
 * {@code Optional<AmlFilingBlobStore>} and skip blob upload silently on
 * services / test slices that don't opt in — the AML alert still transitions
 * to FILED, {@code filed_xlsx_ref} stays null (or carries a caller-supplied
 * manual ref).
 */
@Slf4j
@Component
@ConditionalOnBean(MinioClient.class)
public class AmlFilingBlobStore {

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MinioClient client;
    private final String bucket;

    public AmlFilingBlobStore(MinioClient client,
                              @Value("${minio.bucket.aml-filings:medfund-aml-filings}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Upload {@code bytes} under the deterministic key
     * {@code aml/str-filings/{tenantId}/{alertId}/{yyyyMMdd-HHmmss}.xlsx}
     * and return an {@code s3://<bucket>/<key>} reference.
     *
     * <p>Errors are wrapped in {@link AmlFilingBlobStoreException} — the
     * caller in {@link com.medfund.finance.regulatory.aml.service.AmlAlertService}
     * catches + logs + swallows to keep the workflow transition durable
     * (best-effort blob upload, mirrors the Kafka fan-out posture).
     */
    public String upload(UUID tenantId, UUID alertId, byte[] bytes) {
        if (tenantId == null || alertId == null) {
            throw new IllegalArgumentException("tenantId + alertId required for AML filing blob upload");
        }
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String key = "aml/str-filings/" + tenantId + "/" + alertId + "/" + timestamp + ".xlsx";
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(CONTENT_TYPE_XLSX)
                    .build());
            log.info("[aml-filing-blob] uploaded XLSX for tenant {} alert {} → s3://{}/{} ({} KB)",
                    tenantId, alertId, bucket, key, bytes.length / 1024);
            return "s3://" + bucket + "/" + key;
        } catch (Exception e) {
            throw new AmlFilingBlobStoreException(
                    "MinIO upload failed for tenant " + tenantId + " alert " + alertId, e);
        }
    }

    /** Fetch the previously uploaded blob back for a re-download. */
    public byte[] download(String ref) {
        String expectedPrefix = "s3://" + bucket + "/";
        if (ref == null || !ref.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(
                    "filedXlsxRef does not target bucket " + bucket + ": " + ref);
        }
        String key = ref.substring(expectedPrefix.length());
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new AmlFilingBlobStoreException(
                    "MinIO download failed for " + key, e);
        }
    }

    public static class AmlFilingBlobStoreException extends RuntimeException {
        public AmlFilingBlobStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
