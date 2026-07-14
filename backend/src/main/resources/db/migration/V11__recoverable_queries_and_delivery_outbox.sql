ALTER TABLE agent_task
  ADD COLUMN phase VARCHAR(32) NULL AFTER status,
  ADD COLUMN heartbeat_at TIMESTAMP(3) NULL AFTER started_at;

CREATE TABLE agent_query_checkpoint (
  request_id VARCHAR(128) PRIMARY KEY,
  phase VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  state_json LONGTEXT NOT NULL,
  next_run_at TIMESTAMP(3) NULL,
  heartbeat_at TIMESTAMP(3) NOT NULL,
  notice_at TIMESTAMP(3) NOT NULL,
  deadline_at TIMESTAMP(3) NOT NULL,
  recovered_after_restart BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  INDEX idx_query_checkpoint_due (phase, next_run_at),
  INDEX idx_query_checkpoint_heartbeat (heartbeat_at)
);

CREATE TABLE agent_delivery_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  delivery_type VARCHAR(32) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(3) NOT NULL,
  lease_until TIMESTAMP(3) NULL,
  sent_at TIMESTAMP(3) NULL,
  feishu_message_id VARCHAR(128) NULL,
  last_error TEXT NULL,
  UNIQUE KEY uk_delivery_request_type (request_id, delivery_type),
  INDEX idx_delivery_due (status, next_attempt_at)
);
