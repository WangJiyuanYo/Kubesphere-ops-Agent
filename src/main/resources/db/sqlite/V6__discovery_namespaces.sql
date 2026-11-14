ALTER TABLE agent_discovery_source ADD COLUMN namespaces TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(namespaces));
UPDATE agent_discovery_source SET namespaces=(SELECT json_array(json_extract(payload,'$.namespace'))
FROM agent_console_connection WHERE id=connection_id);
