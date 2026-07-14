CREATE TABLE prompt_template_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  stage VARCHAR(32) NOT NULL,
  domain VARCHAR(32) NOT NULL,
  version_no INT NOT NULL,
  content LONGTEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  created_by VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_prompt_version (stage, domain, version_no),
  INDEX idx_prompt_version_scope (stage, domain, status)
);

CREATE TABLE prompt_template_publication (
  stage VARCHAR(32) NOT NULL,
  domain VARCHAR(32) NOT NULL,
  version_id BIGINT NOT NULL,
  published_by VARCHAR(128) NOT NULL,
  published_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (stage, domain),
  CONSTRAINT fk_prompt_publication_version FOREIGN KEY (version_id) REFERENCES prompt_template_version(id)
);

CREATE TABLE prompt_template_publication_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  stage VARCHAR(32) NOT NULL,
  domain VARCHAR(32) NOT NULL,
  version_id BIGINT NOT NULL,
  action VARCHAR(16) NOT NULL,
  note VARCHAR(512),
  operated_by VARCHAR(128) NOT NULL,
  operated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_prompt_publication_audit_scope (stage, domain, operated_at)
);

CREATE TABLE prompt_replay_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  stage VARCHAR(32) NOT NULL,
  domain VARCHAR(32) NOT NULL,
  form_id VARCHAR(128),
  form_name VARCHAR(256),
  chunk_index INT NOT NULL,
  chunk_count INT NOT NULL,
  record_count INT NOT NULL DEFAULT 0,
  input_chars INT NOT NULL DEFAULT 0,
  encrypted_payload LONGTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_prompt_replay_chunk (request_id, stage, form_id, chunk_index),
  INDEX idx_prompt_replay_created (created_at),
  INDEX idx_prompt_replay_request (request_id, deleted_at)
);
