ALTER TABLE stored_files ADD COLUMN storage_format varchar(16) NOT NULL DEFAULT 'LEGACY_PLAIN';
ALTER TABLE stored_files ADD COLUMN ciphertext_size_bytes bigint;
ALTER TABLE stored_files ADD COLUMN legacy_object_key varchar(80);

ALTER TABLE stored_files ADD CONSTRAINT ck_stored_files_encryption CHECK (
    (storage_format = 'LEGACY_PLAIN' AND ciphertext_size_bytes IS NULL AND legacy_object_key IS NULL)
    OR
    (storage_format = 'AEAD_V1' AND ciphertext_size_bytes IS NOT NULL
        AND ciphertext_size_bytes = size_bytes + 33
        AND (legacy_object_key IS NULL OR legacy_object_key <> object_key))
);
ALTER TABLE stored_files ALTER COLUMN storage_format DROP DEFAULT;
