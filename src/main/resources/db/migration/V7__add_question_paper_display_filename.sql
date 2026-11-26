ALTER TABLE question_papers ADD COLUMN display_filename VARCHAR(255);

UPDATE question_papers SET display_filename = stored_filename;

ALTER TABLE question_papers ALTER COLUMN display_filename SET NOT NULL;
