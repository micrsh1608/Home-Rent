# Cập nhật Thiết kế Hệ thống (ERD, Use Case, Activity & Sequence Diagrams)

> **Lưu ý phạm vi:** Tài liệu này cập nhật thiết kế hệ thống **An Cư Rental** khớp 100% với source code và lược đồ cơ sở dữ liệu sau khi khắc phục P0/P1/P2 (phiên bản lược đồ `V7`). Tài liệu này là bản bổ sung kỹ thuật trong repository, **không thay thế** tài liệu báo cáo đồ án chính thức nộp cho giảng viên (nếu báo cáo đó được lưu bên ngoài repository).

## 1. Biểu đồ thực thể liên kết (ERD — Lược đồ V7)

```mermaid
erDiagram
    USERS ||--o{ BUILDINGS : owns
    USERS ||--o{ CONTRACTS : "signs_as_tenant"
    USERS ||--o{ CONTRACTS : "manages_as_owner"
    USERS ||--o{ SAVED_APARTMENTS : saves
    BUILDINGS ||--o{ APARTMENTS : contains
    APARTMENTS ||--o{ CONTRACTS : leased_by
    APARTMENTS ||--o{ SAVED_APARTMENTS : saved_in
    CONTRACTS ||--o{ CONTRACTS : "renewal_of"
    CONTRACTS ||--o{ CONTRACT_STATUS_HISTORY : audits
    CONTRACTS ||--o{ INVOICES : billed_by
    INVOICES ||--o{ INVOICE_LINES : has_lines

    USERS {
        varchar id PK
        varchar email UK
        varchar password_hash
        nvarchar full_name
        varchar role "OWNER | TENANT"
        bit email_verified
        bit is_demo
        int auth_version
        bit active
    }

    BUILDINGS {
        varchar id PK
        varchar owner_id FK
        nvarchar name
        nvarchar district
        nvarchar address
        nvarchar signing_location
        bigint electric_rate
        bigint water_rate
    }

    APARTMENTS {
        varchar id PK
        varchar building_id FK
        varchar room_code
        nvarchar title
        bigint monthly_price
        decimal area_m2
        int bedrooms
        int bathrooms
        bit published
        varchar condition_status "READY | MAINTENANCE"
        int version "Optimistic lock V5"
    }

    CONTRACTS {
        varchar id PK
        varchar contract_code UK
        varchar apartment_id FK
        varchar tenant_id FK
        varchar owner_id FK
        varchar renewal_of_id FK "Unique non-cancelled V7"
        date start_date
        date end_date
        bigint monthly_rent
        bigint deposit_amount
        bigint deposit_received_amount "V5 P0-02"
        varchar deposit_verification_status "NOT_APPLICABLE|VERIFIED|LEGACY_UNVERIFIED"
        varchar status "AWAITING|SIGNED|CANCELLED"
        varchar payment_status "UNPAID|PAID"
        datetime2 appointment_at
        date move_in_deadline
        date actual_move_in
        date actual_move_out
        int handover_electric_reading "V5 P0-04"
        int handover_water_reading "V5 P0-04"
        varchar handover_meter_status "NOT_RECORDED|VERIFIED|LEGACY_UNVERIFIED"
        bit deposit_forfeited
        varchar renewal_choice "REQUESTED|DECLINED"
        int version "Optimistic lock V5"
    }

    CONTRACT_STATUS_HISTORY {
        varchar id PK "Monotonic time-ordered"
        varchar contract_id FK
        varchar from_status
        varchar to_status
        varchar action_type
        varchar actor_id FK "NULL = System"
        nvarchar note
        datetime2 created_at
    }

    INVOICES {
        varchar id PK
        varchar invoice_code UK
        varchar contract_id FK
        int period_index "V5 P0-05 Unique per contract MONTHLY"
        varchar billing_month
        date period_start
        date period_end
        varchar kind "MONTHLY|EARLY_MOVE_IN"
        varchar status "UNPAID|PAID"
        int old_electric
        int new_electric
        int old_water
        int new_water
        varchar meter_verification_status "V5 P0-04"
        bigint total_amount
        date paid_at
        varchar confirmed_by FK
    }

    INVOICE_LINES {
        varchar id PK
        varchar invoice_id FK
        int line_order
        nvarchar title
        bigint amount
    }

    SAVED_APARTMENTS {
        varchar user_id PK_FK
        varchar apartment_id PK_FK
        datetime2 saved_at
    }
```

