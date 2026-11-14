CREATE TABLE agent_discovery_source (
    connection_id uuid PRIMARY KEY REFERENCES agent_console_connection(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT false, revision bigint NOT NULL DEFAULT 0,
    lease_owner uuid, lease_until timestamptz, due_at timestamptz NOT NULL DEFAULT now(),
    checked_at timestamptz, error text NOT NULL DEFAULT ''
);
CREATE TABLE agent_discovered_task (
    id uuid PRIMARY KEY, connection_id uuid REFERENCES agent_console_connection(id) ON DELETE SET NULL,
    origin_hash varchar(64) NOT NULL, connection_name varchar(80) NOT NULL,
    resource_uid varchar(100) NOT NULL, generation bigint NOT NULL, observation jsonb NOT NULL,
    presence varchar(16) NOT NULL DEFAULT 'PRESENT', state varchar(16) NOT NULL DEFAULT 'PENDING',
    revision bigint NOT NULL DEFAULT 0, request_id uuid, lease_owner uuid, lease_until timestamptz,
    selected_by varchar(200), selected_at timestamptz, diagnosis jsonb, error text NOT NULL DEFAULT '',
    discovered_at timestamptz NOT NULL DEFAULT now(), observed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(connection_id,origin_hash,resource_uid,generation)
);
CREATE INDEX agent_discovered_recent ON agent_discovered_task(discovered_at DESC,id);
CREATE INDEX agent_discovered_queue ON agent_discovered_task(selected_at) WHERE state IN ('QUEUED','RUNNING');
ALTER TABLE agent_chat_session ADD COLUMN discovery_id uuid REFERENCES agent_discovered_task(id) ON DELETE SET NULL;
