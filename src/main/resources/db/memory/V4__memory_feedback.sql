CREATE TABLE agent_memory_feedback (
    request_id TEXT PRIMARY KEY,
    memory_id TEXT NOT NULL REFERENCES agent_memory(id),
    memory_revision INTEGER NOT NULL CHECK(memory_revision > 0),
    turn_id TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('HELPFUL','NOT_APPLICABLE','OUTDATED')),
    note TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(memory_id,turn_id)
);
CREATE INDEX agent_memory_feedback_turn ON agent_memory_feedback(turn_id,created_at);
