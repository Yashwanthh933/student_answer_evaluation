ALTER TABLE answer_pages ADD COLUMN page_number integer;

UPDATE answer_pages SET page_number = 1;

ALTER TABLE answer_pages ALTER COLUMN page_number SET NOT NULL;
ALTER TABLE answer_pages
    ADD CONSTRAINT ck_answer_pages_page_number_positive CHECK (page_number > 0);
ALTER TABLE answer_pages
    ADD CONSTRAINT uq_answer_pages_submission_page UNIQUE (submission_id, page_number);
