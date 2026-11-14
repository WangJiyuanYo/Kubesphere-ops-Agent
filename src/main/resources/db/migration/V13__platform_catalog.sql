CREATE TABLE agent_platform_catalog_source (
    source_key TEXT PRIMARY KEY NOT NULL,warning TEXT NOT NULL DEFAULT '',checked_at BIGINT NOT NULL DEFAULT 0,
    lease_owner UUID,lease_until TIMESTAMPTZ
);
CREATE TABLE agent_platform_catalog (
    source_key TEXT NOT NULL REFERENCES agent_platform_catalog_source(source_key),provisioning_id TEXT NOT NULL,
    payload JSONB NOT NULL,observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(source_key,provisioning_id)
);
