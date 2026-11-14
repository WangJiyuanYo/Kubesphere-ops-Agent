CREATE TABLE agent_memory_outbox (
    run_id uuid PRIMARY KEY REFERENCES agent_run(id),
    payload jsonb NOT NULL,
    environment varchar(16) NOT NULL,
    processed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    due_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_memory_pending ON agent_memory_outbox(created_at) WHERE processed_at IS NULL;
ALTER TABLE agent_chat_turn ADD COLUMN memory_refs jsonb NOT NULL DEFAULT '[]'::jsonb;
