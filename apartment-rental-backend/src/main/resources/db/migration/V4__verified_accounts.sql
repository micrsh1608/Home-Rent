ALTER TABLE users ADD email_verified BIT NOT NULL DEFAULT 0;
ALTER TABLE users ADD is_demo BIT NOT NULL DEFAULT 0;
ALTER TABLE users ADD auth_version INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD address NVARCHAR(300) NULL;
ALTER TABLE users ADD date_of_birth DATE NULL;

UPDATE users SET is_demo = 1 WHERE id IN ('o1', 'o2', 't1', 't2');

-- One current challenge per email. Reissuing replaces the previous code and id.
CREATE TABLE email_otp_challenges (
    email VARCHAR(150) NOT NULL,
    id VARCHAR(36) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    user_id VARCHAR(36) NULL,
    auth_version INT NULL,
    code_hash VARCHAR(255) NOT NULL,
    pending_name NVARCHAR(150) NULL,
    pending_password_hash VARCHAR(255) NULL,
    expires_at DATETIME2 NOT NULL,
    next_send_at DATETIME2 NOT NULL,
    window_start DATETIME2 NOT NULL,
    sent_count INT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed BIT NOT NULL DEFAULT 0,
    CONSTRAINT pk_email_otp_challenges PRIMARY KEY (email),
    CONSTRAINT uq_email_otp_challenge_id UNIQUE (id),
    CONSTRAINT ck_email_otp_purpose CHECK (purpose IN ('REGISTER', 'CHANGE_PASSWORD', 'RESET_PASSWORD'))
);

CREATE TABLE email_otp_rate_limits (
    rate_key VARCHAR(64) NOT NULL,
    window_start DATETIME2 NOT NULL,
    request_count INT NOT NULL,
    CONSTRAINT pk_email_otp_rate_limits PRIMARY KEY (rate_key)
);
