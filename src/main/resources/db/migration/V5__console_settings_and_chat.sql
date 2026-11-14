CREATE TABLE agent_console_llm (
    id integer PRIMARY KEY CHECK(id=1),
    revision bigint NOT NULL,
    payload jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_console_connection (
    id uuid PRIMARY KEY,
    revision bigint NOT NULL,
    payload jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_chat_session (
    id uuid PRIMARY KEY,
    title varchar(80) NOT NULL,
    connection_id uuid REFERENCES agent_console_connection(id) ON DELETE SET NULL,
    run_id uuid REFERENCES agent_run(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_chat_turn (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES agent_chat_session(id) ON DELETE CASCADE,
    user_content text NOT NULL CHECK(length(user_content)<=4000),
    assistant_content text CHECK(length(assistant_content)<=8000),
    include_cluster boolean NOT NULL,
    state varchar(16) NOT NULL,
    source varchar(100),
    lease_owner uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX agent_chat_one_running ON agent_chat_turn(session_id) WHERE state='RUNNING';
CREATE INDEX agent_chat_history ON agent_chat_turn(session_id,sequence DESC);
