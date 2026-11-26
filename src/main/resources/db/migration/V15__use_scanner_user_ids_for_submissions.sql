ALTER TABLE answer_submissions ADD COLUMN scanner_user_id UUID;

UPDATE answer_submissions AS submission
SET scanner_user_id = scanner.id
FROM users AS scanner
WHERE scanner.username = submission.scanner_username;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM answer_submissions WHERE scanner_user_id IS NULL) THEN
        RAISE EXCEPTION 'Could not link every submission owner to a user account';
    END IF;
END;
$$;

ALTER TABLE answer_submissions
    ALTER COLUMN scanner_user_id SET NOT NULL,
    ADD CONSTRAINT answer_submissions_scanner_user_id_fkey FOREIGN KEY (scanner_user_id) REFERENCES users(id),
    DROP CONSTRAINT answer_submissions_scanner_username_fkey,
    DROP COLUMN scanner_username;

CREATE INDEX ix_answer_submissions_scanner_created
    ON answer_submissions (scanner_user_id, created_at DESC);
