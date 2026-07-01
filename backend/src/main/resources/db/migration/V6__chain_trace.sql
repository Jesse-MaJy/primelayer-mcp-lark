CREATE TABLE agent_chain_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  trace_data JSON NOT NULL COMMENT '完整链调 JSON：nodes, edges, summary',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chain_trace_request (request_id)
);

ALTER TABLE agent_tool_call_log
  ADD COLUMN page INT DEFAULT 0,
  ADD COLUMN page_size INT DEFAULT 100,
  ADD COLUMN total_count INT DEFAULT 0;
