package com.medfund.shared.kafka;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provisions a shared {@link MinioClient} bean for services that opt into the
 * MinIO oversize-payload fallback introduced in Phase 15 §10 (I25).
 *
 * <p>Guarded by {@code @ConditionalOnProperty(name = "minio.endpoint")} so a
 * service that never sets the property boots without a MinIO client and never
 * constructs {@link MinIOPayloadStore}. Enabling the store is purely a
 * configuration choice — the classpath cost is unconditional.
 *
 * <p>Configuration keys:
 * <ul>
 *   <li>{@code minio.endpoint} — e.g. {@code http://minio:9000}</li>
 *   <li>{@code minio.access-key} — MinIO access key</li>
 *   <li>{@code minio.secret-key} — MinIO secret key</li>
 *   <li>{@code minio.bucket.report-payloads} — bucket name; defaults to
 *       {@code medfund-report-payloads}</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "minio.endpoint")
public class MinIOConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
