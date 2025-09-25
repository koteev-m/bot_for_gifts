-- rng_seed_commits: дневной commit (hash), опциональный reveal
CREATE TABLE IF NOT EXISTS rng_seed_commits (
    day_utc DATE PRIMARY KEY,
    server_seed_hash TEXT NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revealed_at TIMESTAMPTZ NULL,
    server_seed TEXT NULL,
    CONSTRAINT chk_hash_len CHECK (char_length(server_seed_hash) = 64),
    CONSTRAINT chk_hash_hex CHECK (server_seed_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_seed_hex_or_null CHECK (server_seed IS NULL OR server_seed ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_rng_seed_commits_hash UNIQUE (server_seed_hash)
);

CREATE INDEX IF NOT EXISTS idx_rng_seed_commits_committed_at
    ON rng_seed_commits (committed_at DESC);

-- rng_draws: идемпотентный журнал розыгрышей
CREATE TABLE IF NOT EXISTS rng_draws (
    id BIGSERIAL PRIMARY KEY,
    case_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    nonce TEXT NOT NULL,
    server_seed_hash TEXT NOT NULL,
    roll_hex TEXT NOT NULL,
    ppm INT NOT NULL,
    result_item_id TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_draw_idempotency UNIQUE (case_id, user_id, nonce),
    CONSTRAINT chk_ppm_range CHECK (ppm BETWEEN 0 AND 999999),
    CONSTRAINT chk_roll_hex CHECK (char_length(roll_hex) = 64 AND roll_hex ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_draw_commit_hash FOREIGN KEY (server_seed_hash)
        REFERENCES rng_seed_commits(server_seed_hash) DEFERRABLE INITIALLY DEFERRED
);

-- Индексы для аналитики и быстрых выборок
CREATE INDEX IF NOT EXISTS idx_rng_draws_user_created_at
    ON rng_draws (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rng_draws_case_created_at
    ON rng_draws (case_id, created_at DESC);
