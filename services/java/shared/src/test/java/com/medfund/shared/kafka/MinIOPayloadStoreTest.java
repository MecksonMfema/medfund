package com.medfund.shared.kafka;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour contract for {@link MinIOPayloadStore}. Pins the three-band size
 * policy (inline / warn-inline / upload), the round-trip on download, the
 * best-effort semantics of delete, and the {@code s3://} URI shape callers
 * rely on to route between inline JSON and the MinIO fetch path.
 */
@ExtendWith(MockitoExtension.class)
class MinIOPayloadStoreTest {

    private static final String BUCKET = "medfund-report-payloads";

    @Mock
    private MinioClient client;

    private MinIOPayloadStore store;

    @BeforeEach
    void setUp() {
        store = new MinIOPayloadStore(client, BUCKET);
    }

    @Test
    void maybeUploadInput_belowWarnThreshold_returnsNullAndSkipsUpload() throws Exception {
        byte[] payload = new byte[100 * 1024]; // 100 KB — inline

        String ref = store.maybeUploadInput(UUID.randomUUID(), UUID.randomUUID(), payload);

        assertThat(ref).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void maybeUploadInput_atExactSizeLimit_staysInline() throws Exception {
        // 900 KB — the boundary itself is still inline; only strictly above uploads.
        byte[] payload = new byte[MinIOPayloadStore.SIZE_LIMIT];

        String ref = store.maybeUploadInput(UUID.randomUUID(), UUID.randomUUID(), payload);

        assertThat(ref).isNull();
        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void maybeUploadInput_betweenWarnAndLimit_staysInlineButLogs() throws Exception {
        // 850 KB — inside the warn band. Inline, no upload.
        byte[] payload = new byte[850 * 1024];

        String ref = store.maybeUploadInput(UUID.randomUUID(), UUID.randomUUID(), payload);

        assertThat(ref).isNull();
        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void maybeUploadInput_overLimit_uploadsAndReturnsRef() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        byte[] payload = new byte[MinIOPayloadStore.SIZE_LIMIT + 1];

        String ref = store.maybeUploadInput(jobId, chunkId, payload);

        assertThat(ref).isEqualTo("s3://" + BUCKET + "/" + jobId + "-" + chunkId + "-input.json");
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo(jobId + "-" + chunkId + "-input.json");
        assertThat(args.contentType()).isEqualTo("application/json");
    }

    @Test
    void maybeUploadResult_overLimit_usesResultSuffix() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        byte[] payload = new byte[MinIOPayloadStore.SIZE_LIMIT + 1];

        String ref = store.maybeUploadResult(jobId, chunkId, payload);

        assertThat(ref).endsWith("-result.json");
        assertThat(ref).isNotEqualTo("s3://" + BUCKET + "/" + jobId + "-" + chunkId + "-input.json");
    }

    @Test
    void maybeUploadInput_wrapsMinioFailureAsRuntimeException() throws Exception {
        byte[] payload = new byte[MinIOPayloadStore.SIZE_LIMIT + 1];
        doThrow(new RuntimeException("minio disk full"))
                .when(client).putObject(any(PutObjectArgs.class));

        assertThatThrownBy(() -> store.maybeUploadInput(UUID.randomUUID(), UUID.randomUUID(), payload))
                .isInstanceOf(MinIOPayloadStore.MinIOPayloadStoreException.class)
                .hasMessageContaining("MinIO upload failed");
    }

    @Test
    void download_returnsBytesFromClient() throws Exception {
        byte[] expected = "hello".getBytes();
        UUID jobId = UUID.randomUUID();
        String key = jobId + "-x-input.json";
        String ref = "s3://" + BUCKET + "/" + key;
        when(client.getObject(any(GetObjectArgs.class)))
                .thenReturn(stubGetObjectResponse(expected));

        byte[] actual = store.download(ref);

        assertThat(actual).containsExactly(expected);
        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo(key);
    }

    @Test
    void download_rejectsRefWithWrongBucket() {
        assertThatThrownBy(() -> store.download("s3://some-other-bucket/foo.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void download_rejectsRefWithoutS3Prefix() {
        assertThatThrownBy(() -> store.download("http://example.com/blob"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void download_wrapsMinioFailureAsRuntimeException() throws Exception {
        when(client.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("network blip"));

        assertThatThrownBy(() -> store.download("s3://" + BUCKET + "/foo.json"))
                .isInstanceOf(MinIOPayloadStore.MinIOPayloadStoreException.class)
                .hasMessageContaining("MinIO download failed");
    }

    @Test
    void delete_swallowsMinioFailures() throws Exception {
        // Best-effort — no exception should propagate to the retention job.
        doThrow(new RuntimeException("object already gone"))
                .when(client).removeObject(any(RemoveObjectArgs.class));

        store.delete("s3://" + BUCKET + "/x-input.json");

        verify(client).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void delete_stripsPrefixAndPassesKey() throws Exception {
        String key = UUID.randomUUID() + "-y-result.json";

        store.delete("s3://" + BUCKET + "/" + key);

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo(key);
    }

    private static GetObjectResponse stubGetObjectResponse(byte[] bytes) {
        return new GetObjectResponse(
                null,
                BUCKET,
                null,
                "obj",
                new ByteArrayInputStream(bytes));
    }
}
