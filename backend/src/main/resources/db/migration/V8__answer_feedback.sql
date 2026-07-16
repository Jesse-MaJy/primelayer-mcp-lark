CREATE TABLE agent_answer_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  feishu_open_id VARCHAR(128) NOT NULL,
  rating VARCHAR(32) NOT NULL,
  reason_code VARCHAR(32),
  detail VARCHAR(500),
  feishu_message_id VARCHAR(128),
  last_event_id VARCHAR(128) NOT NULL,
  last_event_time BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_answer_feedback_request_user (request_id, feishu_open_id),
  INDEX idx_answer_feedback_request (request_id),
  INDEX idx_answer_feedback_rating (rating)
);
