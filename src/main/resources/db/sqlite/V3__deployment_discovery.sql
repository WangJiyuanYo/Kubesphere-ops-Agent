CREATE TABLE agent_discovery_source (
    connection_id TEXT PRIMARY KEY NOT NULL REFERENCES agent_console_connection(id) ON DELETE CASCADE,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)), revision INTEGER NOT NULL DEFAULT 0,
    lease_owner TEXT, lease_until INTEGER, due_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    checked_at INTEGER, error TEXT NOT NULL DEFAULT ''
);
CREATE TABLE agent_discovered_task (
    id TEXT PRIMARY KEY NOT NULL, connection_id TEXT REFERENCES agent_console_connection(id) ON DELETE SET NULL,
    origin_hash TEXT NOT NULL, connection_name TEXT NOT NULL,
    resource_uid TEXT NOT NULL, generation INTEGER NOT NULL, observation TEXT NOT NULL CHECK(json_valid(observation)),
    presence TEXT NOT NULL DEFAULT 'PRESENT', state TEXT NOT NULL DEFAULT 'PENDING',
    revision INTEGER NOT NULL DEFAULT 0, request_id TEXT, lease_owner TEXT, lease_until INTEGER,
    selected_by TEXT, selected_at INTEGER, diagnosis TEXT CHECK(diagnosis IS NULL OR json_valid(diagnosis)), error TEXT NOT NULL DEFAULT '',
    discovered_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    observed_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    UNIQUE(connection_id,origin_hash,resource_uid,generation)
);
CREATE INDEX agent_discovered_recent ON agent_discovered_task(discovered_at DESC,id);
CREATE INDEX agent_discovered_queue ON agent_discovered_task(selected_at) WHERE state IN ('QUEUED','RUNNING');
ALTER TABLE agent_chat_session ADD COLUMN discovery_id TEXT REFERENCES agent_discovered_task(id) ON DELETE SET NULL;
