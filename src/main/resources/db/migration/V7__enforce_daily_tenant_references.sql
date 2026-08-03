-- Preserve tenant ownership at the database boundary as well as in application queries.
-- Existing single-column foreign keys are intentionally retained for a safe additive rollout.

ALTER TABLE daily_day
    ADD CONSTRAINT uq_daily_day_id_user
        UNIQUE (day_id, user_id),
    ADD CONSTRAINT uq_daily_day_id_user_date
        UNIQUE (day_id, user_id, day_date);

ALTER TABLE daily_inbox_event
    ADD CONSTRAINT uq_daily_inbox_event_id_user
        UNIQUE (inbox_event_id, user_id);

ALTER TABLE daily_inbox_event
    ADD CONSTRAINT fk_daily_inbox_event_day_owner
        FOREIGN KEY (day_id, user_id)
            REFERENCES daily_day (day_id, user_id) NOT VALID,
    ADD CONSTRAINT fk_daily_inbox_event_replaced_capture_owner
        FOREIGN KEY (replaces_capture_id, user_id)
            REFERENCES daily_capture (capture_id, user_id) NOT VALID;

ALTER TABLE daily_capture
    ADD CONSTRAINT fk_daily_capture_day_owner
        FOREIGN KEY (day_id, user_id)
            REFERENCES daily_day (day_id, user_id) NOT VALID,
    ADD CONSTRAINT fk_daily_capture_source_event_owner
        FOREIGN KEY (source_event_id, user_id)
            REFERENCES daily_inbox_event (inbox_event_id, user_id) NOT VALID;

ALTER TABLE ai_interpretation_log
    ADD CONSTRAINT fk_ai_interpretation_log_inbox_owner
        FOREIGN KEY (inbox_event_id, user_id)
            REFERENCES daily_inbox_event (inbox_event_id, user_id) NOT VALID,
    ADD CONSTRAINT fk_ai_interpretation_log_capture_owner
        FOREIGN KEY (capture_id, user_id)
            REFERENCES daily_capture (capture_id, user_id) NOT VALID;

ALTER TABLE daily_metrics
    ADD CONSTRAINT fk_daily_metrics_day_owner_date
        FOREIGN KEY (day_id, user_id, day_date)
            REFERENCES daily_day (day_id, user_id, day_date) NOT VALID;

ALTER TABLE daily_inbox_event
    VALIDATE CONSTRAINT fk_daily_inbox_event_day_owner,
    VALIDATE CONSTRAINT fk_daily_inbox_event_replaced_capture_owner;

ALTER TABLE daily_capture
    VALIDATE CONSTRAINT fk_daily_capture_day_owner,
    VALIDATE CONSTRAINT fk_daily_capture_source_event_owner;

ALTER TABLE ai_interpretation_log
    VALIDATE CONSTRAINT fk_ai_interpretation_log_inbox_owner,
    VALIDATE CONSTRAINT fk_ai_interpretation_log_capture_owner;

ALTER TABLE daily_metrics
    VALIDATE CONSTRAINT fk_daily_metrics_day_owner_date;

CREATE INDEX idx_ai_interpretation_log_capture_owner
    ON ai_interpretation_log (capture_id, user_id)
    WHERE capture_id IS NOT NULL;
