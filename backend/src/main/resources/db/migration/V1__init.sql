CREATE TABLE admin_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE user_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  feishu_open_id VARCHAR(128) NOT NULL UNIQUE,
  primelayer_user_id VARCHAR(128) NOT NULL,
  primelayer_user_name VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_binding_pl_user (primelayer_user_id)
);

CREATE TABLE project_mcp_token (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  primelayer_user_id VARCHAR(128) NOT NULL,
  project_id VARCHAR(128) NOT NULL,
  project_name VARCHAR(256) NOT NULL,
  mcp_token_ciphertext TEXT NOT NULL,
  token_hash_suffix VARCHAR(32),
  token_status VARCHAR(32) NOT NULL,
  imported_by VARCHAR(128),
  imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at TIMESTAMP NULL,
  UNIQUE KEY uk_project_token_user_project (primelayer_user_id, project_id),
  INDEX idx_project_token_project (project_id)
);

CREATE TABLE feishu_chat_project_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  feishu_chat_id VARCHAR(128) NOT NULL UNIQUE,
  project_id VARCHAR(128) NOT NULL,
  project_name VARCHAR(256) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_by VARCHAR(128),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE agent_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL UNIQUE,
  feishu_message_id VARCHAR(128) NOT NULL UNIQUE,
  feishu_open_id VARCHAR(128) NOT NULL,
  feishu_chat_id VARCHAR(128),
  chat_type VARCHAR(32) NOT NULL,
  message_text TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMP NULL,
  finished_at TIMESTAMP NULL,
  INDEX idx_agent_task_status (status),
  INDEX idx_agent_task_user (feishu_open_id)
);

CREATE TABLE agent_audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL UNIQUE,
  feishu_open_id VARCHAR(128),
  feishu_chat_id VARCHAR(128),
  primelayer_user_id VARCHAR(128),
  project_ids TEXT,
  user_question TEXT,
  intent VARCHAR(128),
  final_answer TEXT,
  latency_ms BIGINT,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  project_id VARCHAR(128),
  primelayer_user_id VARCHAR(128),
  tool_name VARCHAR(128) NOT NULL,
  tool_arguments JSON,
  tool_status VARCHAR(32) NOT NULL,
  latency_ms BIGINT,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_tool_call_request (request_id)
);

CREATE TABLE agent_model_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  purpose VARCHAR(64) NOT NULL,
  input_summary TEXT,
  output_text MEDIUMTEXT,
  status VARCHAR(32) NOT NULL,
  latency_ms BIGINT,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_model_call_request (request_id)
);

CREATE TABLE system_config (
  config_key VARCHAR(128) PRIMARY KEY,
  config_value TEXT NOT NULL,
  description VARCHAR(512),
  is_sensitive TINYINT(1) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
