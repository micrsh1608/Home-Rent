-- Generated from backend migrations V1-V4. Run only on an empty AnCuRental database.
-- The complete initialization is atomic; existing tables cause an immediate stop.
USE [AnCuRental];
GO
SET NOCOUNT ON;
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF SCHEMA_NAME() <> N'dbo'
        THROW 51000, N'Run with a user whose default schema is dbo.', 1;
    IF EXISTS (SELECT 1 FROM sys.tables WHERE is_ms_shipped = 0)
        THROW 51001, N'AnCuRental already has tables. Initialization was stopped to preserve existing data.', 1;
    -- V1__init_schema.sql
    EXEC sys.sp_executesql N'-- ============================================================================
-- V1__init_schema.sql
-- Lược đồ cơ sở dữ liệu hệ thống cho thuê căn hộ An Cư (Microsoft SQL Server)
-- Bao gồm: Người dùng, Tòa nhà, Căn hộ (Tin đăng), Hợp đồng, Lịch sử hợp đồng,
--          Hóa đơn, Dòng hóa đơn và Căn hộ đã lưu.
-- ============================================================================

CREATE TABLE users (
    id VARCHAR(36) NOT NULL,
    email VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name NVARCHAR(150) NOT NULL,
    role VARCHAR(20) NOT NULL,
    phone VARCHAR(30) NULL,
    active BIT NOT NULL DEFAULT 1,
    created_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN (''OWNER'', ''TENANT''))
);
';
    EXEC sys.sp_executesql N'
CREATE TABLE buildings (
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    name NVARCHAR(200) NOT NULL,
    district NVARCHAR(100) NOT NULL,
    address NVARCHAR(300) NOT NULL,
    signing_location NVARCHAR(300) NOT NULL,
    electric_rate BIGINT NOT NULL,
    water_rate BIGINT NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_buildings PRIMARY KEY (id),
    CONSTRAINT fk_buildings_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_buildings_electric_rate CHECK (electric_rate >= 0),
    CONSTRAINT ck_buildings_water_rate CHECK (water_rate >= 0)
);
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_buildings_owner ON buildings (owner_id);
';
    EXEC sys.sp_executesql N'CREATE INDEX idx_buildings_district ON buildings (district);
';
    EXEC sys.sp_executesql N'
CREATE TABLE apartments (
    id VARCHAR(36) NOT NULL,
    building_id VARCHAR(36) NOT NULL,
    room_code VARCHAR(50) NOT NULL,
    title NVARCHAR(250) NOT NULL,
    monthly_price BIGINT NOT NULL,
    area_m2 DECIMAL(8, 2) NOT NULL,
    bedrooms INT NOT NULL,
    bathrooms INT NOT NULL,
    image_key VARCHAR(100) NOT NULL DEFAULT ''greenhouse'',
    custom_image NVARCHAR(MAX) NULL,
    published BIT NOT NULL DEFAULT 1,
    condition_status VARCHAR(20) NOT NULL DEFAULT ''READY'',
    description NVARCHAR(2000) NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_apartments PRIMARY KEY (id),
    CONSTRAINT fk_apartments_building FOREIGN KEY (building_id) REFERENCES buildings (id),
    CONSTRAINT uq_apartments_building_room UNIQUE (building_id, room_code),
    CONSTRAINT ck_apartments_price CHECK (monthly_price > 0),
    CONSTRAINT ck_apartments_area CHECK (area_m2 > 0),
    CONSTRAINT ck_apartments_bedrooms CHECK (bedrooms >= 0),
    CONSTRAINT ck_apartments_bathrooms CHECK (bathrooms >= 1),
    CONSTRAINT ck_apartments_condition CHECK (condition_status IN (''READY'', ''MAINTENANCE''))
);
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_apartments_building ON apartments (building_id);
';
    EXEC sys.sp_executesql N'CREATE INDEX idx_apartments_public_search ON apartments (published, condition_status, monthly_price, bedrooms);
';
    EXEC sys.sp_executesql N'
