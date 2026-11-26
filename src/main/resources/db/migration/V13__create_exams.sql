CREATE TABLE exams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    subject_name VARCHAR(200) NOT NULL,
    course_code VARCHAR(80),
    branch VARCHAR(80) NOT NULL,
    year INTEGER NOT NULL,
    exam_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX exams_list_order_idx ON exams (exam_date DESC, name, branch, year);
