-- V5 (#21, ADR-0015): the add log behind "3 adds of the same target a day". A row per device added by a commit, kept
-- for two days. It is its own table, not derived from group_messages or group_members: retention deletes acknowledged
-- messages and a remove-and-add cycle changes members, and neither may reset the limit. Expand-only (a new table); the
-- app role gets DML on it through the default privileges of V1.
CREATE TABLE gifitup.delivery_adds (
    adder_device_id  uuid        NOT NULL REFERENCES gifitup.devices (id) ON DELETE CASCADE,
    target_device_id uuid        NOT NULL REFERENCES gifitup.devices (id) ON DELETE CASCADE,
    added_at         timestamptz NOT NULL
);
CREATE INDEX delivery_adds_pair_idx ON gifitup.delivery_adds (adder_device_id, target_device_id, added_at);
CREATE INDEX delivery_adds_target_idx ON gifitup.delivery_adds (target_device_id);
CREATE INDEX delivery_adds_added_idx ON gifitup.delivery_adds (added_at);