-- Bảng hợp đồng: tích hợp 2 quyết định nghiệp vụ đã chốt:
-- 1) Người môi giới / Chủ nhà (OWNER) xác nhận khách đã ký tại điểm hẹn & ghi nhận tiền đặt cọc Đã thanh toán.
--    Nếu quá 3 ngày kể từ ngày dọn vào quy định (start_date) mà chưa dọn vào thực tế (actual_move_in IS NULL),
--    hợp đồng chuyển sang CANCELLED và mất tiền đặt cọc (deposit_forfeited = 1).
-- 2) Chu kỳ tính tiền thuê tính từ ngày dọn vào quy định trên hợp đồng (start_date) theo thời hạn thuê
--    (1 tháng, 1 năm hoặc tùy chọn), quy đổi 1 tháng theo 30/31 ngày và riêng tháng 2 luôn tính 30 ngày.
CREATE TABLE contracts (
    id VARCHAR(36) NOT NULL,
    contract_code VARCHAR(32) NOT NULL,
    apartment_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    renewal_of_id VARCHAR(36) NULL,
    apartment_title NVARCHAR(250) NOT NULL,
    room_code VARCHAR(50) NOT NULL,
    property_name NVARCHAR(200) NOT NULL,
    signing_location NVARCHAR(300) NOT NULL,
    terms_version VARCHAR(20) NOT NULL DEFAULT ''v1.0'',
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    lease_term_unit VARCHAR(20) NOT NULL DEFAULT ''CUSTOM'',
    lease_term_value INT NOT NULL DEFAULT 6,
    monthly_rent BIGINT NOT NULL,
    deposit_amount BIGINT NOT NULL,
    electric_rate BIGINT NOT NULL,
    water_rate BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT ''AWAITING'',
    payment_status VARCHAR(20) NOT NULL DEFAULT ''UNPAID'',
    appointment_at DATETIME2 NULL,
    signed_at DATETIME2 NULL,
    signed_confirmed_by VARCHAR(36) NULL,
    payment_confirmed_at DATETIME2 NULL,
    payment_confirmed_by VARCHAR(36) NULL,
    move_in_deadline DATE NOT NULL,
    actual_move_in DATE NULL,
    actual_move_out DATE NULL,
    deposit_forfeited BIT NOT NULL DEFAULT 0,
    cancelled_at DATETIME2 NULL,
    cancellation_reason NVARCHAR(500) NULL,
    renewal_choice VARCHAR(20) NULL,
    renewal_responded_at DATETIME2 NULL,
    created_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contracts PRIMARY KEY (id),
    CONSTRAINT uq_contracts_code UNIQUE (contract_code),
    CONSTRAINT fk_contracts_apartment FOREIGN KEY (apartment_id) REFERENCES apartments (id),
    CONSTRAINT fk_contracts_tenant FOREIGN KEY (tenant_id) REFERENCES users (id),
    CONSTRAINT fk_contracts_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_contracts_renewal_of FOREIGN KEY (renewal_of_id) REFERENCES contracts (id),
    CONSTRAINT fk_contracts_signed_by FOREIGN KEY (signed_confirmed_by) REFERENCES users (id),
    CONSTRAINT fk_contracts_payment_by FOREIGN KEY (payment_confirmed_by) REFERENCES users (id),
    CONSTRAINT ck_contracts_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_contracts_term_unit CHECK (lease_term_unit IN (''MONTH'', ''YEAR'', ''CUSTOM'')),
    CONSTRAINT ck_contracts_term_value CHECK (lease_term_value > 0),
    CONSTRAINT ck_contracts_rent CHECK (monthly_rent > 0),
    CONSTRAINT ck_contracts_deposit CHECK (deposit_amount >= 0),
    CONSTRAINT ck_contracts_rates CHECK (electric_rate >= 0 AND water_rate >= 0),
    CONSTRAINT ck_contracts_status CHECK (status IN (''AWAITING'', ''SIGNED'', ''CANCELLED'')),
    CONSTRAINT ck_contracts_payment CHECK (payment_status IN (''UNPAID'', ''PAID'')),
    CONSTRAINT ck_contracts_paid_requires_signed CHECK (payment_status = ''UNPAID'' OR status IN (''SIGNED'', ''CANCELLED'')),
    CONSTRAINT ck_contracts_renewal CHECK (renewal_choice IS NULL OR renewal_choice IN (''REQUESTED'', ''DECLINED'')),
    CONSTRAINT ck_contracts_handover_dates CHECK (actual_move_out IS NULL OR actual_move_in IS NULL OR actual_move_out >= actual_move_in)
);
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_contracts_apartment_dates ON contracts (apartment_id, status, start_date, end_date);
';
    EXEC sys.sp_executesql N'CREATE INDEX idx_contracts_owner ON contracts (owner_id, status);
