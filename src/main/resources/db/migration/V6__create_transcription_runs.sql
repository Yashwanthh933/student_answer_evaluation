CREATE TABLE transcription_runs (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES answer_submissions(id) ON DELETE CASCADE,
    provider VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    succeeded BOOLEAN NOT NULL DEFAULT FALSE,
    raw_reply TEXT,
    parsed_answers JSONB,
    error_message TEXT
);

CREATE INDEX ix_transcription_runs_submission_started
    ON transcription_runs (submission_id, started_at DESC);
