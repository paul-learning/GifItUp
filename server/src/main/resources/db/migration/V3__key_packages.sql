-- V3 (#14, ADR-0012): the KeyPackage directory. Runs as gifitup_owner; expand-only (a new table), so the previous
-- release keeps working on this schema after a rollback (ADR-0008). The app role gets DML on it through the default
-- privileges of V1.

-- One row per unclaimed KeyPackage of a device. A claim DELETEs the row: nothing at rest records who claimed what or
-- when. ciphersuite is read from the KeyPackage bytes by the server (never client-supplied) and is an MLS cipher suite
-- id, a uint16, so integer. key_package is the MLSMessage bytes; the reader accepts at most 2048 bytes.
CREATE TABLE gifitup.key_packages (
    id          uuid        PRIMARY KEY,
    device_id   uuid        NOT NULL REFERENCES gifitup.devices (id) ON DELETE CASCADE,
    ciphersuite integer     NOT NULL,
    key_package bytea       NOT NULL,
    not_after   timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT key_packages_key_package_key UNIQUE (key_package),
    CONSTRAINT key_packages_ciphersuite_range CHECK (ciphersuite BETWEEN 1 AND 65535),
    CONSTRAINT key_packages_size CHECK (octet_length(key_package) BETWEEN 1 AND 2048)
);
-- The claim picks by (device, suite, oldest first); the cleanup scans by expiry.
CREATE INDEX key_packages_claim_idx ON gifitup.key_packages (device_id, ciphersuite, created_at);
CREATE INDEX key_packages_not_after_idx ON gifitup.key_packages (not_after);
