CREATE TABLE IF NOT EXISTS forward (
    id VARCHAR(64) PRIMARY KEY,
    direction VARCHAR(32) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    listen_port INTEGER NOT NULL,
    target_host VARCHAR(255),
    target_port INTEGER,
    enabled BOOLEAN NOT NULL
);