';
    EXEC sys.sp_executesql N'CREATE INDEX idx_contracts_tenant ON contracts (tenant_id, status);
';
    EXEC sys.sp_executesql N'
CREATE TABLE contract_status_history (
    id VARCHAR(36) NOT NULL,
    contract_id VARCHAR(36) NOT NULL,
    from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NOT NULL,
    from_payment_status VARCHAR(20) NULL,
    to_payment_status VARCHAR(20) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    note NVARCHAR(500) NULL,
    created_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_contract_status_history PRIMARY KEY (id),
    CONSTRAINT fk_history_contract FOREIGN KEY (contract_id) REFERENCES contracts (id),
    CONSTRAINT fk_history_actor FOREIGN KEY (actor_id) REFERENCES users (id)
);
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_contract_history_contract ON contract_status_history (contract_id, created_at);
';
    EXEC sys.sp_executesql N'
CREATE TABLE invoices (
    id VARCHAR(36) NOT NULL,
    invoice_code VARCHAR(32) NOT NULL,
    contract_id VARCHAR(36) NOT NULL,
    billing_month VARCHAR(7) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    days_in_period INT NOT NULL,
    month_basis_days INT NOT NULL,
    kind VARCHAR(30) NOT NULL DEFAULT ''MONTHLY'',
    status VARCHAR(20) NOT NULL DEFAULT ''UNPAID'',
    old_electric INT NULL,
    new_electric INT NULL,
    old_water INT NULL,
    new_water INT NULL,
    total_amount BIGINT NOT NULL,
    paid_at DATE NULL,
    confirmed_by VARCHAR(36) NULL,
    created_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_invoices PRIMARY KEY (id),
    CONSTRAINT uq_invoices_code UNIQUE (invoice_code),
    CONSTRAINT uq_invoices_contract_month_kind UNIQUE (contract_id, billing_month, kind),
    CONSTRAINT fk_invoices_contract FOREIGN KEY (contract_id) REFERENCES contracts (id),
    CONSTRAINT fk_invoices_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users (id),
    CONSTRAINT ck_invoices_kind CHECK (kind IN (''MONTHLY'', ''EARLY_MOVE_IN'')),
    CONSTRAINT ck_invoices_status CHECK (status IN (''UNPAID'', ''PAID'')),
    CONSTRAINT ck_invoices_basis_days CHECK (month_basis_days IN (30, 31)),
    CONSTRAINT ck_invoices_days CHECK (days_in_period > 0),
    CONSTRAINT ck_invoices_electric CHECK (old_electric IS NULL OR (old_electric >= 0 AND new_electric >= old_electric)),
    CONSTRAINT ck_invoices_water CHECK (old_water IS NULL OR (old_water >= 0 AND new_water >= old_water)),
    CONSTRAINT ck_invoices_total CHECK (total_amount >= 0)
);
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_invoices_contract ON invoices (contract_id, billing_month);
';
    EXEC sys.sp_executesql N'
CREATE TABLE invoice_lines (
    id VARCHAR(36) NOT NULL,
    invoice_id VARCHAR(36) NOT NULL,
    line_order INT NOT NULL,
    title NVARCHAR(250) NOT NULL,
    amount BIGINT NOT NULL,
    CONSTRAINT pk_invoice_lines PRIMARY KEY (id),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE,
    CONSTRAINT ck_invoice_lines_amount CHECK (amount >= 0)
);
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_invoice_lines_invoice ON invoice_lines (invoice_id, line_order);
';
    EXEC sys.sp_executesql N'
