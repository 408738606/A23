-- DocFusion MySQL schema script
CREATE DATABASE IF NOT EXISTS docfusion
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE docfusion;

CREATE TABLE IF NOT EXISTS user_accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(1024),
  bio VARCHAR(512),
  auth_token VARCHAR(128) UNIQUE,
  token_expires_at DATETIME,
  created_at DATETIME,
  updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS llm_configs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  config_name VARCHAR(255) NOT NULL UNIQUE,
  provider VARCHAR(255) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  api_key VARCHAR(2048),
  model_name VARCHAR(255) NOT NULL,
  max_tokens INT,
  temperature DOUBLE,
  is_default BIT,
  is_active BIT,
  model_category VARCHAR(32),
  created_at DATETIME,
  updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(64) NOT NULL,
  file_path VARCHAR(1024) NOT NULL,
  extracted_text LONGTEXT,
  summary LONGTEXT,
  file_size BIGINT,
  category VARCHAR(255),
  library_type VARCHAR(32),
  sub_database VARCHAR(255),
  processed BIT,
  upload_time DATETIME,
  last_modified DATETIME
);

CREATE TABLE IF NOT EXISTS chat_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(128),
  role VARCHAR(32),
  content LONGTEXT,
  created_at DATETIME
);

CREATE TABLE IF NOT EXISTS output_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(128),
  file_name VARCHAR(255),
  file_path VARCHAR(1024),
  file_type VARCHAR(32),
  file_size BIGINT,
  description VARCHAR(1024),
  saved_to_knowledge_base BIT,
  created_at DATETIME
);
