CREATE TABLE agent_command_execution (
    id TEXT PRIMARY KEY NOT NULL,batch_id TEXT NOT NULL,run_id TEXT NOT NULL REFERENCES agent_run(id),
    ordinal INTEGER NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL CHECK(json_valid(payload)),
    UNIQUE(batch_id,ordinal)
);
CREATE INDEX agent_command_run ON agent_command_execution(run_id,batch_id,ordinal);
