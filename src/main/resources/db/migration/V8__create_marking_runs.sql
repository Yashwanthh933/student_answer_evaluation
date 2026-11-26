CREATE TABLE marking_runs (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES answer_submissions(id) ON DELETE CASCADE,
    reading_run_id UUID NOT NULL REFERENCES transcription_runs(id) ON DELETE CASCADE,
    question_paper_id UUID NOT NULL REFERENCES question_papers(id),
    provider VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    succeeded BOOLEAN NOT NULL DEFAULT FALSE,
    raw_reply TEXT,
    parsed_marks JSONB,
    unassigned JSONB,
    error_message TEXT
);

CREATE INDEX ix_marking_runs_submission_started
    ON marking_runs (submission_id, started_at DESC);
