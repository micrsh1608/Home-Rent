-- ============================================================================
-- 03_preflight_check_V5.sql (READ-ONLY — KHÔNG chỉnh sửa hay xóa bất kỳ dữ liệu nào)
-- Chạy trong SSMS trên database AnCuRental TRƯỚC KHI nâng cấp V4 -> V5..V7.
-- Nếu bất kỳ truy vấn BLOCKING nào trả về > 0 dòng, hãy xử lý đối soát thủ công
-- và backup database trước khi chạy migration.
-- ============================================================================
USE [AnCuRental];
GO

SELECT @@SERVERNAME AS server_name, DB_NAME() AS database_name, SYSDATETIME() AS checked_at;

-- [BLOCKING 1] Cặp (status, payment_status) không hợp lệ (ví dụ SIGNED + UNPAID hoặc AWAITING + PAID)
SELECT 'BLOCKING_STATUS_PAYMENT' AS check_code, id, contract_code, status, payment_status
FROM dbo.contracts
WHERE NOT (
    (status = 'AWAITING' AND payment_status = 'UNPAID')
    OR (status = 'SIGNED' AND payment_status = 'PAID')
    OR (status = 'CANCELLED' AND payment_status IN ('UNPAID', 'PAID'))
);

-- [BLOCKING 2] deposit_forfeited = 1 nhưng không phải CANCELLED + PAID
SELECT 'BLOCKING_FORFEIT' AS check_code, id, contract_code, status, payment_status, deposit_forfeited
FROM dbo.contracts
WHERE deposit_forfeited = 1 AND NOT (status = 'CANCELLED' AND payment_status = 'PAID');

-- [BLOCKING 3] Hợp đồng SIGNED thiếu metadata xác nhận ký / thu cọc
SELECT 'BLOCKING_SIGNED_META' AS check_code, id, contract_code, signed_at, signed_confirmed_by, payment_confirmed_at, payment_confirmed_by
FROM dbo.contracts
WHERE status = 'SIGNED'
  AND (signed_at IS NULL OR signed_confirmed_by IS NULL OR payment_confirmed_at IS NULL OR payment_confirmed_by IS NULL);

-- [BLOCKING 4] Ngày trả phòng không hợp lệ (trả phòng khi chưa có ngày nhận phòng hoặc < ngày nhận phòng)
SELECT 'BLOCKING_HANDOVER_DATES' AS check_code, id, contract_code, actual_move_in, actual_move_out
FROM dbo.contracts
WHERE actual_move_out IS NOT NULL AND (actual_move_in IS NULL OR actual_move_out < actual_move_in);

-- [BLOCKING 5] Hai hợp đồng SIGNED trùng khoảng ngày hiệu lực trên cùng một căn hộ
WITH signed_spans AS (
    SELECT
        id, contract_code, apartment_id,
        CASE WHEN actual_move_in IS NOT NULL AND actual_move_in < start_date THEN actual_move_in ELSE start_date END AS eff_start,
        end_date
    FROM dbo.contracts
    WHERE status = 'SIGNED'
)
SELECT 'BLOCKING_OVERLAPPING_SIGNED' AS check_code,
       a.apartment_id, a.id AS contract_a, a.eff_start AS start_a, a.end_date AS end_a,
       b.id AS contract_b, b.eff_start AS start_b, b.end_date AS end_b
FROM signed_spans a
JOIN signed_spans b
  ON a.apartment_id = b.apartment_id
 AND a.id < b.id
 AND a.eff_start <= b.end_date
 AND b.eff_start <= a.end_date;

-- [BLOCKING 6] Một hợp đồng gốc có nhiều hơn 1 hợp đồng gia hạn chưa hủy
SELECT 'BLOCKING_DUPLICATE_RENEWAL' AS check_code, renewal_of_id, COUNT(*) AS active_renewal_count
FROM dbo.contracts
WHERE renewal_of_id IS NOT NULL AND status <> 'CANCELLED'
GROUP BY renewal_of_id
HAVING COUNT(*) > 1;

-- [BLOCKING 7] Hóa đơn PAID thiếu paid_at/confirmed_by hoặc UNPAID có giá trị paid_at/confirmed_by
SELECT 'BLOCKING_INVOICE_PAID_META' AS check_code, id, invoice_code, status, paid_at, confirmed_by
FROM dbo.invoices
WHERE (status = 'PAID' AND (paid_at IS NULL OR confirmed_by IS NULL))
   OR (status = 'UNPAID' AND (paid_at IS NOT NULL OR confirmed_by IS NOT NULL));

-- [BLOCKING 8] Hóa đơn MONTHLY thiếu chỉ số / chỉ số âm / mới < cũ, hoặc EARLY_MOVE_IN có chỉ số
SELECT 'BLOCKING_INVOICE_METERS' AS check_code, id, invoice_code, kind, old_electric, new_electric, old_water, new_water
FROM dbo.invoices
WHERE (kind = 'MONTHLY' AND (old_electric IS NULL OR new_electric IS NULL OR old_electric < 0 OR new_electric < old_electric
                             OR old_water IS NULL OR new_water IS NULL OR old_water < 0 OR new_water < old_water))
   OR (kind = 'EARLY_MOVE_IN' AND (old_electric IS NOT NULL OR new_electric IS NOT NULL OR old_water IS NOT NULL OR new_water IS NOT NULL));

-- [BLOCKING 9] Nhiều hơn 1 hóa đơn phạt EARLY_MOVE_IN trên cùng hợp đồng
SELECT 'BLOCKING_DUP_EARLY_PENALTY' AS check_code, contract_id, COUNT(*) AS penalty_count
FROM dbo.invoices
WHERE kind = 'EARLY_MOVE_IN'
GROUP BY contract_id
HAVING COUNT(*) > 1;

-- [AUDIT 1 - KHÔNG CHẶN] Hợp đồng PAID lịch sử chưa có số tiền cọc thực nhận (sẽ gắn LEGACY_UNVERIFIED)
SELECT 'AUDIT_LEGACY_PAID_DEPOSIT' AS check_code, id, contract_code, deposit_amount, status, payment_status
FROM dbo.contracts
WHERE payment_status = 'PAID';

-- [AUDIT 2 - KHÔNG CHẶN] Hợp đồng đã nhận phòng lịch sử chưa có chỉ số điện nước bàn giao (sẽ gắn LEGACY_UNVERIFIED)
SELECT 'AUDIT_LEGACY_HANDOVER_METERS' AS check_code, id, contract_code, actual_move_in
FROM dbo.contracts
WHERE actual_move_in IS NOT NULL;
GO
