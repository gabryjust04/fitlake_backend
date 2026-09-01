CREATE TABLE user_account
(
    user_id      UUID PRIMARY KEY,
    email        VARCHAR(320),
    display_name VARCHAR(255),
    timezone     VARCHAR(63)  NOT NULL DEFAULT 'Europe/Rome',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_account_email UNIQUE (email)
);

CREATE TABLE user_channel_identity
(
    channel_identity_id UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES user_account (user_id),
    channel             VARCHAR(50)  NOT NULL,
    external_user_id    VARCHAR(255) NOT NULL,
    external_chat_id    VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_channel_identity_channel_external_user
        UNIQUE (channel, external_user_id)
);

CREATE TABLE daily_day
(
    day_id       UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES user_account (user_id),
    day_date     DATE        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    opened_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    reopened_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_daily_day_user_date UNIQUE (user_id, day_date),
    CONSTRAINT ck_daily_day_status CHECK (status IN ('OPEN', 'CONFIRMED', 'REOPENED'))
);

CREATE TABLE daily_inbox_event
(
    inbox_event_id     UUID PRIMARY KEY,
    user_id            UUID         NOT NULL REFERENCES user_account (user_id),
    day_id             UUID REFERENCES daily_day (day_id),
    channel            VARCHAR(50)  NOT NULL,
    source_type        VARCHAR(30)  NOT NULL,
    source_message_id  VARCHAR(255),
    source_callback_id VARCHAR(255),
    raw_text           TEXT,
    transcript_text    TEXT,
    normalized_text    TEXT,
    raw_payload        JSONB,
    processing_status  VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    error_code         VARCHAR(100),
    error_message      TEXT,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_daily_inbox_event_source_type CHECK (
        source_type IN (
            'TEXT_MESSAGE',
            'VOICE_MESSAGE',
            'CALLBACK',
            'MOBILE_AI_INPUT',
            'MOBILE_UI_ACTION'
        )
    ),
    CONSTRAINT ck_daily_inbox_event_processing_status CHECK (
        processing_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED')
    )
);

CREATE INDEX idx_daily_inbox_event_user_received_at
    ON daily_inbox_event (user_id, received_at DESC);

CREATE UNIQUE INDEX uq_daily_inbox_event_channel_source_message
    ON daily_inbox_event (channel, source_message_id)
    WHERE source_message_id IS NOT NULL;

CREATE TABLE daily_capture
(
    capture_id      UUID PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES user_account (user_id),
    day_id          UUID        NOT NULL REFERENCES daily_day (day_id),
    source_event_id UUID REFERENCES daily_inbox_event (inbox_event_id),
    capture_type    VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    payload         JSONB       NOT NULL,
    confidence      NUMERIC,
    created_by      VARCHAR(20) NOT NULL,
    updated_by      VARCHAR(20),
    accepted_at     TIMESTAMPTZ,
    rejected_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    expired_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_daily_capture_type CHECK (capture_type IN ('FOOD', 'DAILY_FIELDS', 'MIXED', 'NOTE')),
    CONSTRAINT ck_daily_capture_status CHECK (
        status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'SOFT_DELETED', 'EXPIRED')
    ),
    CONSTRAINT ck_daily_capture_created_by CHECK (created_by IN ('AI', 'USER_UI', 'SYSTEM')),
    CONSTRAINT ck_daily_capture_updated_by CHECK (
        updated_by IS NULL OR updated_by IN ('USER_UI', 'SYSTEM', 'AI')
    ),
    CONSTRAINT ck_daily_capture_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_daily_capture_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0 AND 1
    )
);

CREATE INDEX idx_daily_capture_day_status
    ON daily_capture (day_id, status);

CREATE INDEX idx_daily_capture_user_day
    ON daily_capture (user_id, day_id);

CREATE INDEX idx_daily_capture_created_at
    ON daily_capture (created_at DESC);

CREATE INDEX idx_daily_capture_payload_gin
    ON daily_capture USING GIN (payload);

CREATE TABLE ai_interpretation_log
(
    ai_log_id        UUID PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES user_account (user_id),
    inbox_event_id   UUID REFERENCES daily_inbox_event (inbox_event_id),
    capture_id       UUID REFERENCES daily_capture (capture_id),
    provider         VARCHAR(100) NOT NULL,
    model            VARCHAR(255) NOT NULL,
    prompt_version   VARCHAR(100) NOT NULL,
    input_text       TEXT         NOT NULL,
    context_snapshot JSONB,
    raw_response     JSONB,
    parsed_output    JSONB,
    status           VARCHAR(30)  NOT NULL,
    confidence       NUMERIC,
    error_code       VARCHAR(100),
    error_message    TEXT,
    latency_ms       INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_interpretation_log_status CHECK (
        status IN ('SUCCESS', 'FAILED', 'INVALID_OUTPUT', 'NEEDS_CLARIFICATION')
    ),
    CONSTRAINT ck_ai_interpretation_log_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_ai_interpretation_log_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_ai_interpretation_log_inbox_event
    ON ai_interpretation_log (inbox_event_id);

CREATE INDEX idx_ai_interpretation_log_user_created_at
    ON ai_interpretation_log (user_id, created_at DESC);

CREATE TABLE daily_metrics
(
    day_id                     UUID PRIMARY KEY REFERENCES daily_day (day_id),
    user_id                    UUID        NOT NULL REFERENCES user_account (user_id),
    day_date                   DATE        NOT NULL,
    status                     VARCHAR(20) NOT NULL,
    body_weight_kg             NUMERIC,
    sleep_hours                NUMERIC,
    steps_count                INTEGER,
    hydration_liters           NUMERIC,
    caffeine_mg                INTEGER,
    mood_level                 SMALLINT,
    focus_level                SMALLINT,
    stress_level               SMALLINT,
    total_calories             INTEGER,
    protein_g                  NUMERIC,
    carbs_g                    NUMERIC,
    fat_g                      NUMERIC,
    food_log                   JSONB       NOT NULL DEFAULT '[]'::JSONB,
    daily_notes                TEXT,
    experimental_data          JSONB       NOT NULL DEFAULT '{}'::JSONB,
    generated_from_capture_ids JSONB       NOT NULL DEFAULT '[]'::JSONB,
    confirmed_at               TIMESTAMPTZ,
    recalculated_at            TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_daily_metrics_user_date UNIQUE (user_id, day_date),
    CONSTRAINT ck_daily_metrics_food_log_array CHECK (jsonb_typeof(food_log) = 'array'),
    CONSTRAINT ck_daily_metrics_experimental_data_object CHECK (
        jsonb_typeof(experimental_data) = 'object'
    ),
    CONSTRAINT ck_daily_metrics_capture_ids_array CHECK (
        jsonb_typeof(generated_from_capture_ids) = 'array'
    )
);

CREATE INDEX idx_daily_metrics_user_day_date
    ON daily_metrics (user_id, day_date DESC);

CREATE INDEX idx_daily_metrics_food_log_gin
    ON daily_metrics USING GIN (food_log);
