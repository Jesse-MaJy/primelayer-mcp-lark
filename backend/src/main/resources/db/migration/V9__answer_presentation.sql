ALTER TABLE agent_audit_log
  ADD COLUMN presentation_json MEDIUMTEXT NULL AFTER final_answer;
