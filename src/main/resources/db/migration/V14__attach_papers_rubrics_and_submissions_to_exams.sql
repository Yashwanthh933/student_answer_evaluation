ALTER TABLE question_papers
    ADD COLUMN exam_id UUID,
    ADD COLUMN replaced BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE answer_submissions ADD COLUMN exam_id UUID;

CREATE TABLE exam_rubrics (
    exam_id UUID PRIMARY KEY REFERENCES exams(id),
    rubric_text TEXT NOT NULL,
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
DECLARE
    placeholder_id UUID;
    creator_id UUID;
BEGIN
    IF EXISTS (SELECT 1 FROM question_papers) OR EXISTS (SELECT 1 FROM answer_submissions) THEN
        SELECT id INTO placeholder_id
        FROM exams
        WHERE name = 'Unassigned (before Module 5)' AND status = 'CLOSED'
        ORDER BY created_at, id
        LIMIT 1;

        IF placeholder_id IS NULL THEN
            SELECT id INTO creator_id
            FROM users
            ORDER BY CASE WHEN role = 'SUPER_ADMIN' THEN 0 ELSE 1 END, created_at, id
            LIMIT 1;

            IF creator_id IS NULL THEN
                RAISE EXCEPTION 'Cannot create the legacy exam placeholder without a user account';
            END IF;

            INSERT INTO exams (name, subject_name, course_code, branch, year, exam_date, status, created_by)
            VALUES ('Unassigned (before Module 5)', 'Unassigned', NULL, 'Unassigned', 1,
                    CURRENT_DATE, 'CLOSED', creator_id)
            RETURNING id INTO placeholder_id;
        END IF;

        UPDATE question_papers SET exam_id = placeholder_id;
        UPDATE answer_submissions SET exam_id = placeholder_id;
    END IF;
END;
$$;

WITH ordered_papers AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY exam_id ORDER BY uploaded_at DESC, id DESC) AS position
    FROM question_papers
)
UPDATE question_papers AS paper
SET replaced = ordered_papers.position > 1
FROM ordered_papers
WHERE ordered_papers.id = paper.id;

ALTER TABLE question_papers
    ALTER COLUMN exam_id SET NOT NULL,
    ADD CONSTRAINT question_papers_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES exams(id);

ALTER TABLE answer_submissions
    ALTER COLUMN exam_id SET NOT NULL,
    ADD CONSTRAINT answer_submissions_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES exams(id);

CREATE INDEX ix_question_papers_exam_id ON question_papers (exam_id);
CREATE UNIQUE INDEX uq_question_papers_current_per_exam ON question_papers (exam_id) WHERE replaced = FALSE;

INSERT INTO exam_rubrics (exam_id, rubric_text, updated_by, updated_at)
SELECT DISTINCT ON (paper.exam_id)
       paper.exam_id, old_rubric.rubric_text, uploader.id, old_rubric.updated_at
FROM question_paper_rubrics AS old_rubric
JOIN question_papers AS paper ON paper.id = old_rubric.question_paper_id
JOIN users AS uploader ON uploader.username = paper.uploader_username
ORDER BY paper.exam_id, old_rubric.updated_at DESC, paper.id DESC;

DROP TABLE question_paper_rubrics;
