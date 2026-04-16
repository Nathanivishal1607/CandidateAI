-- ============================================================
-- AI-Powered Natural Language Candidate Search System
-- Schema: candidate_search
-- ============================================================

CREATE DATABASE IF NOT EXISTS candidate_search
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE candidate_search;

-- Candidates table: stores structured candidate profiles
-- resume_text holds the full raw text (OCR-extracted or parsed)
DROP TABLE IF EXISTS candidates;

CREATE TABLE candidates (
    id              INT             AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(150)    NOT NULL,
    email           VARCHAR(200)    UNIQUE,
    phone           VARCHAR(30),
    location        VARCHAR(150),
    skills          TEXT            COMMENT 'Comma-separated list of skills',
    experience_years DECIMAL(4,1)   DEFAULT 0,
    education       VARCHAR(255),
    current_role    VARCHAR(200),
    resume_text     LONGTEXT        COMMENT 'Full resume text (OCR or parsed)',
    resume_filename VARCHAR(300),
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_location  (location),
    INDEX idx_exp       (experience_years),
    FULLTEXT INDEX ft_skills_resume (skills, resume_text)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Uploads table: tracks uploaded resume files
DROP TABLE IF EXISTS resume_uploads;

CREATE TABLE resume_uploads (
    id              INT             AUTO_INCREMENT PRIMARY KEY,
    candidate_id    INT,
    original_name   VARCHAR(300),
    stored_path     VARCHAR(500),
    ocr_used        BOOLEAN         DEFAULT FALSE,
    extracted_text  LONGTEXT,
    uploaded_at     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (candidate_id) REFERENCES candidates(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Search logs: for analytics / debugging
DROP TABLE IF EXISTS search_log;

CREATE TABLE search_log (
    id              INT             AUTO_INCREMENT PRIMARY KEY,
    raw_query       TEXT,
    extracted_skills TEXT,
    extracted_exp   DECIMAL(4,1),
    extracted_loc   VARCHAR(150),
    result_count    INT,
    searched_at     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