## 2. Biểu đồ Tuần tự (Sequence Diagrams) các luồng trọng yếu

### 2.1. Xác nhận ký & thu tiền cọc với khóa CSDL chống trùng lịch (P0-01 & P0-02)

```mermaid
sequenceDiagram
    actor Owner as Chủ nhà (OWNER)
    participant UI as React UI (App Mode)
    participant API as ContractController + CSRF Filter
    participant Svc as ContractLifecycleService
    participant Lock as DatabaseLockService
    participant DB as SQL Server / H2

    Owner->>UI: Nhập số tiền cọc thực nhận + tích 2 xác nhận
    UI->>API: POST /api/contracts/{id}/confirm-sign-and-deposit (Cookie + X-CSRF-Token)
    API->>API: Kiểm tra Session, Role OWNER & CSRF Token
    API->>Svc: confirmSignAndDepositByOwner(actor, id, req)
    Svc->>DB: BEGIN TRANSACTION
    Svc->>Lock: lockApartmentAndContract(id)
    Lock->>DB: SELECT id FROM apartments WITH (UPDLOCK, HOLDLOCK) WHERE id = ?
    Lock->>DB: SELECT id FROM contracts WITH (UPDLOCK, HOLDLOCK) WHERE id = ?
    Svc->>DB: Đọc lại hợp đồng & trạng thái căn hộ dưới khóa
    Svc->>Svc: Kiểm tra lịch hẹn, receivedAmount >= deposit, không bảo trì
    Svc->>DB: Kiểm tra trùng lịch SIGNED (inclusive, xét actual_move_in sớm)
    alt Trùng lịch SIGNED khác
        Svc-->>API: 409 OVERLAPPING_SIGNED_CONTRACT (ROLLBACK, giữ nguyên AWAITING)
    else Hợp lệ
        Svc->>DB: UPDATE contracts SET status='SIGNED', payment='PAID', deposit_received_amount=?, deposit_verification_status='VERIFIED', version=version+1 WHERE id=? AND status='AWAITING' AND version=?
        Svc->>DB: UPDATE apartments SET published=0, version=version+1 WHERE id=?
        Svc->>DB: INSERT contract_status_history (kèm số cọc thực nhận)
        Svc->>DB: COMMIT (nhả khóa UPDLOCK/HOLDLOCK)
        Svc-->>UI: 200 OK ContractDto
    end
```

### 2.2. Bàn giao nhận/trả phòng & Bảo toàn hóa đơn phạt nhận phòng sớm (P0-04, P0-06, P0-07)

```mermaid
sequenceDiagram
    actor Owner as Chủ nhà (OWNER)
    participant Svc as ContractLifecycleService
    participant Lock as DatabaseLockService
    participant DB as SQL Server / H2

    Owner->>Svc: POST /api/contracts/{id}/check-in (actualMoveIn, initialElectric, initialWater)
    Svc->>DB: BEGIN TRANSACTION
    Svc->>Lock: lockApartmentAndContract(id)
    Svc->>Svc: Kiểm tra ServerClock (không ngày tương lai, <= start_date + 3, chỉ số >= 0)
    alt actualMoveIn < start_date
        Svc->>DB: Kiểm tra trùng lịch giai đoạn nhận sớm
        Svc->>DB: Upsert hóa đơn EARLY_MOVE_IN (giữ nguyên nếu PAID; cập nhật tại chỗ nếu UNPAID)
    end
    Svc->>DB: UPDATE contracts SET actual_move_in=?, handover_electric_reading=?, handover_water_reading=?, handover_meter_status='VERIFIED', version=version+1
    Svc->>DB: INSERT contract_status_history & COMMIT

    Note over Owner,DB: Sau đó khi khách trả phòng
    Owner->>Svc: POST /api/contracts/{id}/check-out (actualMoveOut)
    Svc->>DB: BEGIN TRANSACTION + lockApartmentAndContract(id)
    Svc->>Svc: Giữ nguyên actual_move_in & handover meters; kiểm tra actualMoveOut >= actualMoveIn
    Svc->>Svc: Hóa đơn EARLY_MOVE_IN đã PAID được giữ nguyên 100% (không DELETE)
    Svc->>DB: UPDATE contracts SET actual_move_out=?, version=version+1 & COMMIT
```

