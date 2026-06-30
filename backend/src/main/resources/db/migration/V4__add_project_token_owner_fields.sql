ALTER TABLE project_mcp_token
  ADD COLUMN owner_type VARCHAR(32) NOT NULL DEFAULT 'PRIMELAYER_USER',
  ADD COLUMN owner_id VARCHAR(128) NULL,
  ADD COLUMN project_remark VARCHAR(256) NULL;

UPDATE project_mcp_token
SET owner_id = primelayer_user_id
WHERE owner_id IS NULL;

UPDATE project_mcp_token p
SET owner_type = 'OPEN_ID',
    owner_id = (
      SELECT u.feishu_open_id
      FROM user_binding u
      WHERE u.primelayer_user_id = p.primelayer_user_id AND u.status = 'ACTIVE'
      LIMIT 1
    )
WHERE p.owner_type = 'PRIMELAYER_USER'
  AND EXISTS (
      SELECT 1
      FROM user_binding u
      WHERE u.primelayer_user_id = p.primelayer_user_id AND u.status = 'ACTIVE'
  );

CREATE INDEX idx_project_token_owner ON project_mcp_token(owner_type, owner_id);
