CREATE TABLE agent_platform_catalog_source (
    source_key TEXT PRIMARY KEY NOT NULL,warning TEXT NOT NULL DEFAULT '',checked_at BIGINT NOT NULL DEFAULT 0,
    lease_owner TEXT,lease_until BIGINT
);
CREATE TABLE agent_platform_catalog (
    source_key TEXT NOT NULL REFERENCES agent_platform_catalog_source(source_key),provisioning_id TEXT NOT NULL,
    payload TEXT NOT NULL CHECK(json_valid(payload)),observed_at BIGINT NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    PRIMARY KEY(source_key,provisioning_id)
);
