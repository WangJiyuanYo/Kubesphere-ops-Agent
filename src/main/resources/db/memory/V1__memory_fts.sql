CREATE TABLE agent_memory (
    rowid INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,source_key TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,problem TEXT NOT NULL,cause TEXT NOT NULL,solution TEXT NOT NULL,verification TEXT NOT NULL,
    tags TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('DRAFT','VERIFIED','ARCHIVED')),
    environment TEXT NOT NULL,source_type TEXT NOT NULL,source_ref TEXT NOT NULL,
    revision INTEGER NOT NULL,title_terms TEXT NOT NULL,body_terms TEXT NOT NULL,
    created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL
);
CREATE VIRTUAL TABLE agent_memory_fts USING fts5(title_terms,body_terms,content='agent_memory',content_rowid='rowid',tokenize='unicode61');
CREATE TRIGGER agent_memory_ai AFTER INSERT ON agent_memory BEGIN
    INSERT INTO agent_memory_fts(rowid,title_terms,body_terms) VALUES(new.rowid,new.title_terms,new.body_terms);
END;
CREATE TRIGGER agent_memory_ad AFTER DELETE ON agent_memory BEGIN
    INSERT INTO agent_memory_fts(agent_memory_fts,rowid,title_terms,body_terms) VALUES('delete',old.rowid,old.title_terms,old.body_terms);
END;
CREATE TRIGGER agent_memory_au AFTER UPDATE ON agent_memory BEGIN
    INSERT INTO agent_memory_fts(agent_memory_fts,rowid,title_terms,body_terms) VALUES('delete',old.rowid,old.title_terms,old.body_terms);
    INSERT INTO agent_memory_fts(rowid,title_terms,body_terms) VALUES(new.rowid,new.title_terms,new.body_terms);
END;
CREATE INDEX agent_memory_scope ON agent_memory(environment,status,updated_at);
