-- SQLite 时间统一为 UTC epoch 毫秒；JSON 使用带校验的 TEXT。
CREATE TABLE agent_run (
    id TEXT PRIMARY KEY NOT NULL, request_key TEXT NOT NULL UNIQUE,
    provisioning_id TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL CHECK(json_valid(payload)),
    due_at INTEGER NOT NULL,lease_owner TEXT,lease_until INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE INDEX agent_run_due ON agent_run(due_at) WHERE state IN
    ('DISCOVERING','MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING');
CREATE UNIQUE INDEX agent_run_active_provisioning ON agent_run(provisioning_id) WHERE state IN
    ('DISCOVERING','MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING');
CREATE UNIQUE INDEX agent_run_version ON agent_run(provisioning_id,(payload->>'deploymentVersion'))
    WHERE payload->>'deploymentVersion' IS NOT NULL;
CREATE TABLE agent_deployment_event (
    event_id TEXT PRIMARY KEY NOT NULL,provisioning_id TEXT NOT NULL,deployment_version TEXT NOT NULL,
    payload TEXT NOT NULL CHECK(json_valid(payload)),run_id TEXT REFERENCES agent_run(id),error_code TEXT,
    due_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    UNIQUE(provisioning_id,deployment_version)
);
CREATE INDEX agent_event_pending ON agent_deployment_event(due_at) WHERE run_id IS NULL AND error_code IS NULL;
CREATE TABLE agent_audit (
    id TEXT PRIMARY KEY NOT NULL,run_id TEXT NOT NULL REFERENCES agent_run(id),payload TEXT NOT NULL CHECK(json_valid(payload)),
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE INDEX agent_audit_run ON agent_audit(run_id,created_at);
CREATE TABLE agent_notification (
    id TEXT PRIMARY KEY NOT NULL,run_id TEXT NOT NULL REFERENCES agent_run(id),payload TEXT NOT NULL CHECK(json_valid(payload)),
    state TEXT NOT NULL DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,
    due_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),lease_owner TEXT,lease_until INTEGER,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE INDEX agent_notification_due ON agent_notification(due_at) WHERE state='PENDING';
CREATE TABLE agent_console_llm (
    id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,payload TEXT NOT NULL CHECK(json_valid(payload)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE TABLE agent_console_connection (
    id TEXT PRIMARY KEY NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL CHECK(json_valid(payload)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE TABLE agent_chat_session (
    id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL CHECK(length(title)<=80),
    connection_id TEXT REFERENCES agent_console_connection(id) ON DELETE SET NULL,
    run_id TEXT REFERENCES agent_run(id) ON DELETE SET NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    updated_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE TABLE agent_chat_turn (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,
    session_id TEXT NOT NULL REFERENCES agent_chat_session(id) ON DELETE CASCADE,
    user_content TEXT NOT NULL CHECK(length(user_content)<=4000),assistant_content TEXT CHECK(length(assistant_content)<=8000),
    include_cluster INTEGER NOT NULL CHECK(include_cluster IN (0,1)),state TEXT NOT NULL,
    source TEXT CHECK(length(source)<=100),memory_refs TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(memory_refs)),lease_owner TEXT NOT NULL,lease_until INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE UNIQUE INDEX agent_chat_one_running ON agent_chat_turn(session_id) WHERE state='RUNNING';
CREATE INDEX agent_chat_history ON agent_chat_turn(session_id,sequence DESC);
CREATE TABLE agent_memory_outbox (
    run_id TEXT PRIMARY KEY NOT NULL REFERENCES agent_run(id),payload TEXT NOT NULL CHECK(json_valid(payload)),
    environment TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,
    due_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER)),
    processed_at INTEGER,created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec')*1000 AS INTEGER))
);
CREATE INDEX agent_memory_pending ON agent_memory_outbox(created_at) WHERE processed_at IS NULL;
