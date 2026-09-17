CREATE TABLE IF NOT EXISTS forward (
    id TEXT PRIMARY KEY,
    direction TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    listen_port INTEGER NOT NULL,
    target_host TEXT,
    target_port INTEGER,
    enabled INTEGER NOT NULL
);