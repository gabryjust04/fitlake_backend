ALTER TABLE daily_inbox_event
    ADD COLUMN replaces_capture_id UUID,
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN processing_attempt_id UUID,
    ADD CONSTRAINT fk_daily_inbox_event_replaces_capture
        FOREIGN KEY (replaces_capture_id) REFERENCES daily_capture (capture_id);

UPDATE daily_inbox_event
SET processing_started_at = received_at,
    processing_attempt_id = gen_random_uuid();

ALTER TABLE daily_inbox_event
    ALTER COLUMN processing_started_at SET NOT NULL,
    ALTER COLUMN processing_attempt_id SET NOT NULL;

DROP INDEX uq_daily_inbox_event_channel_source_message;

CREATE UNIQUE INDEX uq_daily_inbox_event_user_channel_source_message
    ON daily_inbox_event (user_id, channel, source_message_id)
    WHERE source_message_id IS NOT NULL;

CREATE INDEX idx_daily_inbox_event_replaces_capture
    ON daily_inbox_event (replaces_capture_id)
    WHERE replaces_capture_id IS NOT NULL;

CREATE INDEX idx_daily_inbox_event_day_received_at
    ON daily_inbox_event (day_id, received_at DESC)
    WHERE day_id IS NOT NULL;

CREATE UNIQUE INDEX uq_daily_capture_source_event
    ON daily_capture (source_event_id)
    WHERE source_event_id IS NOT NULL;

DROP INDEX idx_ai_interpretation_log_inbox_event;

CREATE UNIQUE INDEX uq_ai_interpretation_log_inbox_event
    ON ai_interpretation_log (inbox_event_id)
    WHERE inbox_event_id IS NOT NULL;

ALTER TABLE ai_interpretation_log
    DROP CONSTRAINT ck_ai_interpretation_log_status,
    ADD CONSTRAINT ck_ai_interpretation_log_status CHECK (
        status IN ('SUCCESS', 'FAILED', 'INVALID_OUTPUT', 'NEEDS_CLARIFICATION', 'NO_OP')
    );