CREATE TABLE saved_apartments (
    user_id VARCHAR(36) NOT NULL,
    apartment_id VARCHAR(36) NOT NULL,
    saved_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_saved_apartments PRIMARY KEY (user_id, apartment_id),
    CONSTRAINT fk_saved_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_apartment FOREIGN KEY (apartment_id) REFERENCES apartments (id) ON DELETE CASCADE
);
';
    -- V2__seed_data.sql
    EXEC sys.sp_executesql N'-- ============================================================================
-- V2__seed_data.sql
-- Dữ liệu mẫu cho 2 chủ nhà (OWNER), 2 khách thuê (TENANT), 4 tòa nhà,
-- 6 căn hộ, 2 hợp đồng đã ký và 1 hóa đơn mẫu.
-- Mật khẩu mặc định cho tất cả tài khoản mẫu: AnCu@123
-- ============================================================================

INSERT INTO users (id, email, password_hash, full_name, role, phone, active) VALUES
(''o1'', ''thuha@ancu.vn'', ''$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a'', N''Trần Thu Hà'', ''OWNER'', ''0901000001'', 1),
(''o2'', ''giabao@ancu.vn'', ''$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a'', N''Phạm Gia Bảo'', ''OWNER'', ''0901000002'', 1),
(''t1'', ''minhanh@ancu.vn'', ''$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a'', N''Nguyễn Minh Anh'', ''TENANT'', ''0902000001'', 1),
(''t2'', ''hoangnam@ancu.vn'', ''$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a'', N''Lê Hoàng Nam'', ''TENANT'', ''0902000002'', 1);
';
    EXEC sys.sp_executesql N'
INSERT INTO buildings (id, owner_id, name, district, address, signing_location, electric_rate, water_rate) VALUES
(''b1'', ''o1'', N''The River Thảo Điền'', N''TP. Thủ Đức'', N''28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh'', N''Sảnh lễ tân The River Thảo Điền, 28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh'', 3500, 20000),
(''b2'', ''o2'', N''Lumière Residence'', N''Quận 7'', N''88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh'', N''Sảnh lễ tân Lumière Residence, 88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh'', 3500, 20000),
(''b3'', ''o1'', N''Urban Nest'', N''Bình Thạnh'', N''55 Nguyễn Hữu Cảnh, TP. Hồ Chí Minh'', N''Sảnh lễ tân Urban Nest, 55 Nguyễn Hữu Cảnh, TP. Hồ Chí Minh'', 3500, 20000),
(''b4'', ''o2'', N''The Greenhouse'', N''Quận 3'', N''46 Võ Văn Tần, TP. Hồ Chí Minh'', N''Sảnh lễ tân The Greenhouse, 46 Võ Văn Tần, TP. Hồ Chí Minh'', 3500, 20000);
';
    EXEC sys.sp_executesql N'
INSERT INTO apartments (id, building_id, room_code, title, monthly_price, area_m2, bedrooms, bathrooms, image_key, published, condition_status, description) VALUES
(''a1'', ''b1'', ''A-12.06'', N''Căn hộ The River Thảo Điền'', 12500000, 62.00, 2, 2, ''river'', 1, ''READY'', N''Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.''),
(''a2'', ''b2'', ''B-08.02'', N''Căn hộ Lumière Residence'', 9800000, 45.00, 1, 1, ''lumiere'', 1, ''READY'', N''Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.''),
(''a3'', ''b3'', ''C-18.03'', N''Căn hộ Urban Nest'', 15200000, 78.00, 3, 2, ''urban'', 1, ''READY'', N''Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.''),
(''a4'', ''b4'', ''D-07.01'', N''Căn hộ The Greenhouse'', 7900000, 38.00, 1, 1, ''greenhouse'', 1, ''READY'', N''Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.''),
(''a5'', ''b1'', ''A-05.02'', N''Căn hộ River Garden'', 11200000, 56.00, 2, 1, ''greenhouse'', 0, ''READY'', N''Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.''),
(''a6'', ''b2'', ''B-09.03'', N''Căn hộ Lumière Park'', 10400000, 52.00, 2, 1, ''urban'', 0, ''READY'', N''Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.'');
';
    EXEC sys.sp_executesql N'
