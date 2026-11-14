CREATE TABLE agent_case_recovery (
    discovery_id uuid PRIMARY KEY REFERENCES agent_discovered_task(id),
    run_id uuid NOT NULL REFERENCES agent_run(id),
    selected_by varchar(100) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_case_recovery_run ON agent_case_recovery(run_id);
