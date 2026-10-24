-- NexusID v50 safe migration: allow one parent to link to multiple students.
-- Keeps student_id unique, so each student can still have at most one parent.
-- BACK UP THE DATABASE BEFORE RUNNING. MySQL DDL may implicitly commit.
-- Review existing duplicates before applying; the existing v50 schema should have none.
ALTER TABLE parent_links
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (parent_user_id, student_id);