INSERT INTO contracts (
    id, contract_code, apartment_id, tenant_id, owner_id, renewal_of_id,
    apartment_title, room_code, property_name, signing_location, terms_version,
    start_date, end_date, lease_term_unit, lease_term_value,
    monthly_rent, deposit_amount, electric_rate, water_rate,
    status, payment_status, appointment_at, signed_at, signed_confirmed_by,
    payment_confirmed_at, payment_confirmed_by, move_in_deadline, actual_move_in,
    deposit_forfeited, renewal_choice
) VALUES
(
    ''HD-1001'', ''HD-1001'', ''a5'', ''t1'', ''o1'', NULL,
    N''Căn hộ River Garden'', ''A-05.02'', N''The River Thảo Điền'',
    N''Sảnh lễ tân The River Thảo Điền, 28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh'', ''v1.0'',
    DATEADD(DAY, -160, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, 8, CAST(CURRENT_TIMESTAMP AS DATE)),
    ''MONTH'', 6,
    11200000, 11200000, 3500, 20000,
    ''SIGNED'', ''PAID'',
    DATEADD(DAY, -161, CURRENT_TIMESTAMP),
    DATEADD(DAY, -161, CURRENT_TIMESTAMP), ''o1'',
    DATEADD(DAY, -161, CURRENT_TIMESTAMP), ''o1'',
    DATEADD(DAY, -157, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -160, CAST(CURRENT_TIMESTAMP AS DATE)),
    0, NULL
),
(
    ''HD-1002'', ''HD-1002'', ''a6'', ''t2'', ''o2'', NULL,
    N''Căn hộ Lumière Park'', ''B-09.03'', N''Lumière Residence'',
    N''Sảnh lễ tân Lumière Residence, 88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh'', ''v1.0'',
    DATEADD(DAY, -190, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -9, CAST(CURRENT_TIMESTAMP AS DATE)),
    ''MONTH'', 6,
    10400000, 10400000, 3500, 20000,
    ''SIGNED'', ''PAID'',
    DATEADD(DAY, -192, CURRENT_TIMESTAMP),
    DATEADD(DAY, -192, CURRENT_TIMESTAMP), ''o2'',
    DATEADD(DAY, -192, CURRENT_TIMESTAMP), ''o2'',
    DATEADD(DAY, -187, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -190, CAST(CURRENT_TIMESTAMP AS DATE)),
    0, ''DECLINED''
);
';
    EXEC sys.sp_executesql N'
INSERT INTO contract_status_history (
    id, contract_id, from_status, to_status, from_payment_status, to_payment_status,
    action_type, actor_id, note
) VALUES
(''h-1001'', ''HD-1001'', ''AWAITING'', ''SIGNED'', ''UNPAID'', ''PAID'', ''SIGN_AND_DEPOSIT_CONFIRMED'', ''o1'', N''Người môi giới/Chủ nhà xác nhận khách đã ký tại địa điểm hẹn và đã thu tiền đặt cọc.''),
(''h-1002'', ''HD-1002'', ''AWAITING'', ''SIGNED'', ''UNPAID'', ''PAID'', ''SIGN_AND_DEPOSIT_CONFIRMED'', ''o2'', N''Người môi giới/Chủ nhà xác nhận khách đã ký tại địa điểm hẹn và đã thu tiền đặt cọc.'');
';
    EXEC sys.sp_executesql N'
