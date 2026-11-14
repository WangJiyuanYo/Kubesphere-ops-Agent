CREATE TABLE agent_learning_outbox (
    source_key varchar(200) PRIMARY KEY, payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0, due_at timestamptz NOT NULL DEFAULT now(),
    lease_owner uuid, lease_until timestamptz, processed_at timestamptz,
    error_code varchar(40) NOT NULL DEFAULT ''
);
CREATE INDEX agent_learning_pending ON agent_learning_outbox(due_at) WHERE processed_at IS NULL;
