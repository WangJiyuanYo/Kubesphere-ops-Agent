DROP INDEX agent_run_due;
DROP INDEX agent_run_active_provisioning;
CREATE INDEX agent_run_due ON agent_run(due_at) WHERE state IN
    ('DISCOVERING','MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING');
CREATE UNIQUE INDEX agent_run_active_provisioning ON agent_run(provisioning_id) WHERE state IN
    ('DISCOVERING','MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING');
CREATE UNIQUE INDEX agent_run_version ON agent_run(provisioning_id, (payload->>'deploymentVersion'))
    WHERE payload->>'deploymentVersion' IS NOT NULL;

CREATE TABLE agent_deployment_event (
    event_id varchar(80) PRIMARY KEY,
    provisioning_id varchar(19) NOT NULL,
    deployment_version varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    run_id uuid REFERENCES agent_run(id),
    due_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(provisioning_id, deployment_version)
);
CREATE INDEX agent_event_pending ON agent_deployment_event(due_at) WHERE run_id IS NULL;

CREATE TABLE agent_audit (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_run(id),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_audit_run ON agent_audit(run_id, created_at);

CREATE TABLE agent_notification (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_run(id),
    payload jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    due_at timestamptz NOT NULL DEFAULT now(),
    lease_owner uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_notification_due ON agent_notification(due_at) WHERE state='PENDING';
