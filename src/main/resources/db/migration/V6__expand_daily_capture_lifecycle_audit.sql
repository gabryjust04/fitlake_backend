ALTER TABLE ai_interpretation_log
    DROP CONSTRAINT ck_ai_interpretation_log_status,
    ALTER COLUMN input_text DROP NOT NULL;

UPDATE ai_interpretation_log
SET status = 'INVALID_OUTPUT'
WHERE status = 'NEEDS_CLARIFICATION';

UPDATE ai_interpretation_log
SET status = 'NO_RELEVANT_DATA'
WHERE status = 'NO_OP';

ALTER TABLE ai_interpretation_log
    ADD CONSTRAINT ck_ai_interpretation_log_status CHECK (
        status IN ('SUCCESS', 'FAILED', 'INVALID_OUTPUT', 'NO_RELEVANT_DATA')
    );

ALTER TABLE daily_capture_audit
    DROP CONSTRAINT ck_daily_capture_audit_action,
    DROP CONSTRAINT ck_daily_capture_audit_payloads,
    DROP CONSTRAINT ck_daily_capture_audit_versions,
    ALTER COLUMN old_payload DROP NOT NULL,
    ALTER COLUMN new_payload DROP NOT NULL,
    ALTER COLUMN old_version DROP NOT NULL,
    ALTER COLUMN new_version DROP NOT NULL,
    ADD COLUMN old_status VARCHAR(20),
    ADD COLUMN new_status VARCHAR(20),
    ADD COLUMN actor VARCHAR(20),
    ADD COLUMN reason_code VARCHAR(100),
    ADD COLUMN related_capture_id UUID;

UPDATE daily_capture_audit
SET actor = 'USER_UI'
WHERE actor IS NULL;

ALTER TABLE daily_capture_audit
    ALTER COLUMN actor SET NOT NULL,
    ADD CONSTRAINT fk_daily_capture_audit_related_capture_owner
        FOREIGN KEY (related_capture_id, user_id)
            REFERENCES daily_capture (capture_id, user_id),
    ADD CONSTRAINT ck_daily_capture_audit_action CHECK (
        action IN (
            'CREATE',
            'ACCEPT',
            'REJECT',
            'UI_EDIT',
            'SOFT_DELETE',
            'REPLACED_BY_REPROCESS'
        )
    ),
    ADD CONSTRAINT ck_daily_capture_audit_actor CHECK (
        actor IN ('AI', 'USER_UI', 'SYSTEM')
    ),
    ADD CONSTRAINT ck_daily_capture_audit_statuses CHECK (
        (old_status IS NULL OR old_status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'SOFT_DELETED', 'EXPIRED')) AND
        (new_status IS NULL OR new_status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'SOFT_DELETED', 'EXPIRED'))
    ),
    ADD CONSTRAINT ck_daily_capture_audit_payloads CHECK (
        (old_payload IS NULL OR jsonb_typeof(old_payload) = 'object') AND
        (new_payload IS NULL OR jsonb_typeof(new_payload) = 'object')
    ),
    ADD CONSTRAINT ck_daily_capture_audit_versions CHECK (
        (old_version IS NULL OR old_version >= 0) AND
        (new_version IS NULL OR new_version >= 0) AND
        (old_version IS NULL OR new_version IS NULL OR new_version > old_version)
    ),
    ADD CONSTRAINT ck_daily_capture_audit_reason_code CHECK (
        reason_code IS NULL OR btrim(reason_code) <> ''
    ),
    ADD CONSTRAINT ck_daily_capture_audit_related_capture CHECK (
        related_capture_id IS NULL OR related_capture_id <> capture_id
    ),
    ADD CONSTRAINT ck_daily_capture_audit_action_shape CHECK (
        (
            action = 'CREATE' AND
            actor IN ('AI', 'USER_UI') AND
            old_payload IS NULL AND new_payload IS NOT NULL AND
            old_status IS NULL AND new_status IS NOT NULL AND new_status = 'OPEN' AND
            old_version IS NULL AND new_version IS NOT NULL AND new_version = 0 AND
            reason_code IS NULL AND related_capture_id IS NULL
        ) OR (
            action = 'ACCEPT' AND actor = 'USER_UI' AND
            old_payload IS NULL AND new_payload IS NULL AND
            old_status IS NOT NULL AND new_status IS NOT NULL AND
            old_status = 'OPEN' AND new_status = 'ACCEPTED' AND
            old_version IS NOT NULL AND new_version IS NOT NULL AND
            reason_code IS NULL AND related_capture_id IS NULL
        ) OR (
            action = 'REJECT' AND actor = 'USER_UI' AND
            old_payload IS NULL AND new_payload IS NULL AND
            old_status IS NOT NULL AND new_status IS NOT NULL AND
            old_status = 'OPEN' AND new_status = 'REJECTED' AND
            old_version IS NOT NULL AND new_version IS NOT NULL AND
            related_capture_id IS NULL
        ) OR (
            action = 'UI_EDIT' AND actor = 'USER_UI' AND
            old_payload IS NOT NULL AND new_payload IS NOT NULL AND
            (
                (old_status IS NULL AND new_status IS NULL) OR
                (
                    old_status IS NOT NULL AND new_status IS NOT NULL AND
                    old_status IN ('OPEN', 'ACCEPTED') AND new_status = old_status
                )
            ) AND
            old_version IS NOT NULL AND new_version IS NOT NULL AND
            reason_code IS NULL AND related_capture_id IS NULL
        ) OR (
            action = 'SOFT_DELETE' AND actor = 'USER_UI' AND
            old_payload IS NULL AND new_payload IS NULL AND
            old_status IS NOT NULL AND new_status IS NOT NULL AND
            old_status IN ('OPEN', 'ACCEPTED', 'REJECTED') AND new_status = 'SOFT_DELETED' AND
            old_version IS NOT NULL AND new_version IS NOT NULL AND
            related_capture_id IS NULL
        ) OR (
            action = 'REPLACED_BY_REPROCESS' AND actor = 'SYSTEM' AND
            old_payload IS NULL AND new_payload IS NULL AND
            old_status IS NOT NULL AND new_status IS NOT NULL AND
            old_status = 'OPEN' AND new_status = 'REJECTED' AND
            old_version IS NOT NULL AND new_version IS NOT NULL AND
            reason_code IS NOT NULL AND reason_code = 'REPLACED_BY_REPROCESS' AND
            related_capture_id IS NOT NULL
        )
    );

CREATE INDEX idx_daily_capture_audit_related_capture
    ON daily_capture_audit (related_capture_id)
    WHERE related_capture_id IS NOT NULL;
