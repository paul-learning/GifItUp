-- V2 (#13, ADR-0010): invites and the invite tree. Runs as gifitup_owner; expand-only (a new table and nullable or
-- defaulted columns), so the previous release keeps working on this schema after a rollback (ADR-0008).

-- One row per invite. Only the SHA-256 of the 128-bit code is stored; the code itself never reaches the database.
-- created_by NULL = an admin invite (bootstrap, created with the admin CLI on the VM).
CREATE TABLE gifitup.invites (
    id         uuid        PRIMARY KEY,
    code_hash  bytea       NOT NULL,
    created_by uuid        REFERENCES gifitup.accounts (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    used_by    uuid        REFERENCES gifitup.accounts (id) ON DELETE SET NULL,
    revoked_at timestamptz,
    CONSTRAINT invites_code_hash_key UNIQUE (code_hash),
    CONSTRAINT invites_code_hash_length CHECK (octet_length(code_hash) = 32),
    CONSTRAINT invites_expiry_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX invites_created_by_idx ON gifitup.invites (created_by);

-- The invite tree (contacts, #18): who invited this account. NULL for accounts that joined with an admin invite
-- (joined_via_admin) and for accounts whose inviter was deleted; joined_via_admin tells the two apart (#13 SEC-46).
ALTER TABLE gifitup.accounts ADD COLUMN invited_by uuid REFERENCES gifitup.accounts (id) ON DELETE SET NULL;
ALTER TABLE gifitup.accounts ADD COLUMN joined_via_admin boolean NOT NULL DEFAULT false;
CREATE INDEX accounts_invited_by_idx ON gifitup.accounts (invited_by);
