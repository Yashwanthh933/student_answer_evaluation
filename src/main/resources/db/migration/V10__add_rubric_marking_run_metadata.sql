ALTER TABLE marking_runs
    ADD COLUMN run_type VARCHAR(20) NOT NULL DEFAULT 'no-rubric'
        CHECK (run_type IN ('no-rubric', 'rubric')),
    ADD COLUMN rubric_text TEXT;
