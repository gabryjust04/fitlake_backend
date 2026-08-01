ALTER TABLE daily_capture
    ADD CONSTRAINT uq_daily_capture_id_user UNIQUE (capture_id, user_id);

CREATE TABLE daily_capture_audit
(
    audit_id    UUID PRIMARY KEY,
    capture_id  UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    action      VARCHAR(30)  NOT NULL,
    old_payload JSONB        NOT NULL,
    new_payload JSONB        NOT NULL,
    old_version BIGINT       NOT NULL,
    new_version BIGINT       NOT NULL,
    request_id  VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT fk_daily_capture_audit_capture_owner
        FOREIGN KEY (capture_id, user_id)
            REFERENCES daily_capture (capture_id, user_id),
    CONSTRAINT ck_daily_capture_audit_action CHECK (action IN ('UI_EDIT')),
    CONSTRAINT ck_daily_capture_audit_payloads CHECK (
        jsonb_typeof(old_payload) = 'object' AND
        jsonb_typeof(new_payload) = 'object'
    ),
    CONSTRAINT ck_daily_capture_audit_versions CHECK (
        old_version >= 0 AND new_version > old_version
    ),
    CONSTRAINT ck_daily_capture_audit_request_id CHECK (
        request_id IS NULL OR btrim(request_id) <> ''
    ),
    CONSTRAINT uq_daily_capture_audit_capture_version UNIQUE (capture_id, new_version)
);

CREATE INDEX idx_daily_capture_audit_capture_created
    ON daily_capture_audit (capture_id, created_at DESC, audit_id);

CREATE INDEX idx_daily_capture_audit_user_created
    ON daily_capture_audit (user_id, created_at DESC, audit_id);

ALTER TABLE daily_metrics
    ALTER COLUMN total_calories TYPE NUMERIC(18, 6)
        USING total_calories::NUMERIC(18, 6);
