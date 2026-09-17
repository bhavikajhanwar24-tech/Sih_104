CREATE TABLE IF NOT EXISTS caller_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    caller_id VARCHAR(255) NOT NULL,
    caller_name VARCHAR(255) NOT NULL,
    claimed_role VARCHAR(255) NOT NULL,
    consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    voice_fingerprint VARCHAR(512)
);

CREATE TABLE IF NOT EXISTS audit_blocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    details TEXT,
    timestamp TIMESTAMP NOT NULL,
    previous_hash TEXT NOT NULL,
    current_hash TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS forensic_dossiers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    dossier_type VARCHAR(255) NOT NULL,
    summary TEXT,
    generated_at TIMESTAMP NOT NULL
);
