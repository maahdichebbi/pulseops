-- V1__create_users.sql
-- Creates the users table.
-- password_hash stores a bcrypt hash (always 60 chars for bcrypt cost ≤12).

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email)
);
