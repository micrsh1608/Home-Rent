-- ============================================================================
-- V7__enforce_business_constraints.sql (H2 MODE=MSSQLServer dialect)
-- Áp dụng đầy đủ ràng buộc nghiệp vụ ở mức cơ sở dữ liệu (P0-02, P0-04, P0-05, P0-06, P1-10, P1-11).
-- ============================================================================

ALTER TABLE contracts DROP CONSTRAINT ck_contracts_paid_requires_signed;
ALTER TABLE contracts DROP CONSTRAINT ck_contracts_handover_dates;
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_electric;
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_water;

ALTER TABLE apartments ADD CONSTRAINT ck_apartments_version CHECK (version >= 0);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_version CHECK (version >= 0);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_status_payment_strict CHECK (
    (status = 'AWAITING' AND payment_status = 'UNPAID')
    OR (status = 'SIGNED' AND payment_status = 'PAID')
    OR (status = 'CANCELLED' AND payment_status IN ('UNPAID', 'PAID'))
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_forfeit_requires_cancelled_paid CHECK (
    deposit_forfeited = 0 OR (status = 'CANCELLED' AND payment_status = 'PAID')
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_signed_metadata CHECK (
    status <> 'SIGNED'
    OR (signed_at IS NOT NULL AND signed_confirmed_by IS NOT NULL
        AND payment_confirmed_at IS NOT NULL AND payment_confirmed_by IS NOT NULL)
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_deposit_verification CHECK (
    deposit_verification_status IN ('NOT_APPLICABLE', 'VERIFIED', 'LEGACY_UNVERIFIED')
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_deposit_received CHECK (
    deposit_received_amount IS NULL OR deposit_received_amount >= 0
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_paid_deposit_verification CHECK (
    (payment_status = 'UNPAID'
        AND deposit_verification_status = 'NOT_APPLICABLE'
        AND deposit_received_amount IS NULL)
    OR (payment_status = 'PAID' AND (
        (deposit_verification_status = 'VERIFIED'
            AND deposit_received_amount IS NOT NULL
            AND deposit_received_amount >= deposit_amount)
        OR (deposit_verification_status = 'LEGACY_UNVERIFIED'
            AND deposit_received_amount IS NULL)
    ))
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_handover_dates_strict CHECK (
    actual_move_out IS NULL OR (actual_move_in IS NOT NULL AND actual_move_out >= actual_move_in)
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_handover_meter_status CHECK (
    handover_meter_status IN ('NOT_RECORDED', 'VERIFIED', 'LEGACY_UNVERIFIED')
);

ALTER TABLE contracts ADD CONSTRAINT ck_contracts_handover_meters CHECK (
    (handover_meter_status = 'NOT_RECORDED'
        AND actual_move_in IS NULL
        AND handover_electric_reading IS NULL
        AND handover_water_reading IS NULL)
    OR (handover_meter_status = 'LEGACY_UNVERIFIED'
        AND actual_move_in IS NOT NULL
        AND handover_electric_reading IS NULL
        AND handover_water_reading IS NULL)
    OR (handover_meter_status = 'VERIFIED'
        AND actual_move_in IS NOT NULL
        AND handover_electric_reading IS NOT NULL AND handover_electric_reading >= 0
        AND handover_water_reading IS NOT NULL AND handover_water_reading >= 0)
);

-- Tương đương filtered unique index trên H2 (MODE=MSSQLServer chỉ cho phép 1 NULL trong unique index thông thường)
ALTER TABLE contracts ADD active_renewal_guard VARCHAR(80) GENERATED ALWAYS AS (
    CASE WHEN renewal_of_id IS NOT NULL AND status <> 'CANCELLED'
         THEN 'R:' || renewal_of_id
         ELSE 'N:' || id
    END
);
CREATE UNIQUE INDEX uq_contracts_active_renewal_of ON contracts (active_renewal_guard);

ALTER TABLE invoices ADD CONSTRAINT ck_invoices_period_index CHECK (
    (kind = 'MONTHLY' AND period_index IS NOT NULL AND period_index >= 1)
    OR (kind = 'EARLY_MOVE_IN' AND period_index IS NULL)
);

ALTER TABLE invoices ADD CONSTRAINT ck_invoices_monthly_meters_strict CHECK (
    (kind = 'EARLY_MOVE_IN'
        AND old_electric IS NULL AND new_electric IS NULL
        AND old_water IS NULL AND new_water IS NULL)
    OR (kind = 'MONTHLY'
        AND old_electric IS NOT NULL AND new_electric IS NOT NULL
        AND old_electric >= 0 AND new_electric >= old_electric
        AND old_water IS NOT NULL AND new_water IS NOT NULL
        AND old_water >= 0 AND new_water >= old_water)
);

ALTER TABLE invoices ADD CONSTRAINT ck_invoices_meter_verification CHECK (
    meter_verification_status IN ('NOT_APPLICABLE', 'VERIFIED', 'LEGACY_UNVERIFIED', 'LEGACY_DISCREPANCY')
);

ALTER TABLE invoices ADD CONSTRAINT ck_invoices_paid_metadata CHECK (
    (status = 'UNPAID' AND paid_at IS NULL AND confirmed_by IS NULL)
    OR (status = 'PAID' AND paid_at IS NOT NULL AND confirmed_by IS NOT NULL)
);

ALTER TABLE invoices ADD monthly_period_guard VARCHAR(90) GENERATED ALWAYS AS (
    CASE WHEN kind = 'MONTHLY'
         THEN 'M:' || contract_id || ':' || CAST(period_index AS VARCHAR(20))
         ELSE 'E:' || id
    END
);
CREATE UNIQUE INDEX uq_invoices_contract_monthly_period ON invoices (monthly_period_guard);

ALTER TABLE invoices ADD early_move_in_guard VARCHAR(80) GENERATED ALWAYS AS (
    CASE WHEN kind = 'EARLY_MOVE_IN'
         THEN 'P:' || contract_id
         ELSE 'M:' || id
    END
);
CREATE UNIQUE INDEX uq_invoices_contract_early_move_in ON invoices (early_move_in_guard);

CREATE INDEX idx_invoices_contract_period ON invoices (contract_id, kind, period_index);
