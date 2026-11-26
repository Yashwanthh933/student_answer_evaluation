CREATE TABLE question_papers (
    id uuid PRIMARY KEY,
    uploader_username varchar(100) NOT NULL REFERENCES users(username),
    stored_filename varchar(255) NOT NULL UNIQUE,
    content_type varchar(50) NOT NULL CHECK (content_type = 'application/pdf'),
    uploaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
