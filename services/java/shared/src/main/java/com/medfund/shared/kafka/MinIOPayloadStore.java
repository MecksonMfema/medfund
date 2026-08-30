package com.medfund.shared.kafka;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO-backed store for oversize report-job payloads (Phase 15 §10 / I25).
 *
 * <p>The report-job pipeline pushes chunk request + result payloads through
 * Kafka. Kafka's default 1 MB message ceiling is too small for GMM/VFA
 * projections on tenants with long-horizon LIFE cohorts. Rather than raise the
 * broker ceiling, oversize payloads are streamed to MinIO; the Kafka event
 * carries an {@code s3://<bucket>/<key>} reference that the consumer resolves
 * on the far side.
 *
 * <p>Size policy:
 * <ul>
 *   <li>≤ {@link #SIZE_WARN} — inline (returns {@code null})</li>
 *   <li>&gt; {@link #SIZE_WARN} and ≤ {@link #SIZE_LIMIT} — inline but logs a
 *       warning so operators see the payload approaching the ceiling</li>
 *   <li>&gt; {@link #SIZE_LIMIT} — uploaded to MinIO, returns the ref</li>
 * </ul>
 *
 * <p>Object naming: {@code {jobId}-{chunkId}-input.json} or
 * {@code -result.json}. Input payloads expire on a 7-day lifecycle rule wired
 * at bucket provisioning; result payloads follow the parent report_job
 * retention class (STATUTORY_7Y or OPERATIONAL_90D per I28) via
 * cascade-delete when the row purges.
 *
 * <p>The bean is {@code @ConditionalOnBean(MinioClient.class)} so it only
 * exists when {@link MinIOConfig} has itself been activated by
 * {@code minio.endpoint}. Callers that resolve the store via optional
 * injection ({@code Optional<MinIOPayloadStore>}) can fall back to inline-only
 * behaviour on services that don't opt in.
 */
@Slf4j
@Component
@ConditionalOnBean(MinioClient.class)
public class MinIOPayloadStore {

    /** Warn threshold — logs but still returns null so caller sends inline. */
    public static final int SIZE_WARN = 800 * 1024;

    /** Hard threshold — payload uploaded to MinIO and ref returned. */
    public static final int SIZE_LIMIT = 900 * 1024;

    private final MinioClient client;
    private final String bucket;

    public MinIOPayloadStore(
            MinioClient client,
            @Value("${minio.bucket.report-payloads:medfund-report-payloads}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Uploads {@code payload} to MinIO when it exceeds {@link #SIZE_LIMIT},
     * returning the {@code s3://} reference. Returns {@code null} when the
     * payload fits inline — the caller sends the bytes on the Kafka event.
     *
     * <p>Payloads in the {@link #SIZE_WARN}..{@link #SIZE_LIMIT} window log a
     * warning but still return {@code null}; operators use the warning to
     * spot tenants nearing the ceiling before compute breaks.
     */
    public String maybeUploadInput(UUID jobId, UUID chunkId, byte[] payload) {
        return maybeUpload(jobId, chunkId, payload, "input");
    }

    /**
     * Uploads a result payload to MinIO when oversize. Same semantics as
     * {@link #maybeUploadInput(UUID, UUID, byte[])}; the object key uses
     * {@code -result.json} so input + result never collide.
     */
    public String maybeUploadResult(UUID jobId, UUID chunkId, byte[] payload) {
        return maybeUpload(jobId, chunkId, payload, "result");
    }

    private String maybeUpload(UUID jobId, UUID chunkId, byte[] payload, String kind) {
        int size = payload.length;
        if (size > SIZE_WARN && size <= SIZE_LIMIT) {
            log.warn("Payload for job {} chunk {} ({}) nearing Kafka ceiling: {} KB",
                    jobId, chunkId, kind, size / 1024);
            return null;
        }
        if (size <= SIZE_LIMIT) {
            return null;
        }
        String key = jobId + "-" + chunkId + "-" + kind + ".json";
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(payload), size, -1)
                    .contentType("application/json")
                    .build());
            log.info("Uploaded oversize {} payload for job {} chunk {} → s3://{}/{} ({} KB)",
                    kind, jobId, chunkId, bucket, key, size / 1024);
            return "s3://" + bucket + "/" + key;
        } catch (Exception e) {
            throw new MinIOPayloadStoreException(
                    "MinIO upload failed for " + key, e);
        }
    }

    /** Downloads the object referenced by {@code payloadRef} into memory. */
    public byte[] download(String payloadRef) {
        String key = objectKey(payloadRef);
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new MinIOPayloadStoreException(
                    "MinIO download failed for " + key, e);
        }
    }

    /**
     * Best-effort delete. Failures are logged but never propagate — the caller
     * is typically a scheduled cleanup, and a missing object is not a bug.
     */
    public void delete(String payloadRef) {
        String key = objectKey(payloadRef);
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            log.warn("MinIO delete failed for {}: {}", key, e.getMessage());
        }
    }

    private String objectKey(String payloadRef) {
        String expectedPrefix = "s3://" + bucket + "/";
        if (payloadRef == null || !payloadRef.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(
                    "payloadRef does not target bucket " + bucket + ": " + payloadRef);
        }
        return payloadRef.substring(expectedPrefix.length());
    }

    /** Runtime wrapper for underlying MinIO client exceptions. */
    public static class MinIOPayloadStoreException extends RuntimeException {
        public MinIOPayloadStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
