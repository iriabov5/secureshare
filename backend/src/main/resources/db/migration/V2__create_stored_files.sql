CREATE TABLE stored_files (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_users(id),
    original_name varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    object_key varchar(80) UNIQUE NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('UPLOADING', 'READY', 'FAILED')),
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_stored_files_owner_status_created ON stored_files(owner_id, status, created_at DESC, id DESC);
