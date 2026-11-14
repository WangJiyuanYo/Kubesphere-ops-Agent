CREATE TABLE agent_case_recovery (
    discovery_id TEXT PRIMARY KEY NOT NULL REFERENCES agent_discovered_task(id),
    run_id TEXT NOT NULL REFERENCES agent_run(id),
    selected_by TEXT NOT NULL, created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE INDEX agent_case_recovery_run ON agent_case_recovery(run_id);
