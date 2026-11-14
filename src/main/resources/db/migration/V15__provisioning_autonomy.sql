CREATE TABLE agent_provisioning_source (
 platform_id TEXT PRIMARY KEY, cursor_value TEXT NOT NULL DEFAULT '', warning TEXT NOT NULL DEFAULT '',
 checked_at BIGINT NOT NULL DEFAULT 0, lease_owner UUID, lease_until BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE agent_provisioning_watch (
 id UUID PRIMARY KEY, platform_id TEXT NOT NULL, workspace_id TEXT NOT NULL, site_id TEXT NOT NULL,
 provisioning_id TEXT NOT NULL, deployment_version TEXT NOT NULL, sequence BIGINT NOT NULL,
 stage JSONB NOT NULL, revision BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL,
 phase_started_at BIGINT NOT NULL, progress_at BIGINT NOT NULL, observed_at BIGINT NOT NULL,
 authorized_at BIGINT NOT NULL DEFAULT 0, authorization_hash TEXT NOT NULL DEFAULT '',
 warning TEXT NOT NULL DEFAULT '', paused BOOLEAN NOT NULL DEFAULT false, run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
 UNIQUE(platform_id, provisioning_id, deployment_version)
);
CREATE TABLE agent_provisioning_stage_event (
 platform_id TEXT NOT NULL, event_id TEXT NOT NULL, watch_id UUID NOT NULL REFERENCES agent_provisioning_watch(id),
 sequence BIGINT NOT NULL, payload_hash TEXT NOT NULL, payload JSONB NOT NULL, status TEXT NOT NULL, received_at BIGINT NOT NULL,
 PRIMARY KEY(platform_id,event_id)
);
CREATE INDEX agent_provisioning_timeline ON agent_provisioning_stage_event(watch_id,sequence);
CREATE TABLE agent_autonomy_policy (
 id UUID PRIMARY KEY, platform_id TEXT NOT NULL, revision BIGINT NOT NULL, payload JSONB NOT NULL, cluster_api TEXT NOT NULL,
 connection_revision BIGINT NOT NULL, actor TEXT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE agent_autonomy_audit (
 id UUID PRIMARY KEY, policy_id UUID NOT NULL, payload JSONB NOT NULL, actor TEXT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE agent_diagnosis_trigger (
 id UUID PRIMARY KEY, watch_id UUID NOT NULL REFERENCES agent_provisioning_watch(id),
 policy_id UUID NOT NULL REFERENCES agent_autonomy_policy(id), policy_revision BIGINT NOT NULL,
 fingerprint TEXT NOT NULL, discovery_id UUID REFERENCES agent_discovered_task(id) ON DELETE CASCADE, request_id UUID NOT NULL,
 state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', created_at BIGINT NOT NULL,
 UNIQUE(watch_id,fingerprint)
);
CREATE TABLE agent_autonomy_run (
 run_id UUID PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE, watch_id UUID NOT NULL REFERENCES agent_provisioning_watch(id),
 policy_id UUID NOT NULL REFERENCES agent_autonomy_policy(id), policy_revision BIGINT NOT NULL, approved_plan_id TEXT
);
CREATE TABLE agent_autonomy_slot (
 cluster_api TEXT PRIMARY KEY, run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE
);
CREATE TABLE agent_release_budget (
 platform_id TEXT NOT NULL, provisioning_id TEXT NOT NULL, deployment_version TEXT NOT NULL, run_id UUID REFERENCES agent_run(id) ON DELETE CASCADE,
 PRIMARY KEY(platform_id,provisioning_id,deployment_version)
);
CREATE TABLE agent_recovery_outcome (
 run_id UUID PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE, outcome TEXT NOT NULL, payload JSONB NOT NULL, recorded_at BIGINT NOT NULL
);
CREATE TABLE agent_run_diagnosis (
 run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE, fingerprint TEXT NOT NULL, state TEXT NOT NULL,
 plan JSONB, created_at BIGINT NOT NULL, PRIMARY KEY(run_id,fingerprint)
);
CREATE TABLE agent_provisioning_watch_audit (
 id UUID PRIMARY KEY, watch_id UUID NOT NULL REFERENCES agent_provisioning_watch(id) ON DELETE CASCADE,
 payload JSONB NOT NULL, actor TEXT NOT NULL, created_at BIGINT NOT NULL
);
