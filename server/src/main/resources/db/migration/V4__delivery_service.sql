-- V4 (#21, ADR-0015): the Delivery Service. Runs as gifitup_owner; expand-only (new tables), so the previous release
-- keeps working on this schema after a rollback (ADR-0008). The app role gets DML on the tables through the default
-- privileges of V1.

-- One row per MLS group. The server never parses MLS: id is the opaque group id the creating client chose
-- (16..64 bytes). epoch and next_seq are advanced only under a row lock (SELECT ... FOR UPDATE), so seq order equals
-- commit order. creator_device_id is the device that created the group; it is NULL once that device is gone and the
-- creator's right to remove others lapses with it.
CREATE TABLE gifitup.groups (
    id                bytea       PRIMARY KEY,
    creator_device_id uuid        REFERENCES gifitup.devices (id) ON DELETE SET NULL,
    epoch             bigint      NOT NULL,
    next_seq          bigint      NOT NULL,
    retained_bytes    bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    CONSTRAINT groups_id_size CHECK (octet_length(id) BETWEEN 16 AND 64),
    CONSTRAINT groups_counters CHECK (epoch >= 0 AND next_seq >= 1 AND retained_bytes >= 0)
);
CREATE INDEX groups_creator_idx ON gifitup.groups (creator_device_id);

-- Current members. joined_seq is the seq of the commit that added the device (0 for the creator): a device never
-- sees messages up to it. acked_seq starts at joined_seq. last_sent_seq and last_client_msg_id are the device's own
-- last accepted message, the freshness anchor (ADR-0002, ADR-0015); set in the same transaction as the append.
CREATE TABLE gifitup.group_members (
    group_id           bytea       NOT NULL REFERENCES gifitup.groups (id) ON DELETE CASCADE,
    device_id          uuid        NOT NULL REFERENCES gifitup.devices (id) ON DELETE CASCADE,
    joined_seq         bigint      NOT NULL,
    acked_seq          bigint      NOT NULL,
    last_sent_seq      bigint,
    last_client_msg_id bytea,
    last_acked_at      timestamptz NOT NULL,
    added_by_device_id uuid        REFERENCES gifitup.devices (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL,
    PRIMARY KEY (group_id, device_id),
    CONSTRAINT group_members_cursors CHECK (acked_seq >= joined_seq AND joined_seq >= 0),
    CONSTRAINT group_members_msg_id_size CHECK (last_client_msg_id IS NULL OR octet_length(last_client_msg_id) = 16)
);
CREATE INDEX group_members_device_idx ON gifitup.group_members (device_id);
CREATE INDEX group_members_added_by_idx ON gifitup.group_members (added_by_device_id, created_at);

-- The per-group append-only log. kind: 1 commit, 2 application. epoch, added and removed describe a commit (the
-- envelope, visible to the server by design, ADR-0002); payload is opaque ciphertext. client_msg_id makes a retry
-- idempotent; payload_hash lets a repeat be told from a different message with the same id. It is never sent to
-- recipients.
CREATE TABLE gifitup.group_messages (
    group_id         bytea       NOT NULL REFERENCES gifitup.groups (id) ON DELETE CASCADE,
    seq              bigint      NOT NULL,
    sender_device_id uuid        REFERENCES gifitup.devices (id) ON DELETE SET NULL,
    kind             smallint    NOT NULL,
    epoch            bigint,
    added            uuid[]      NOT NULL,
    removed          uuid[]      NOT NULL,
    client_msg_id    bytea       NOT NULL,
    payload_hash     bytea       NOT NULL,
    payload          bytea       NOT NULL,
    created_at       timestamptz NOT NULL,
    PRIMARY KEY (group_id, seq),
    CONSTRAINT group_messages_kind CHECK (kind IN (1, 2)),
    CONSTRAINT group_messages_msg_id_size CHECK (octet_length(client_msg_id) = 16),
    CONSTRAINT group_messages_hash_size CHECK (octet_length(payload_hash) = 32),
    CONSTRAINT group_messages_payload_size CHECK (octet_length(payload) BETWEEN 1 AND 16384)
);
-- Retry lookups; sender_device_id is NULL once the sender is deleted, which never matches.
CREATE UNIQUE INDEX group_messages_client_msg_idx ON gifitup.group_messages (group_id, sender_device_id, client_msg_id);
CREATE INDEX group_messages_created_idx ON gifitup.group_messages (created_at);

-- A Welcome waiting for its target device. Deleted on ack or after the TTL. adder_device_id bounds pending welcomes
-- per (adder, target); it is NULL once the adder is deleted.
CREATE TABLE gifitup.welcomes (
    id               uuid        PRIMARY KEY,
    group_id         bytea       NOT NULL REFERENCES gifitup.groups (id) ON DELETE CASCADE,
    target_device_id uuid        NOT NULL REFERENCES gifitup.devices (id) ON DELETE CASCADE,
    adder_device_id  uuid        REFERENCES gifitup.devices (id) ON DELETE SET NULL,
    welcome          bytea       NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT welcomes_size CHECK (octet_length(welcome) BETWEEN 1 AND 16384)
);
CREATE INDEX welcomes_target_idx ON gifitup.welcomes (target_device_id, created_at);
CREATE INDEX welcomes_adder_idx ON gifitup.welcomes (adder_device_id, target_device_id);
CREATE INDEX welcomes_created_idx ON gifitup.welcomes (created_at);
