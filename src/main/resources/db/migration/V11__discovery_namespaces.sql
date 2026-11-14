ALTER TABLE agent_discovery_source ADD COLUMN namespaces jsonb NOT NULL DEFAULT '[]'::jsonb;
UPDATE agent_discovery_source s SET namespaces=jsonb_build_array(c.payload->>'namespace')
FROM agent_console_connection c WHERE s.connection_id=c.id;
