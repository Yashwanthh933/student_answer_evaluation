CREATE TABLE question_paper_rubrics (
    question_paper_id UUID PRIMARY KEY REFERENCES question_papers(id) ON DELETE CASCADE,
    rubric_text TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
