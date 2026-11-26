ALTER TABLE users RENAME COLUMN password TO password_hash;

ALTER TABLE users
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN display_name VARCHAR(200),
    ADD COLUMN role VARCHAR(30),
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN created_by UUID,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_login_at TIMESTAMPTZ;

UPDATE users
SET display_name = CASE username
        WHEN 'scanner' THEN 'Scanner'
        WHEN 'paper_uploader' THEN 'Exam Controller'
        WHEN 'super_admin' THEN 'Super Admin'
        ELSE username
    END;

UPDATE users AS u
SET role = old_roles.role
FROM (
    SELECT username,
           CASE
               WHEN bool_or(authority = 'ROLE_SUPER_ADMIN') THEN 'SUPER_ADMIN'
               WHEN bool_or(authority = 'ROLE_QUESTION_PAPER_UPLOADER') THEN 'EXAM_CONTROLLER'
               WHEN bool_or(authority = 'ROLE_SCANNER') THEN 'SCANNER'
               ELSE NULL
           END AS role
    FROM authorities
    GROUP BY username
) AS old_roles
WHERE old_roles.username = u.username;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE role IS NULL) THEN
        RAISE EXCEPTION 'Could not map an existing account to a Phase 17 role';
    END IF;
END
$$;

ALTER TABLE users
    ALTER COLUMN display_name SET NOT NULL,
    ALTER COLUMN role SET NOT NULL,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('SUPER_ADMIN', 'EXAM_CONTROLLER', 'SCANNER'));

DROP TABLE authorities;

ALTER TABLE answer_submissions
    DROP CONSTRAINT answer_submissions_scanner_username_fkey;
ALTER TABLE question_papers
    DROP CONSTRAINT question_papers_uploader_username_fkey;

ALTER TABLE users DROP CONSTRAINT users_pkey;
ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (id);
ALTER TABLE users ADD CONSTRAINT users_username_key UNIQUE (username);
ALTER TABLE users
    ADD CONSTRAINT users_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE answer_submissions
    ADD CONSTRAINT answer_submissions_scanner_username_fkey
    FOREIGN KEY (scanner_username) REFERENCES users(username);
ALTER TABLE question_papers
    ADD CONSTRAINT question_papers_uploader_username_fkey
    FOREIGN KEY (uploader_username) REFERENCES users(username);
