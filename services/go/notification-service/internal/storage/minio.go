package storage

import (
	"context"
	"fmt"
	"io"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// MinIOFetcher implements invoice.PdfFetcher against the same MinIO
// bucket file-service uploads to.
type MinIOFetcher struct {
	client *minio.Client
}

func NewMinIOFetcher(endpoint, accessKey, secretKey string, useSSL bool) (*MinIOFetcher, error) {
	cli, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: useSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("init minio: %w", err)
	}
	return &MinIOFetcher{client: cli}, nil
}

func (f *MinIOFetcher) GetObject(ctx context.Context, bucket, key string) ([]byte, error) {
	obj, err := f.client.GetObject(ctx, bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, fmt.Errorf("get %s/%s: %w", bucket, key, err)
	}
	defer obj.Close()
	return io.ReadAll(obj)
}
