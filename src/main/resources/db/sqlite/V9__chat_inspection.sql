CREATE TABLE agent_chat_inspection (
    turn_id TEXT PRIMARY KEY REFERENCES agent_chat_turn(id) ON DELETE CASCADE,
    payload TEXT NOT NULL
);
