CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    username varchar(64) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role varchar(16) NOT NULL CHECK (role = 'USER'),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_app_users_username UNIQUE (username),
    CONSTRAINT ck_app_users_username CHECK (username ~ '^[a-z0-9_]{3,64}$')
);
