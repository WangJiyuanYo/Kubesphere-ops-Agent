CREATE TABLE agent_learning_outbox (
    source_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL CHECK(json_valid(payload)),
    attempts INTEGER NOT NULL DEFAULT 0, due_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    lease_owner TEXT, lease_until INTEGER, processed_at INTEGER, error_code TEXT NOT NULL DEFAULT ''
);
CREATE INDEX agent_learning_pending ON agent_learning_outbox(due_at) WHERE processed_at IS NULL;
