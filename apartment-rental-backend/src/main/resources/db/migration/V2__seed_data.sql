-- ============================================================================
-- V2__seed_data.sql
-- Dữ liệu mẫu cho 2 chủ nhà (OWNER), 2 khách thuê (TENANT), 4 tòa nhà,
-- 6 căn hộ, 2 hợp đồng đã ký và 1 hóa đơn mẫu.
-- Mật khẩu mặc định cho tất cả tài khoản mẫu: AnCu@123
-- ============================================================================

INSERT INTO users (id, email, password_hash, full_name, role, phone, active) VALUES
('o1', 'thuha@ancu.vn', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a', N'Trần Thu Hà', 'OWNER', '0901000001', 1),
('o2', 'giabao@ancu.vn', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a', N'Phạm Gia Bảo', 'OWNER', '0901000002', 1),
('t1', 'minhanh@ancu.vn', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a', N'Nguyễn Minh Anh', 'TENANT', '0902000001', 1),
('t2', 'hoangnam@ancu.vn', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.AQubh4a', N'Lê Hoàng Nam', 'TENANT', '0902000002', 1);

INSERT INTO buildings (id, owner_id, name, district, address, signing_location, electric_rate, water_rate) VALUES
('b1', 'o1', N'The River Thảo Điền', N'TP. Thủ Đức', N'28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh', N'Sảnh lễ tân The River Thảo Điền, 28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh', 3500, 20000),
('b2', 'o2', N'Lumière Residence', N'Quận 7', N'88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh', N'Sảnh lễ tân Lumière Residence, 88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh', 3500, 20000),
('b3', 'o1', N'Urban Nest', N'Bình Thạnh', N'55 Nguyễn Hữu Cảnh, TP. Hồ Chí Minh', N'Sảnh lễ tân Urban Nest, 55 Nguyễn Hữu Cảnh, TP. Hồ Chí Minh', 3500, 20000),
('b4', 'o2', N'The Greenhouse', N'Quận 3', N'46 Võ Văn Tần, TP. Hồ Chí Minh', N'Sảnh lễ tân The Greenhouse, 46 Võ Văn Tần, TP. Hồ Chí Minh', 3500, 20000);

INSERT INTO apartments (id, building_id, room_code, title, monthly_price, area_m2, bedrooms, bathrooms, image_key, published, condition_status, description) VALUES
('a1', 'b1', 'A-12.06', N'Căn hộ The River Thảo Điền', 12500000, 62.00, 2, 2, 'river', 1, 'READY', N'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.'),
('a2', 'b2', 'B-08.02', N'Căn hộ Lumière Residence', 9800000, 45.00, 1, 1, 'lumiere', 1, 'READY', N'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.'),
('a3', 'b3', 'C-18.03', N'Căn hộ Urban Nest', 15200000, 78.00, 3, 2, 'urban', 1, 'READY', N'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.'),
('a4', 'b4', 'D-07.01', N'Căn hộ The Greenhouse', 7900000, 38.00, 1, 1, 'greenhouse', 1, 'READY', N'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.'),
('a5', 'b1', 'A-05.02', N'Căn hộ River Garden', 11200000, 56.00, 2, 1, 'greenhouse', 0, 'READY', N'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.'),
('a6', 'b2', 'B-09.03', N'Căn hộ Lumière Park', 10400000, 52.00, 2, 1, 'urban', 0, 'READY', N'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.');

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
    'HD-1001', 'HD-1001', 'a5', 't1', 'o1', NULL,
    N'Căn hộ River Garden', 'A-05.02', N'The River Thảo Điền',
    N'Sảnh lễ tân The River Thảo Điền, 28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh', 'v1.0',
    DATEADD(DAY, -160, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, 8, CAST(CURRENT_TIMESTAMP AS DATE)),
    'MONTH', 6,
    11200000, 11200000, 3500, 20000,
    'SIGNED', 'PAID',
    DATEADD(DAY, -161, CURRENT_TIMESTAMP),
    DATEADD(DAY, -161, CURRENT_TIMESTAMP), 'o1',
    DATEADD(DAY, -161, CURRENT_TIMESTAMP), 'o1',
    DATEADD(DAY, -157, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -160, CAST(CURRENT_TIMESTAMP AS DATE)),
    0, NULL
),
(
    'HD-1002', 'HD-1002', 'a6', 't2', 'o2', NULL,
    N'Căn hộ Lumière Park', 'B-09.03', N'Lumière Residence',
    N'Sảnh lễ tân Lumière Residence, 88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh', 'v1.0',
    DATEADD(DAY, -190, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -9, CAST(CURRENT_TIMESTAMP AS DATE)),
    'MONTH', 6,
    10400000, 10400000, 3500, 20000,
    'SIGNED', 'PAID',
    DATEADD(DAY, -192, CURRENT_TIMESTAMP),
    DATEADD(DAY, -192, CURRENT_TIMESTAMP), 'o2',
    DATEADD(DAY, -192, CURRENT_TIMESTAMP), 'o2',
    DATEADD(DAY, -187, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -190, CAST(CURRENT_TIMESTAMP AS DATE)),
    0, 'DECLINED'
);

INSERT INTO contract_status_history (
    id, contract_id, from_status, to_status, from_payment_status, to_payment_status,
    action_type, actor_id, note
) VALUES
('h-1001', 'HD-1001', 'AWAITING', 'SIGNED', 'UNPAID', 'PAID', 'SIGN_AND_DEPOSIT_CONFIRMED', 'o1', N'Người môi giới/Chủ nhà xác nhận khách đã ký tại địa điểm hẹn và đã thu tiền đặt cọc.'),
('h-1002', 'HD-1002', 'AWAITING', 'SIGNED', 'UNPAID', 'PAID', 'SIGN_AND_DEPOSIT_CONFIRMED', 'o2', N'Người môi giới/Chủ nhà xác nhận khách đã ký tại địa điểm hẹn và đã thu tiền đặt cọc.');

INSERT INTO invoices (
    id, invoice_code, contract_id, billing_month, period_start, period_end,
    days_in_period, month_basis_days, kind, status,
    old_electric, new_electric, old_water, new_water, total_amount
) VALUES (
    'INV-1001', 'HĐ-1001', 'HD-1001',
    SUBSTRING(CAST(DATEADD(DAY, -31, CAST(CURRENT_TIMESTAMP AS DATE)) AS VARCHAR(10)), 1, 7),
    DATEADD(DAY, -38, CAST(CURRENT_TIMESTAMP AS DATE)),
    DATEADD(DAY, -9, CAST(CURRENT_TIMESTAMP AS DATE)),
    30, 30, 'MONTHLY', 'UNPAID',
    661, 785, 37, 41, 11714000
);

INSERT INTO invoice_lines (id, invoice_id, line_order, title, amount) VALUES
('il-1001-1', 'INV-1001', 1, N'Tiền thuê', 11200000),
('il-1001-2', 'INV-1001', 2, N'Tiền điện (124 kWh)', 434000),
('il-1001-3', 'INV-1001', 3, N'Tiền nước (4 m³)', 80000);
