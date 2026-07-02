CREATE TABLE IF NOT EXISTS agent_chain_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  trace_data JSON NOT NULL COMMENT '完整链调 JSON：nodes, edges, summary',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chain_trace_request (request_id)
);

-- 分页字段，仅当列不存在时才添加
DROP PROCEDURE IF EXISTS add_column_if_missing;
CREATE PROCEDURE add_column_if_missing(IN tbl VARCHAR(128), IN col VARCHAR(128), IN col_def VARCHAR(256))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = col
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', col_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END;

CALL add_column_if_missing('agent_tool_call_log', 'page', 'INT DEFAULT 0');
CALL add_column_if_missing('agent_tool_call_log', 'page_size', 'INT DEFAULT 100');
CALL add_column_if_missing('agent_tool_call_log', 'total_count', 'INT DEFAULT 0');

DROP PROCEDURE IF EXISTS add_column_if_missing;