INSERT INTO invoices (
    id, invoice_code, contract_id, billing_month, period_start, period_end,
    days_in_period, month_basis_days, kind, status,
    old_electric, new_electric, old_water, new_water, total_amount
) VALUES (
    ''INV-1001'', ''HĐ-1001'', ''HD-1001'',
    SUBSTRING(CAST(DATEADD(DAY, -31, CAST(CURRENT_TIMESTAMP AS DATE)) AS VARCHAR(10)), 1, 7),
    DATEADD(DAY, -38, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -9, CAST(CURRENT_TIMESTAMP AS DATE)),
    30, 30, ''MONTHLY'', ''UNPAID'',
    661, 785, 37, 41, 11714000
);
';
    EXEC sys.sp_executesql N'
INSERT INTO invoice_lines (id, invoice_id, line_order, title, amount) VALUES
(''il-1001-1'', ''INV-1001'', 1, N''Tiền thuê'', 11200000),
(''il-1001-2'', ''INV-1001'', 2, N''Tiền điện (124 kWh)'', 434000),
(''il-1001-3'', ''INV-1001'', 3, N''Tiền nước (4 m³)'', 80000);
';
    -- V3__automatic_no_show_cancellation.sql
    EXEC sys.sp_executesql N'ALTER TABLE contract_status_history ALTER COLUMN actor_id VARCHAR(36) NULL;
';
    EXEC sys.sp_executesql N'
CREATE INDEX idx_contracts_no_show ON contracts (status, actual_move_in, move_in_deadline);
';
    -- V4__verified_accounts.sql
    EXEC sys.sp_executesql N'ALTER TABLE users ADD email_verified BIT NOT NULL DEFAULT 0;
';
    EXEC sys.sp_executesql N'ALTER TABLE users ADD is_demo BIT NOT NULL DEFAULT 0;
';
    EXEC sys.sp_executesql N'ALTER TABLE users ADD auth_version INT NOT NULL DEFAULT 0;
';
    EXEC sys.sp_executesql N'ALTER TABLE users ADD address NVARCHAR(300) NULL;
';
    EXEC sys.sp_executesql N'ALTER TABLE users ADD date_of_birth DATE NULL;
';
    EXEC sys.sp_executesql N'
UPDATE users SET is_demo = 1 WHERE id IN (''o1'', ''o2'', ''t1'', ''t2'');
';
    EXEC sys.sp_executesql N'
-- One current challenge per email. Reissuing replaces the previous code and id.
CREATE TABLE email_otp_challenges (
    email VARCHAR(150) NOT NULL,
    id VARCHAR(36) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    user_id VARCHAR(36) NULL,
    auth_version INT NULL,
    code_hash VARCHAR(255) NOT NULL,
    pending_name NVARCHAR(150) NULL,
    pending_password_hash VARCHAR(255) NULL,
    expires_at DATETIME2 NOT NULL,
    next_send_at DATETIME2 NOT NULL,
    window_start DATETIME2 NOT NULL,
    sent_count INT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed BIT NOT NULL DEFAULT 0,
    CONSTRAINT pk_email_otp_challenges PRIMARY KEY (email),
    CONSTRAINT uq_email_otp_challenge_id UNIQUE (id),
    CONSTRAINT ck_email_otp_purpose CHECK (purpose IN (''REGISTER'', ''CHANGE_PASSWORD'', ''RESET_PASSWORD''))
);
';
    EXEC sys.sp_executesql N'
CREATE TABLE email_otp_rate_limits (
    rate_key VARCHAR(64) NOT NULL,
    window_start DATETIME2 NOT NULL,
    request_count INT NOT NULL,
    CONSTRAINT pk_email_otp_rate_limits PRIMARY KEY (rate_key)
);
';
    IF (SELECT COUNT(*) FROM sys.tables WHERE schema_id = SCHEMA_ID(N'dbo') AND name IN (
        'users','buildings','apartments','contracts','contract_status_history',
        'invoices','invoice_lines','saved_apartments','email_otp_challenges','email_otp_rate_limits')) <> 10
        THROW 51002, N'Initialization did not create all ten project tables.', 1;
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;

SELECT @@SERVERNAME AS server_name, DB_NAME() AS database_name;
SELECT SCHEMA_NAME(schema_id) AS schema_name, name AS table_name
FROM sys.tables WHERE is_ms_shipped = 0 ORDER BY name;
