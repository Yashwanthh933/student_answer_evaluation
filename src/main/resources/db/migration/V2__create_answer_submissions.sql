CREATE TABLE answer_submissions (
    id uuid PRIMARY KEY,
    scanner_username varchar(100) NOT NULL REFERENCES users(username),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE answer_pages (
    id uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES answer_submissions(id) ON DELETE CASCADE,
    stored_filename varchar(255) NOT NULL UNIQUE,
    content_type varchar(50) NOT NULL,
    uploaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_answer_pages_submission_id ON answer_pages (submission_id);
