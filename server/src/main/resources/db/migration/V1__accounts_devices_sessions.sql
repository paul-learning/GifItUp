-- V1 (#12, ADR-0008): accounts, devices, sessions. Runs as gifitup_owner (the migrate connection sets role gifitup_owner at startup).
-- Migrations are expand-only: the previous release must keep working against the new schema (deploy rollback).
-- Every table is schema-qualified: Flyway's search_path points at its own history schema while migrating.

-- The app role may use the schema and read/write rows of tables the owner creates in it, now and later. It gets
-- nothing on the flyway schema, so it can't touch the migration history.
GRANT USAGE ON SCHEMA gifitup TO gifitup_app;
ALTER DEFAULT PRIVILEGES FOR ROLE gifitup_owner IN SCHEMA gifitup
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO gifitup_app;
ALTER DEFAULT PRIVILEGES FOR ROLE gifitup_owner IN SCHEMA gifitup
    GRANT USAGE, SELECT ON SEQUENCES TO gifitup_app;

-- Usernames follow the crate's rule byte for byte (ADR-0007): ^[a-z0-9_]{3,32}$. The CHECK backs up the server's own
-- validation; uniqueness comes from the constraint, not from a read before the insert.
CREATE TABLE gifitup.accounts (
    id         uuid        PRIMARY KEY,
    username   text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT accounts_username_key UNIQUE (username),
    CONSTRAINT accounts_username_rule CHECK (username ~ '^[a-z0-9_]{3,32}$')
);

-- signature_key: the device's Ed25519 MLS signature public key, which is also its auth key (#12). Unique: one key, one
-- device.
CREATE TABLE gifitup.devices (
    id            uuid        PRIMARY KEY,
    account_id    uuid        NOT NULL REFERENCES gifitup.accounts (id) ON DELETE CASCADE,
    signature_key bytea       NOT NULL,
    created_at    timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL,
    CONSTRAINT devices_signature_key_key UNIQUE (signature_key),
    CONSTRAINT devices_signature_key_length CHECK (octet_length(signature_key) = 32)
);
CREATE INDEX devices_account_id_idx ON gifitup.devices (account_id);

-- Only the SHA-256 of a session token is stored; the token itself never reaches the database.
CREATE TABLE gifitup.sessions (
    token_hash bytea       PRIMARY KEY,
    device_id  uuid        NOT NULL REFERENCES gifitup.devices (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT sessions_token_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT sessions_expiry_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX sessions_device_id_idx ON gifitup.sessions (device_id);
