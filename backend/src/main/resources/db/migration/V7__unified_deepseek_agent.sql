DELETE FROM system_config
WHERE config_key = 'ai.engine' OR config_key LIKE 'ai.fastgpt.%';

INSERT INTO system_config(config_key, config_value, description, is_sensitive)
VALUES ('ai.deepseek.model', 'deepseek-v4-pro', 'Selected DeepSeek model for unified agent queries.', 0)
ON DUPLICATE KEY UPDATE description = VALUES(description), is_sensitive = 0;
