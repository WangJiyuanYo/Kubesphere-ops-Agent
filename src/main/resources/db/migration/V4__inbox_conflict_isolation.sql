ALTER TABLE agent_deployment_event ADD COLUMN error_code varchar(80);
DROP INDEX agent_event_pending;
CREATE INDEX agent_event_pending ON agent_deployment_event(due_at) WHERE run_id IS NULL AND error_code IS NULL;
