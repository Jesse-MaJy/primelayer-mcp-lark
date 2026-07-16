DELETE FROM project_mcp_token
WHERE owner_type IN ('CHAT_ID', 'PRIMELAYER_USER');

ALTER TABLE project_mcp_token
  DROP INDEX uk_project_token_owner_project,
  DROP INDEX uk_project_token_user_project,
  ADD COLUMN feishu_open_id VARCHAR(128) NULL AFTER id,
  ADD COLUMN mcp_user_id VARCHAR(128) NULL AFTER feishu_open_id;

UPDATE project_mcp_token
SET feishu_open_id = owner_id,
    mcp_user_id = NULL
WHERE owner_type = 'OPEN_ID';

ALTER TABLE project_mcp_token
  MODIFY feishu_open_id VARCHAR(128) NOT NULL,
  DROP COLUMN owner_type,
  DROP COLUMN owner_id,
  DROP COLUMN primelayer_user_id,
  ADD UNIQUE INDEX uk_project_token_feishu_project (feishu_open_id, project_id),
  ADD INDEX idx_project_token_feishu_status (feishu_open_id, token_status, verify_status);

ALTER TABLE user_binding
  DROP INDEX idx_user_binding_pl_user,
  DROP COLUMN primelayer_user_id,
  DROP COLUMN primelayer_user_name;

DROP TABLE feishu_chat_project_binding;

ALTER TABLE agent_task
  ADD COLUMN resolved_question TEXT NULL AFTER message_text,
  ADD COLUMN resolved_project_ids JSON NULL AFTER resolved_question,
  ADD COLUMN project_scope_notice VARCHAR(512) NULL AFTER resolved_project_ids;

CREATE TABLE agent_project_scope_clarification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_key VARCHAR(384) NOT NULL UNIQUE,
  feishu_open_id VARCHAR(128) NOT NULL,
  feishu_chat_id VARCHAR(128) NOT NULL,
  original_request_id VARCHAR(128) NOT NULL UNIQUE,
  original_task_id BIGINT NOT NULL,
  original_question TEXT NOT NULL,
  candidate_projects JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  claimed_by_request_id VARCHAR(128),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_project_scope_status (status, updated_at),
  CONSTRAINT fk_project_scope_original_task FOREIGN KEY (original_task_id) REFERENCES agent_task(id)
);
