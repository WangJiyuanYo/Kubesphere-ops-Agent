CREATE TABLE agent_chat_inspection (
    turn_id uuid PRIMARY KEY REFERENCES agent_chat_turn(id) ON DELETE CASCADE,
    payload jsonb NOT NULL
);