### 2.3. Lập hóa đơn tuần tự & xác nhận thu tiền Idempotent (P0-04, P0-05, P1-12)

```mermaid
sequenceDiagram
    actor Owner as Chủ nhà (OWNER)
    participant UI as OwnerBilling UI
    participant Svc as ContractLifecycleService
    participant DB as SQL Server / H2

    UI->>Svc: GET /api/contracts/{id}/next-invoice-preview
    Svc-->>UI: Kỳ kế tiếp (periodIndex, billingMonth) + Điện/Nước cũ (read-only)
    Owner->>UI: Nhập chỉ số Điện mới, Nước mới
    UI->>Svc: POST /api/contracts/{id}/invoices
    Svc->>DB: BEGIN TRANSACTION + lockContract(id)
    Svc->>Svc: resolveTargetPeriod từ LeaseRentCalculator (từ chối lệch month/periodIndex)
    Svc->>DB: Kiểm tra tuần tự (Kỳ 1 <- handover VERIFIED; Kỳ k <- Kỳ k-1)
    Svc->>DB: INSERT invoices (period_index, meter_verification_status='VERIFIED') + 3 invoice_lines
    Svc->>DB: COMMIT

    Owner->>Svc: POST /api/invoices/{invoiceId}/confirm-paid (có thể gọi lặp/đồng thời)
    Svc->>DB: BEGIN TRANSACTION + lockContract(contractId)
    Svc->>DB: UPDATE invoices SET status='PAID', paid_at=serverToday, confirmed_by=actorId WHERE id=? AND status='UNPAID'
    Svc->>DB: COMMIT -> Trả bản ghi đã lưu (bảo toàn paid_at & confirmed_by đầu tiên)
```

## 3. Cập nhật Use Case & Quy tắc Hoạt động (Activity Rules)

| Use Case | Tác nhân | Điều kiện tiên quyết mới sau khắc phục | Kết quả đảm bảo |
|---|---|---|---|
| **UC-01 Yêu cầu thuê** | `TENANT` | Căn hộ đang mở & sẵn sàng; giá và cọc do server tự lấy từ CSDL | Chặn gian lận giá/cọc; không tạo trùng với yêu cầu đang mở của chính khách |
| **UC-02 Ký hợp đồng & thu cọc** | `OWNER` | Có lịch hẹn ký, xác nhận ký tại điểm hẹn, nhập số cọc thực nhận `>= deposit` | Khóa CSDL chống 2 hợp đồng `SIGNED` trùng lịch; lưu `deposit_received_amount` |
| **UC-03 Hủy quá hạn 3 ngày (No-Show)** | `OWNER` / `Hệ thống` | `ServerClock.today() > start_date + 3` và `actual_move_in IS NULL` | Dùng khóa chung với Check-in; không thể giả mạo ngày `today` từ client |
| **UC-04 Nhận phòng (Check-in)** | `OWNER` | Hợp đồng `SIGNED`, nhập `actualMoveIn`, `initialElectric >= 0`, `initialWater >= 0` | Lưu mốc chỉ số điện/nước ban đầu; phát sinh đúng 1 phạt nhận sớm nếu `actualMoveIn < start_date` |
| **UC-05 Trả phòng (Check-out)** | `OWNER` | Đã có `actualMoveIn`, `actualMoveOut >= actualMoveIn` và `<= ServerClock.today()` | Bảo toàn `actualMoveIn`, chỉ số bàn giao và hóa đơn phạt đã `PAID`; tiền thuê không giảm |
| **UC-06 Lập hóa đơn theo kỳ** | `OWNER` | Đã bàn giao xác minh (kỳ 1) hoặc đã lập kỳ liền trước (kỳ `k > 1`) | Duy nhất 1 hóa đơn/`(contract_id, period_index)`; chỉ số liên tục không thể sửa lùi |
| **UC-07 Xác nhận thu hóa đơn** | `OWNER` | Hóa đơn thuộc quyền sở hữu của chủ nhà | Idempotent & nguyên tử; giữ nguyên `paid_at` và `confirmed_by` ban đầu |
| **UC-08 Gia hạn hợp đồng** | `TENANT` → `OWNER` | Trong mốc 30 ngày cuối; mỗi hợp đồng cũ chỉ có tối đa 1 hợp đồng gia hạn chưa hủy | Bảo toàn hợp đồng cũ; khóa + filtered unique index chống tạo 2 hợp đồng gia hạn song song |
