ALTER TABLE contract_status_history ALTER COLUMN actor_id VARCHAR(36) NULL;

CREATE INDEX idx_contracts_no_show ON contracts (status, actual_move_in, move_in_deadline);
