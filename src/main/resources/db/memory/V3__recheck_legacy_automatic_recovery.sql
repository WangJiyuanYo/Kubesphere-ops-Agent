-- 旧自动归档未强制要求动作凭据。人工编辑过的记录保留，未经人工确认的旧自动结论重新待核验。
UPDATE agent_memory
SET status='DRAFT',revision=revision+1,
    verification='历史自动归档没有足够的动作受理证明，需重新核验。' || char(10) || verification,
    updated_at=CAST(unixepoch('subsec')*1000 AS INTEGER)
WHERE source_type='RUN' AND status='VERIFIED' AND revision=1;
