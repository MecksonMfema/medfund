package com.medfund.finance.report.schedule;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Phase 17 §A.2 — MinIO-backed persistence for scheduled-report XLSX blobs.
 * Object keys are {@code <tenantId>/<yyyy>/<MM>/<jobId>.xlsx} — one row per
 * fired {@code report_job} — so cleanup can prefix-scan by month and delete
 * batches at retention time (Phase 15 §14 pattern).
 *
 * <p>The bean is {@code @ConditionalOnBean(MinioClient.class)} so the finance
 * boot stays green in test slices that don't wire MinIO — the orchestrator
 * injects via {@code Optional<ReportPayloadStore>} and errors clearly if a
 * schedule fires without a MinIO destination.
 */
@Slf4j
@Component
@ConditionalOnBean(MinioClient.class)
public class ReportPayloadStore {

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MinioClient client;
    private final String bucket;

    public ReportPayloadStore(MinioClient client,
                              @Value("${minio.bucket.report-payloads:medfund-report-payloads}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public String bucket() {
        return bucket;
    }

    public Mono<Void> putXlsx(String objectKey, byte[] bytes) {
        return Mono.fromRunnable(() -> {
            try {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                        .contentType(CONTENT_TYPE_XLSX)
                        .build());
                log.info("[report-payload] uploaded s3://{}/{} ({} KB)",
                        bucket, objectKey, bytes.length / 1024);
            } catch (Exception e) {
                throw new ReportPayloadStoreException(
                        "MinIO putObject failed for " + objectKey, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<byte[]> getXlsx(String objectKey) {
        return Mono.fromCallable(() -> {
            try (InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build())) {
                return in.readAllBytes();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public static class ReportPayloadStoreException extends RuntimeException {
        public ReportPayloadStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
