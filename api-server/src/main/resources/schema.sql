CREATE TABLE IF NOT EXISTS videos (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    original_minio_key VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transcoded_files (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    resolution VARCHAR(10) NOT NULL,
    minio_key VARCHAR(500) NOT NULL,
    file_size BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transcoded_files_video_id ON transcoded_files(video_id);
