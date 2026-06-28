package storage

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// MinIOStore is a thin wrapper around the MinIO Go SDK scoped to one
// bucket. Used for uploading rendered invoice PDFs and serving them
// back to notification-service via short-lived presigned URLs.
type MinIOStore struct {
	client *minio.Client
	bucket string
}

func NewMinIOStore(endpoint, accessKey, secretKey, bucket string, useSSL bool) (*MinIOStore, error) {
	cli, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: useSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("init minio client: %w", err)
	}

	// Idempotent — create the bucket if a fresh dev volume gave us an
	// empty MinIO. Production setups should pre-create the bucket so
	// the service doesn't need bucket-create permissions.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := ensureBucket(ctx, cli, bucket); err != nil {
		log.Printf("[storage] could not ensure bucket %q: %v (will continue and let uploads surface the failure)", bucket, err)
	}

	return &MinIOStore{client: cli, bucket: bucket}, nil
}

func ensureBucket(ctx context.Context, cli *minio.Client, bucket string) error {
	exists, err := cli.BucketExists(ctx, bucket)
	if err != nil {
		return err
	}
	if exists {
		return nil
	}
	return cli.MakeBucket(ctx, bucket, minio.MakeBucketOptions{})
}

// PutObject uploads bytes to <bucket>/<key> and returns the resulting
// object key. Caller is responsible for choosing a stable, unambiguous
// key — we never auto-rename or version because the invoice number
// already provides identity.
func (s *MinIOStore) PutObject(ctx context.Context, key string, data []byte, contentType string) (string, error) {
	_, err := s.client.PutObject(ctx, s.bucket, key, bytes.NewReader(data), int64(len(data)),
		minio.PutObjectOptions{ContentType: contentType})
	if err != nil {
		return "", fmt.Errorf("put object %q: %w", key, err)
	}
	return key, nil
}

// GetObject streams the object at <bucket>/<key> back to the caller.
// Used by the /invoice-pdf streaming route the contributions-service
// delegates to so PDF storage stays encapsulated in this service.
// Caller is responsible for closing the returned reader.
func (s *MinIOStore) GetObject(ctx context.Context, bucket, key string) (
		io.ReadCloser, int64, string, error) {
	if bucket == "" {
		bucket = s.bucket
	}
	obj, err := s.client.GetObject(ctx, bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, 0, "", fmt.Errorf("get object %q/%q: %w", bucket, key, err)
	}
	stat, err := obj.Stat()
	if err != nil {
		_ = obj.Close()
		return nil, 0, "", fmt.Errorf("stat object %q/%q: %w", bucket, key, err)
	}
	return obj, stat.Size, stat.ContentType, nil
}

// PresignedGet returns a time-limited URL the notification-service can
// use to fetch the object back. Keep the window short — these URLs
// shouldn't be reused beyond the email send.
func (s *MinIOStore) PresignedGet(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := s.client.PresignedGetObject(ctx, s.bucket, key, ttl, nil)
	if err != nil {
		return "", fmt.Errorf("presign get %q: %w", key, err)
	}
	return u.String(), nil
}

// Bucket returns the bucket name — useful for log messages and event
// payloads that want to fully qualify the object location.
func (s *MinIOStore) Bucket() string { return s.bucket }
