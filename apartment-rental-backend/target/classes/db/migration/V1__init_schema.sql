-- ============================================================================
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
    CONSTRAINT ck_users_role CHECK (role IN ('OWNER', 'TENANT'))
);

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

CREATE INDEX idx_buildings_owner ON buildings (owner_id);
CREATE INDEX idx_buildings_district ON buildings (district);

CREATE TABLE apartments (
    id VARCHAR(36) NOT NULL,
    building_id VARCHAR(36) NOT NULL,
    room_code VARCHAR(50) NOT NULL,
    title NVARCHAR(250) NOT NULL,
    monthly_price BIGINT NOT NULL,
    area_m2 DECIMAL(8, 2) NOT NULL,
    bedrooms INT NOT NULL,
    bathrooms INT NOT NULL,
    image_key VARCHAR(100) NOT NULL DEFAULT 'greenhouse',
    custom_image NVARCHAR(MAX) NULL,
    published BIT NOT NULL DEFAULT 1,
    condition_status VARCHAR(20) NOT NULL DEFAULT 'READY',
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
    CONSTRAINT ck_apartments_condition CHECK (condition_status IN ('READY', 'MAINTENANCE'))
);

CREATE INDEX idx_apartments_building ON apartments (building_id);
CREATE INDEX idx_apartments_public_search ON apartments (published, condition_status, monthly_price, bedrooms);

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
    terms_version VARCHAR(20) NOT NULL DEFAULT 'v1.0',
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    lease_term_unit VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',
    lease_term_value INT NOT NULL DEFAULT 6,
    monthly_rent BIGINT NOT NULL,
    deposit_amount BIGINT NOT NULL,
    electric_rate BIGINT NOT NULL,
    water_rate BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AWAITING',
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
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
    CONSTRAINT ck_contracts_term_unit CHECK (lease_term_unit IN ('MONTH', 'YEAR', 'CUSTOM')),
    CONSTRAINT ck_contracts_term_value CHECK (lease_term_value > 0),
    CONSTRAINT ck_contracts_rent CHECK (monthly_rent > 0),
    CONSTRAINT ck_contracts_deposit CHECK (deposit_amount >= 0),
    CONSTRAINT ck_contracts_rates CHECK (electric_rate >= 0 AND water_rate >= 0),
    CONSTRAINT ck_contracts_status CHECK (status IN ('AWAITING', 'SIGNED', 'CANCELLED')),
    CONSTRAINT ck_contracts_payment CHECK (payment_status IN ('UNPAID', 'PAID')),
    CONSTRAINT ck_contracts_paid_requires_signed CHECK (payment_status = 'UNPAID' OR status IN ('SIGNED', 'CANCELLED')),
    CONSTRAINT ck_contracts_renewal CHECK (renewal_choice IS NULL OR renewal_choice IN ('REQUESTED', 'DECLINED')),
    CONSTRAINT ck_contracts_handover_dates CHECK (actual_move_out IS NULL OR actual_move_in IS NULL OR actual_move_out >= actual_move_in)
);

CREATE INDEX idx_contracts_apartment_dates ON contracts (apartment_id, status, start_date, end_date);
CREATE INDEX idx_contracts_owner ON contracts (owner_id, status);
CREATE INDEX idx_contracts_tenant ON contracts (tenant_id, status);

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

CREATE INDEX idx_contract_history_contract ON contract_status_history (contract_id, created_at);

CREATE TABLE invoices (
    id VARCHAR(36) NOT NULL,
    invoice_code VARCHAR(32) NOT NULL,
    contract_id VARCHAR(36) NOT NULL,
    billing_month VARCHAR(7) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    days_in_period INT NOT NULL,
    month_basis_days INT NOT NULL,
    kind VARCHAR(30) NOT NULL DEFAULT 'MONTHLY',
    status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
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
    CONSTRAINT ck_invoices_kind CHECK (kind IN ('MONTHLY', 'EARLY_MOVE_IN')),
    CONSTRAINT ck_invoices_status CHECK (status IN ('UNPAID', 'PAID')),
    CONSTRAINT ck_invoices_basis_days CHECK (month_basis_days IN (30, 31)),
    CONSTRAINT ck_invoices_days CHECK (days_in_period > 0),
    CONSTRAINT ck_invoices_electric CHECK (old_electric IS NULL OR (old_electric >= 0 AND new_electric >= old_electric)),
    CONSTRAINT ck_invoices_water CHECK (old_water IS NULL OR (old_water >= 0 AND new_water >= old_water)),
    CONSTRAINT ck_invoices_total CHECK (total_amount >= 0)
);

CREATE INDEX idx_invoices_contract ON invoices (contract_id, billing_month);

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

CREATE INDEX idx_invoice_lines_invoice ON invoice_lines (invoice_id, line_order);

CREATE TABLE saved_apartments (
    user_id VARCHAR(36) NOT NULL,
    apartment_id VARCHAR(36) NOT NULL,
    saved_at DATETIME2 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_saved_apartments PRIMARY KEY (user_id, apartment_id),
    CONSTRAINT fk_saved_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_apartment FOREIGN KEY (apartment_id) REFERENCES apartments (id) ON DELETE CASCADE
);
