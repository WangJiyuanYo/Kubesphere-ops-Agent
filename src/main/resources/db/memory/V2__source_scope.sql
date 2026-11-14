ALTER TABLE agent_memory ADD COLUMN scope_key TEXT NOT NULL DEFAULT 'UNBOUND';
UPDATE agent_memory SET scope_key='DEMO' WHERE environment='DEMO';
CREATE INDEX agent_memory_source_scope ON agent_memory(scope_key,status,updated_at);
