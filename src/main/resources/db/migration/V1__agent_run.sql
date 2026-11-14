CREATE TABLE agent_run (
    id uuid PRIMARY KEY,
    request_key varchar(80) NOT NULL UNIQUE,
    provisioning_id varchar(19) NOT NULL,
    state varchar(32) NOT NULL,
    payload jsonb NOT NULL,
    due_at timestamptz NOT NULL,
    lease_owner varchar(40),
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_run_due ON agent_run(due_at) WHERE state IN
    ('MONITORING','DIAGNOSING','RETRY_WAIT','EXECUTING','VERIFYING');
-- 同一平台任务同时只允许一个未结束的 Agent，避免重复续发 Operator 恢复预算。
CREATE UNIQUE INDEX agent_run_active_provisioning ON agent_run(provisioning_id)
    WHERE state IN ('MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING');
