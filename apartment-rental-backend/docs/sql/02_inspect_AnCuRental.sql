-- Chạy trong SSMS, kết nối tới instance chứa database AnCuRental (chỉ đọc).
USE [AnCuRental];
GO

SELECT @@SERVERNAME AS server_name, DB_NAME() AS database_name;

SELECT SCHEMA_NAME(schema_id) AS schema_name, name AS table_name
FROM sys.tables
WHERE is_ms_shipped = 0
ORDER BY name;

-- Kiểm tra lịch sử migration Flyway (nếu đã nâng cấp lên V5-V7)
IF OBJECT_ID(N'dbo.flyway_schema_history', N'U') IS NOT NULL
    SELECT installed_rank, version, description, type, script, installed_on, success
    FROM dbo.flyway_schema_history
    ORDER BY installed_rank;

SELECT N'users' AS table_name, COUNT_BIG(*) AS row_count FROM dbo.users
UNION ALL SELECT N'buildings', COUNT_BIG(*) FROM dbo.buildings
UNION ALL SELECT N'apartments', COUNT_BIG(*) FROM dbo.apartments
UNION ALL SELECT N'contracts', COUNT_BIG(*) FROM dbo.contracts
UNION ALL SELECT N'contract_status_history', COUNT_BIG(*) FROM dbo.contract_status_history
UNION ALL SELECT N'invoices', COUNT_BIG(*) FROM dbo.invoices
UNION ALL SELECT N'invoice_lines', COUNT_BIG(*) FROM dbo.invoice_lines
UNION ALL SELECT N'saved_apartments', COUNT_BIG(*) FROM dbo.saved_apartments
UNION ALL SELECT N'email_otp_challenges', COUNT_BIG(*) FROM dbo.email_otp_challenges
UNION ALL SELECT N'email_otp_rate_limits', COUNT_BIG(*) FROM dbo.email_otp_rate_limits;

-- Tài khoản: không hiển thị nội dung băm mật khẩu.
SELECT id, email, full_name, role, phone, address, date_of_birth,
       email_verified, is_demo, active, created_at
FROM dbo.users ORDER BY role, full_name;

SELECT * FROM dbo.buildings;
SELECT * FROM dbo.apartments;
SELECT * FROM dbo.contracts;
SELECT * FROM dbo.contract_status_history ORDER BY created_at ASC, id ASC;
SELECT * FROM dbo.invoices;
SELECT * FROM dbo.invoice_lines;
SELECT * FROM dbo.saved_apartments;

-- OTP: chỉ hiển thị trạng thái yêu cầu.
SELECT email, purpose, expires_at, attempts, consumed, sent_count, next_send_at
FROM dbo.email_otp_challenges;
SELECT * FROM dbo.email_otp_rate_limits;

-- Ràng buộc CHECK và Filtered Unique Index
SELECT t.name AS table_name, cc.name AS constraint_name, cc.is_not_trusted, cc.definition
FROM sys.check_constraints cc
JOIN sys.tables t ON t.object_id = cc.parent_object_id
ORDER BY t.name, cc.name;

SELECT t.name AS table_name, i.name AS index_name, i.is_unique, i.has_filter, i.filter_definition
FROM sys.indexes i
JOIN sys.tables t ON t.object_id = i.object_id
WHERE t.is_ms_shipped = 0 AND i.name IS NOT NULL
ORDER BY t.name, i.name;
GO
