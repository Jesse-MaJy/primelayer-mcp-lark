UPDATE project_mcp_token
SET owner_id = primelayer_user_id
WHERE owner_id IS NULL OR trim(owner_id) = '';

DELETE p_old
FROM project_mcp_token p_old
JOIN project_mcp_token p_new
  ON p_old.id < p_new.id
 AND p_old.owner_type = p_new.owner_type
 AND p_old.owner_id = p_new.owner_id
 AND lower(trim(p_old.project_id)) = lower(trim(p_new.project_id));

ALTER TABLE project_mcp_token
  MODIFY owner_id VARCHAR(128) NOT NULL;

CREATE UNIQUE INDEX uk_project_token_owner_project
  ON project_mcp_token(owner_type, owner_id, project_id);
