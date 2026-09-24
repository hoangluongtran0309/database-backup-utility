CREATE TABLE notification_channels (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('TELEGRAM', 'SLACK', 'EMAIL', 'WEBHOOK')),
    bot_token_enc TEXT,
    chat_id VARCHAR(100),
    webhook_url_enc TEXT,
    email_to VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_connection_successful BOOLEAN,
    last_connection_message VARCHAR(500),
    last_connection_checked_at TIMESTAMPTZ,
    CONSTRAINT chk_notification_channel_configuration CHECK (
        (type = 'TELEGRAM' AND bot_token_enc IS NOT NULL AND chat_id IS NOT NULL
            AND webhook_url_enc IS NULL AND email_to IS NULL)
        OR (type IN ('SLACK', 'WEBHOOK') AND bot_token_enc IS NULL AND chat_id IS NULL
            AND webhook_url_enc IS NOT NULL AND email_to IS NULL)
        OR (type = 'EMAIL' AND bot_token_enc IS NULL AND chat_id IS NULL
            AND webhook_url_enc IS NULL AND email_to IS NOT NULL)
    ),
    CONSTRAINT chk_notification_channel_connection_check CHECK (
        (last_connection_successful IS NULL AND last_connection_checked_at IS NULL)
        OR (last_connection_successful IS NOT NULL AND last_connection_checked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_notification_channels_name_ci ON notification_channels (LOWER(BTRIM(name)));

CREATE TABLE database_target_notification_channels (
    target_id UUID NOT NULL REFERENCES database_targets(id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES notification_channels(id) ON DELETE RESTRICT,
    events TEXT NOT NULL,
    PRIMARY KEY (target_id, channel_id),
    CONSTRAINT chk_target_notification_events_not_blank CHECK (BTRIM(events) <> '')
);

CREATE INDEX idx_target_notification_channels_channel_id
    ON database_target_notification_channels(channel_id);
