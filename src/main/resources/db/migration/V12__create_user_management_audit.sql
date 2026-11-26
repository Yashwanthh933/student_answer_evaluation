CREATE TABLE user_management_audit (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(30) NOT NULL CHECK (action IN (
        'USER_CREATED', 'USER_DISABLED', 'USER_ENABLED', 'PASSWORD_RESET', 'PASSWORD_CHANGED'
    )),
    target_username VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_user_management_audit_created_at
    ON user_management_audit (created_at DESC);
