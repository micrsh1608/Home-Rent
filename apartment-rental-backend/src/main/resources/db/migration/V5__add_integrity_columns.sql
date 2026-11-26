-- ============================================================================
-- V5__add_integrity_columns.sql
-- Bổ sung các cột toàn vẹn nghiệp vụ tương thích cả SQL Server và H2:
-- - Số tiền cọc thực nhận và trạng thái xác minh cọc (P0-02)
-- - Chỉ số điện/nước bàn giao ban đầu và trạng thái xác minh bàn giao (P0-04)
-- - Số kỳ thuê (period_index) và trạng thái xác minh chỉ số hóa đơn (P0-04, P0-05)
-- - Phiên bản khóa lạc quan (version) trên contracts và apartments (P1-10)
-- ============================================================================

ALTER TABLE apartments ADD version INT NOT NULL DEFAULT 0;

ALTER TABLE contracts ADD deposit_received_amount BIGINT NULL;
ALTER TABLE contracts ADD deposit_verification_status VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE';
ALTER TABLE contracts ADD handover_electric_reading INT NULL;
ALTER TABLE contracts ADD handover_water_reading INT NULL;
ALTER TABLE contracts ADD handover_meter_status VARCHAR(20) NOT NULL DEFAULT 'NOT_RECORDED';
ALTER TABLE contracts ADD version INT NOT NULL DEFAULT 0;

ALTER TABLE invoices ADD period_index INT NULL;
ALTER TABLE invoices ADD meter_verification_status VARCHAR(20) NOT NULL DEFAULT 'VERIFIED';
