ALTER TABLE user_account
    DROP CONSTRAINT uq_user_account_email;

CREATE TABLE user_auth_identity
(
    auth_identity_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL
        REFERENCES user_account (user_id),

    provider         VARCHAR(40)  NOT NULL,
    issuer           VARCHAR(255) NOT NULL,
    external_subject VARCHAR(255) NOT NULL,

    email_at_link_time VARCHAR(320),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_user_auth_identity_provider
        CHECK (provider IN ('FIREBASE')),

    CONSTRAINT uq_user_auth_identity_external
        UNIQUE (issuer, external_subject),

    CONSTRAINT uq_user_auth_identity_user_issuer
        UNIQUE (user_id, issuer)
);
