CREATE TABLE agent_command_execution (
    id UUID PRIMARY KEY,batch_id UUID NOT NULL,run_id UUID NOT NULL REFERENCES agent_run(id),
    ordinal INTEGER NOT NULL,state TEXT NOT NULL,payload JSONB NOT NULL,
    UNIQUE(batch_id,ordinal)
);
CREATE INDEX agent_command_run ON agent_command_execution(run_id,batch_id,ordinal);
