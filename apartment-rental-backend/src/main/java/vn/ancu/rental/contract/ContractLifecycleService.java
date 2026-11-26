package vn.ancu.rental.contract;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ancu.rental.apartment.ApartmentDto;
import vn.ancu.rental.apartment.OwnerPropertyService;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.DatabaseLockService;
import vn.ancu.rental.common.ServerClock;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ContractLifecycleService {

    private static final AtomicLong HISTORY_SEQ = new AtomicLong(0);

    public static final long MAX_MONTHLY_RENT = LeaseRentCalculator.MAX_MONTHLY_RENT;
    public static final long MAX_DEPOSIT = 100_000_000_000L;
    public static final int MAX_METER_READING = 10_000_000;
    public static final int MAX_NOTE_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseLockService lockService;
    private final ServerClock serverClock;
    private final TransactionTemplate perItemTransactionTemplate;

    public ContractLifecycleService(
            JdbcTemplate jdbcTemplate,
            DatabaseLockService lockService,
            ServerClock serverClock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.lockService = lockService;
        this.serverClock = serverClock;
        this.perItemTransactionTemplate = new TransactionTemplate(transactionManager);
        this.perItemTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public record CreateContractRequest(
            String apartmentId,
            String tenantId,
            LocalDate startDate,
            LocalDate endDate,
            String leaseTermUnit,
            Integer leaseTermValue,
            Long rent,
            Long deposit,
            String renewalOf,
            LocalDateTime appointment
    ) {
        public CreateContractRequest(
                String apartmentId,
                String tenantId,
                LocalDate startDate,
                LocalDate endDate,
                String leaseTermUnit,
                Integer leaseTermValue,
                Long rent,
                Long deposit,
                String renewalOf
        ) {
            this(apartmentId, tenantId, startDate, endDate, leaseTermUnit, leaseTermValue, rent, deposit, renewalOf, null);
        }
    }

    public record UpdateContractRequest(
            LocalDate startDate,
            LocalDate endDate,
            String leaseTermUnit,
            Integer leaseTermValue,
            Long rent,
            Long deposit,
            LocalDateTime appointment
    ) {
        public UpdateContractRequest(
                LocalDate startDate,
                LocalDate endDate,
                String leaseTermUnit,
                Integer leaseTermValue,
                Long rent,
                LocalDateTime appointment
        ) {
            this(startDate, endDate, leaseTermUnit, leaseTermValue, rent, null, appointment);
        }
    }

    public record CancelAwaitingContractRequest(String reason) {
    }

    public record ScheduleAppointmentRequest(LocalDateTime appointment) {
    }

    public record ConfirmSignAndDepositRequest(
            Boolean signedAtLocation,
            Boolean depositReceived,
            Long receivedAmount,
            String note
    ) {
    }

    public record CheckInRequest(
            LocalDate actualMoveIn,
            Integer initialElectric,
            Integer initialWater
    ) {
    }

    public record CheckOutRequest(LocalDate actualMoveOut) {
    }

    public record HandoverRequest(
            LocalDate actualMoveIn,
            LocalDate actualMoveOut,
            Integer initialElectric,
            Integer initialWater
    ) {
        public HandoverRequest(LocalDate actualMoveIn, LocalDate actualMoveOut) {
            this(actualMoveIn, actualMoveOut, null, null);
        }
    }

    public record RenewalResponseRequest(String choice) {
    }

    public record CreateInvoiceRequest(
            String month,
            Integer periodIndex,
            Integer oldElectric,
            Integer newElectric,
            Integer oldWater,
            Integer newWater
    ) {
    }

    public List<ContractDto> listContractsForActor(AuthUser actor) {
        LocalDate today = serverClock.today();
        String column = "OWNER".equalsIgnoreCase(actor.role()) ? "c.owner_id" : "c.tenant_id";
        return jdbcTemplate.query(
                BASE_CONTRACT_SELECT + " WHERE " + column + " = ? ORDER BY c.created_at DESC, c.id DESC",
                contractRowMapper(today),
                actor.id()
        );
    }

    public ContractDto getContractForActor(AuthUser actor, String contractId) {
        return getContractForActorInternal(actor, contractId, serverClock.today());
    }

    private ContractDto getContractForActorInternal(AuthUser actor, String contractId, LocalDate today) {
        List<ContractDto> list = jdbcTemplate.query(
                BASE_CONTRACT_SELECT + " WHERE c.id = ?",
                contractRowMapper(today),
                contractId
        );
        if (list.isEmpty()) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        ContractDto contract = list.get(0);
        boolean authorized = "OWNER".equalsIgnoreCase(actor.role())
                ? contract.ownerId().equals(actor.id())
                : contract.tenantId().equals(actor.id());
        if (!authorized) {
            throw AppException.forbidden("Bạn không có quyền thao tác với hợp đồng này.");
        }
        return contract;
    }

    public List<ContractHistoryDto> getContractHistory(AuthUser actor, String contractId) {
        getContractForActor(actor, contractId);
        return jdbcTemplate.query(
                """
                SELECT h.id, h.contract_id, h.from_status, h.to_status,
                       h.from_payment_status, h.to_payment_status, h.action_type,
                       h.actor_id, u.full_name AS actor_name, h.note, h.created_at
                FROM contract_status_history h
                LEFT JOIN users u ON u.id = h.actor_id
                WHERE h.contract_id = ?
                ORDER BY h.created_at ASC, h.id ASC
                """,
                (rs, rowNum) -> new ContractHistoryDto(
                        rs.getString("id"),
                        rs.getString("contract_id"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("from_payment_status"),
                        rs.getString("to_payment_status"),
                        rs.getString("action_type"),
                        rs.getString("actor_id"),
                        rs.getString("actor_id") == null ? "Hệ thống" : rs.getString("actor_name"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ),
                contractId
        );
    }

    @Transactional
    public ContractDto createContract(AuthUser actor, CreateContractRequest req) {
        LocalDate today = serverClock.today();
        if (req == null || req.apartmentId() == null || req.apartmentId().isBlank() || req.startDate() == null) {
            throw AppException.badRequest("INVALID_CONTRACT", "Kiểm tra lại căn hộ và ngày bắt đầu hợp đồng.");
        }

        // Level 1 lock: serialize all apartment occupancy/renewal/maintenance transitions
        if (!lockService.lockApartment(req.apartmentId())) {
            throw AppException.notFound("Căn hộ không tồn tại.");
        }

        String renewalOfId = (req.renewalOf() != null && !req.renewalOf().isBlank()) ? req.renewalOf().trim() : null;
        if (renewalOfId != null) {
            if (!"OWNER".equalsIgnoreCase(actor.role())) {
                throw AppException.forbidden("Chỉ chủ nhà mới có quyền lập hợp đồng gia hạn.");
            }
            // Level 2 lock: lock base contract after apartment lock (canonical lock order)
            if (!lockService.lockContract(renewalOfId)) {
                throw AppException.notFound("Không tìm thấy hợp đồng gốc cần gia hạn.");
            }
        }

        Date sqlToday = Date.valueOf(today);
        List<ApartmentDto> units = jdbcTemplate.query(
                OwnerPropertyService.BASE_APARTMENT_SELECT + " WHERE a.id = ?",
                OwnerPropertyService.apartmentRowMapper(),
                sqlToday, req.apartmentId()
        );
        if (units.isEmpty()) {
            throw AppException.notFound("Căn hộ không tồn tại.");
        }
        ApartmentDto unit = units.get(0);
        if ("maintenance".equalsIgnoreCase(unit.condition())) {
            throw AppException.conflict("APARTMENT_MAINTENANCE", "Căn hộ đang bảo trì, không khả dụng để tạo hợp đồng.");
        }

        String tenantId;
        long monthlyRent;
        long depositAmount;

        if ("OWNER".equalsIgnoreCase(actor.role())) {
            if (!unit.ownerId().equals(actor.id())) {
                throw AppException.forbidden("Chủ nhà chỉ được tạo hợp đồng cho căn hộ của mình.");
            }
            tenantId = req.tenantId();
            if (req.rent() != null) {
                if (req.rent() <= 0 || req.rent() > MAX_MONTHLY_RENT) {
                    throw AppException.badRequest("INVALID_RENT", "Tiền thuê hàng tháng phải lớn hơn 0 và nằm trong giới hạn hợp lệ.");
                }
                monthlyRent = req.rent();
            } else {
                monthlyRent = unit.price();
            }
            if (req.deposit() != null) {
                if (req.deposit() < 0 || req.deposit() > MAX_DEPOSIT) {
                    throw AppException.badRequest("INVALID_DEPOSIT", "Tiền đặt cọc không hợp lệ.");
                }
                depositAmount = req.deposit();
            } else {
                depositAmount = monthlyRent;
            }
        } else {
            // P0-03: TENANT cannot forge rent, deposit, or tenantId; always derive from authoritative DB listing
            if (req.tenantId() != null && !req.tenantId().isBlank() && !req.tenantId().equals(actor.id())) {
                throw AppException.forbidden("Khách thuê không thể tạo hợp đồng thay cho tài khoản khác.");
            }
            if (!unit.published() || !"ready".equals(unit.unitStatus())) {
                throw AppException.conflict("APARTMENT_UNAVAILABLE", "Căn hộ không khả dụng để tạo hợp đồng.");
            }
            if (req.rent() != null && req.rent() != unit.price()) {
                throw AppException.badRequest("TENANT_PRICE_TAMPERING",
                        "Khách thuê không được tự thay đổi giá thuê niêm yết của căn hộ.");
            }
            if (req.deposit() != null && req.deposit() != unit.price()) {
                throw AppException.badRequest("TENANT_DEPOSIT_TAMPERING",
                        "Khách thuê không được tự thay đổi mức tiền đặt cọc theo quy định.");
            }
            tenantId = actor.id();
            monthlyRent = unit.price();
            depositAmount = unit.price();
        }

        if (tenantId == null || tenantId.isBlank() || !isTenantUser(tenantId)) {
            throw AppException.badRequest("INVALID_TENANT", "Chọn khách thuê hợp lệ.");
        }

        String termUnit = normalizeTermUnit(req.leaseTermUnit());
        int termValue = (req.leaseTermValue() != null) ? req.leaseTermValue() : 1;
        if (termValue <= 0) {
            throw AppException.badRequest("INVALID_TERM", "Thời hạn thuê phải lớn hơn 0.");
        }

        LocalDate startDate = req.startDate();
        LocalDate endDate = req.endDate();
        if (endDate == null && ("MONTH".equals(termUnit) || "YEAR".equals(termUnit))) {
            endDate = LeaseRentCalculator.calculateEndDateForTerm(startDate, termUnit, termValue);
        }
        validateLeaseDates(startDate, endDate, today);
        // Validate breakdown bounds (overflow & max duration)
        LeaseRentCalculator.calculateLeaseBreakdown(startDate, endDate, monthlyRent);

        if (renewalOfId != null) {
            ContractDto base = getContractForActorInternal(actor, renewalOfId, today);
            if (!"signed".equalsIgnoreCase(base.status())
                    || !base.apartmentId().equals(unit.id())
                    || !base.tenantId().equals(tenantId)
                    || !"requested".equalsIgnoreCase(base.renewal())
                    || !startDate.isAfter(base.endDate())
                    || hasRenewalChild(base.id())) {
                throw AppException.conflict("INVALID_RENEWAL", "Kiểm tra lại yêu cầu gia hạn và thời hạn của hợp đồng mới.");
            }
        }

        if (hasOverlappingContractOrRequest(unit.id(), tenantId, startDate, endDate, null)) {
            throw AppException.conflict("OVERLAPPING_CONTRACT", "Đã có hợp đồng hoặc yêu cầu của khách thuê này trùng thời gian.");
        }

        if (req.appointment() != null) {
            LocalDateTime now = serverClock.nowMinutePrecision();
            if (!req.appointment().isAfter(now)) {
                throw AppException.badRequest("INVALID_APPOINTMENT", "Chọn ngày giờ ký trong tương lai theo giờ Việt Nam.");
            }
        }

        String id = UUID.randomUUID().toString();
        String code = "HD-" + id.substring(0, 8).toUpperCase();
        LocalDate moveInDeadline = startDate.plusDays(3);

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO contracts (
                        id, contract_code, apartment_id, tenant_id, owner_id, renewal_of_id,
                        apartment_title, room_code, property_name, signing_location, terms_version,
                        start_date, end_date, lease_term_unit, lease_term_value,
                        monthly_rent, deposit_amount, deposit_received_amount, deposit_verification_status,
                        electric_rate, water_rate,
                        status, payment_status, appointment_at, move_in_deadline,
                        handover_meter_status, deposit_forfeited, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'v1.0', ?, ?, ?, ?, ?, ?, NULL, 'NOT_APPLICABLE', ?, ?, 'AWAITING', 'UNPAID', ?, ?, 'NOT_RECORDED', 0, 0)
                    """,
                    id, code, unit.id(), tenantId, unit.ownerId(), renewalOfId,
                    unit.title(), unit.room(), unit.propertyName(), unit.location(),
                    Date.valueOf(startDate), Date.valueOf(endDate), termUnit, termValue,
                    monthlyRent, depositAmount, unit.electricRate(), unit.waterRate(),
                    req.appointment() != null ? Timestamp.valueOf(req.appointment()) : null,
                    Date.valueOf(moveInDeadline)
            );
        } catch (DuplicateKeyException ex) {
            throw AppException.conflict("INVALID_RENEWAL", "Hợp đồng gốc đã có hợp đồng gia hạn đang hoạt động hoặc trùng mã hợp đồng.");
        }

        recordHistory(id, null, "AWAITING", null, "UNPAID", "CREATED", actor.id(), "Tạo hợp đồng chờ ký.");

        if (req.appointment() != null) {
            String actorLabel = "OWNER".equalsIgnoreCase(actor.role()) ? "Chủ nhà / Người môi giới" : "Khách thuê";
            recordHistory(
                    id, "AWAITING", "AWAITING", "UNPAID", "UNPAID",
                    "SCHEDULED", actor.id(), actorLabel + " đặt lịch hẹn ký tại " + unit.location()
            );
        }

        return getContractForActorInternal(actor, id, today);
    }

    @Transactional
    public ContractDto scheduleAppointment(
            AuthUser actor,
            String contractId,
            ScheduleAppointmentRequest req
    ) {
        String apartmentId = lockService.lockApartmentAndContract(contractId);
        if (apartmentId == null) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);
        if (!"awaiting".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("CANNOT_SCHEDULE", "Hợp đồng này không thể đặt hoặc đổi lịch ký.");
        }
        LocalDateTime now = serverClock.nowMinutePrecision();
        if (req == null || req.appointment() == null || !req.appointment().isAfter(now)) {
            throw AppException.badRequest("INVALID_APPOINTMENT", "Chọn ngày giờ ký trong tương lai theo giờ Việt Nam.");
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET appointment_at = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'AWAITING' AND version = ?
                """,
                Timestamp.valueOf(req.appointment()), contractId, contract.version()
        );
        if (updated == 0) {
            throw AppException.conflict("CONTRACT_CHANGED", "Hợp đồng đã thay đổi trạng thái. Vui lòng tải lại.");
        }

        String actorLabel = "OWNER".equalsIgnoreCase(actor.role()) ? "Chủ nhà / Người môi giới" : "Khách thuê";
        recordHistory(
                contractId, "AWAITING", "AWAITING", "UNPAID", "UNPAID",
                "SCHEDULED", actor.id(), actorLabel + " đặt/đổi lịch hẹn ký tại " + contract.location()
        );
        return getContractForActorInternal(actor, contractId, today);
    }

    @Transactional
    public ContractDto updateAwaitingContract(
            AuthUser actor,
            String contractId,
            UpdateContractRequest req
    ) {
        String apartmentId = lockService.lockApartmentAndContract(contractId);
        if (apartmentId == null) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);
        if (!"awaiting".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("CANNOT_UPDATE", "Chỉ có thể chỉnh sửa điều khoản khi hợp đồng đang ở trạng thái Chờ ký.");
        }
        if (req == null || req.startDate() == null) {
            throw AppException.badRequest("INVALID_DATES", "Kiểm tra lại ngày bắt đầu hợp đồng.");
        }

        String termUnit = (req.leaseTermUnit() != null && !req.leaseTermUnit().isBlank())
                ? normalizeTermUnit(req.leaseTermUnit())
                : contract.leaseTermUnit();
        int termValue = (req.leaseTermValue() != null)
                ? req.leaseTermValue()
                : contract.leaseTermValue();
        if (termValue <= 0) {
            throw AppException.badRequest("INVALID_TERM", "Thời hạn thuê phải lớn hơn 0.");
        }

        LocalDate startDate = req.startDate();
        LocalDate endDate = req.endDate();
        if (endDate == null && ("MONTH".equals(termUnit) || "YEAR".equals(termUnit))) {
            endDate = LeaseRentCalculator.calculateEndDateForTerm(startDate, termUnit, termValue);
        }
        validateLeaseDates(startDate, endDate, today);

        if (contract.renewalOf() != null && !contract.renewalOf().isBlank()) {
            ContractDto base = getContractForActorInternal(actor, contract.renewalOf(), today);
            if (!startDate.isAfter(base.endDate())) {
                throw AppException.conflict("INVALID_RENEWAL", "Ngày bắt đầu hợp đồng gia hạn phải sau ngày kết thúc của hợp đồng cũ.");
            }
        }

        // P0-03: TENANT cannot alter rent or deposit
        long monthlyRent;
        long depositAmount;
        if ("OWNER".equalsIgnoreCase(actor.role())) {
            if (req.rent() != null) {
                if (req.rent() <= 0 || req.rent() > MAX_MONTHLY_RENT) {
                    throw AppException.badRequest("INVALID_RENT", "Tiền thuê hàng tháng phải lớn hơn 0 và nằm trong giới hạn hợp lệ.");
                }
                monthlyRent = req.rent();
            } else {
                monthlyRent = contract.rent();
            }
            if (req.deposit() != null) {
                if (req.deposit() < 0 || req.deposit() > MAX_DEPOSIT) {
                    throw AppException.badRequest("INVALID_DEPOSIT", "Tiền đặt cọc không hợp lệ.");
                }
                depositAmount = req.deposit();
            } else {
                depositAmount = (req.rent() != null) ? monthlyRent : contract.deposit();
            }
        } else {
            if (req.rent() != null && req.rent() != contract.rent()) {
                throw AppException.forbidden("Khách thuê không có quyền thay đổi giá thuê trên hợp đồng.");
            }
            if (req.deposit() != null && req.deposit() != contract.deposit()) {
                throw AppException.forbidden("Khách thuê không có quyền thay đổi tiền đặt cọc trên hợp đồng.");
            }
            monthlyRent = contract.rent();
            depositAmount = contract.deposit();
        }

        LeaseRentCalculator.calculateLeaseBreakdown(startDate, endDate, monthlyRent);

        if (hasOverlappingContractOrRequest(contract.apartmentId(), contract.tenantId(), startDate, endDate, contractId)) {
            throw AppException.conflict("OVERLAPPING_CONTRACT", "Đã có hợp đồng hoặc yêu cầu của khách thuê này trùng thời gian.");
        }

        LocalDateTime nextAppointment = contract.appointment();
        boolean appointmentChanged = false;
        if (req.appointment() != null) {
            LocalDateTime now = serverClock.nowMinutePrecision();
            if (!req.appointment().isAfter(now)) {
                throw AppException.badRequest("INVALID_APPOINTMENT", "Chọn ngày giờ ký trong tương lai theo giờ Việt Nam.");
            }
            nextAppointment = req.appointment();
            appointmentChanged = !Objects.equals(nextAppointment, contract.appointment());
        }

        LocalDate moveInDeadline = startDate.plusDays(3);
        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET start_date = ?, end_date = ?, lease_term_unit = ?, lease_term_value = ?,
                    monthly_rent = ?, deposit_amount = ?, move_in_deadline = ?, appointment_at = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'AWAITING' AND version = ?
                """,
                Date.valueOf(startDate), Date.valueOf(endDate), termUnit, termValue,
                monthlyRent, depositAmount, Date.valueOf(moveInDeadline),
                nextAppointment != null ? Timestamp.valueOf(nextAppointment) : null,
                contractId, contract.version()
        );
        if (updated == 0) {
            throw AppException.conflict("CONTRACT_CHANGED", "Hợp đồng đã thay đổi trạng thái. Vui lòng tải lại.");
        }

        StringBuilder note = new StringBuilder("Cập nhật điều khoản hợp đồng chờ ký (")
                .append(startDate).append(" đến ").append(endDate);
        if (monthlyRent != contract.rent() || depositAmount != contract.deposit()) {
            note.append("; tiền thuê ").append(contract.rent()).append(" -> ").append(monthlyRent)
                    .append(", tiền cọc ").append(contract.deposit()).append(" -> ").append(depositAmount);
        }
        note.append(").");

        recordHistory(
                contractId, "AWAITING", "AWAITING", "UNPAID", "UNPAID",
                "UPDATED", actor.id(), truncate(note.toString(), MAX_NOTE_LENGTH)
        );

        if (appointmentChanged) {
            String actorLabel = "OWNER".equalsIgnoreCase(actor.role()) ? "Chủ nhà / Người môi giới" : "Khách thuê";
            recordHistory(
                    contractId, "AWAITING", "AWAITING", "UNPAID", "UNPAID",
                    "SCHEDULED", actor.id(), actorLabel + " đặt/đổi lịch hẹn ký tại " + contract.location()
            );
        }

        return getContractForActorInternal(actor, contractId, today);
    }

    @Transactional
    public ContractDto cancelAwaitingContract(
            AuthUser actor,
            String contractId,
            CancelAwaitingContractRequest req
    ) {
        String apartmentId = lockService.lockApartmentAndContract(contractId);
        if (apartmentId == null) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);
        if (!"awaiting".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("CANNOT_CANCEL_AWAITING", "Chỉ có thể hủy trực tiếp khi hợp đồng đang ở trạng thái Chờ ký.");
        }

        String actorRoleLabel = "OWNER".equalsIgnoreCase(actor.role()) ? "Chủ nhà / Người môi giới" : "Khách thuê";
        String customReason = (req != null && req.reason() != null && !req.reason().isBlank())
                ? truncate(req.reason().trim(), MAX_NOTE_LENGTH)
                : actorRoleLabel + " hủy hợp đồng chờ ký trước khi ký kết.";

        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET status = 'CANCELLED',
                    deposit_forfeited = 0,
                    cancelled_at = CURRENT_TIMESTAMP,
                    cancellation_reason = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'AWAITING' AND version = ?
                """,
                customReason, contractId, contract.version()
        );
        if (updated == 0) {
            throw AppException.conflict("CONTRACT_CHANGED", "Hợp đồng đã thay đổi trạng thái. Vui lòng tải lại.");
        }

        recordHistory(
                contractId, "AWAITING", "CANCELLED", "UNPAID", "UNPAID",
                "CANCELLED_AWAITING", actor.id(), customReason
        );
        return getContractForActorInternal(actor, contractId, today);
    }

    /**
     * P0-01 + P0-02:
     * - Khóa hàng căn hộ và hợp đồng trực tiếp tại CSDL (UPDLOCK, HOLDLOCK trên SQL Server / FOR UPDATE trên H2)
     *   trong phạm vi @Transactional, giữ khóa cho đến khi commit/rollback (an toàn đa instance).
     * - Bắt buộc xác nhận số tiền cọc thực nhận != null, >= 0, >= deposit_amount và lưu vào deposit_received_amount.
     */
    @Transactional
    public ContractDto confirmSignAndDepositByOwner(
            AuthUser actor,
            String contractId,
            ConfirmSignAndDepositRequest req
    ) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ người môi giới / chủ nhà quản lý căn hộ mới được xác nhận ký và thu tiền ban đầu.");
        }

        String apartmentId = lockService.lockApartmentAndContract(contractId);
        if (apartmentId == null) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }

        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);
        if ("signed".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("ALREADY_SIGNED", "Hợp đồng này đã được xác nhận ký và thu tiền cọc.");
        }
        if (!"awaiting".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("INVALID_CONTRACT_STATUS", "Hợp đồng không ở trạng thái chờ ký.");
        }
        if (contract.appointment() == null) {
            throw AppException.badRequest("MISSING_APPOINTMENT", "Khách thuê cần đặt lịch hẹn trước khi xác nhận ký tại địa điểm hẹn.");
        }
        if (req == null || !Boolean.TRUE.equals(req.signedAtLocation())) {
            throw AppException.badRequest("SIGNATURE_NOT_CONFIRMED", "Hợp đồng chưa ký không thể chuyển sang Đã thanh toán. Người môi giới cần xác nhận khách đã ký tại địa điểm hẹn.");
        }
        if (!Boolean.TRUE.equals(req.depositReceived())) {
            throw AppException.badRequest("PAYMENT_NOT_CONFIRMED", "Hợp đồng chỉ được kích hoạt khi người môi giới ghi nhận đã thu khoản tiền đặt cọc trên hợp đồng.");
        }
        // P0-02: receivedAmount is mandatory, non-negative, and >= contract.deposit()
        if (req.receivedAmount() == null) {
            throw AppException.badRequest("MISSING_DEPOSIT_AMOUNT", "Vui lòng nhập số tiền đặt cọc thực nhận.");
        }
        if (req.receivedAmount() < 0 || req.receivedAmount() > MAX_DEPOSIT) {
            throw AppException.badRequest("INVALID_DEPOSIT_AMOUNT", "Số tiền đặt cọc thực nhận không hợp lệ.");
        }
        if (req.receivedAmount() < contract.deposit()) {
            throw AppException.badRequest("INSUFFICIENT_DEPOSIT", "Số tiền nhận ban đầu phải đủ mức tiền đặt cọc trên hợp đồng.");
        }
        if (contract.endDate().isBefore(today)) {
            throw AppException.conflict("CONTRACT_EXPIRED", "Thời hạn của hợp đồng này đã qua.");
        }

        String conditionStatus = jdbcTemplate.queryForObject(
                "SELECT condition_status FROM apartments WHERE id = ?",
                String.class,
                contract.apartmentId()
        );
        if ("MAINTENANCE".equalsIgnoreCase(conditionStatus)) {
            throw AppException.conflict("APARTMENT_MAINTENANCE", "Căn hộ đang bảo trì.");
        }

        if (hasOverlappingSignedContract(contract.apartmentId(), contract.startDate(), contract.endDate(), contractId)) {
            throw AppException.conflict("OVERLAPPING_SIGNED_CONTRACT", "Căn hộ đã có hợp đồng đã ký trùng thời gian.");
        }

        Timestamp nowTs = Timestamp.valueOf(serverClock.nowMinutePrecision());
        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET status = 'SIGNED',
                    payment_status = 'PAID',
                    deposit_received_amount = ?,
                    deposit_verification_status = 'VERIFIED',
                    signed_at = ?,
                    signed_confirmed_by = ?,
                    payment_confirmed_at = ?,
                    payment_confirmed_by = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'AWAITING' AND payment_status = 'UNPAID' AND version = ?
                """,
                req.receivedAmount(), nowTs, actor.id(), nowTs, actor.id(), contractId, contract.version()
        );
        if (updated == 0) {
            throw AppException.conflict("CONCURRENT_SIGN_CONFLICT", "Hợp đồng đã được cập nhật bởi yêu cầu khác.");
        }

        jdbcTemplate.update(
                "UPDATE apartments SET published = 0, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                contract.apartmentId()
        );

        String baseNote = (req.note() != null && !req.note().isBlank())
                ? req.note().trim()
                : "Người môi giới xác nhận khách đã ký tại địa điểm hẹn và đã thu tiền đặt cọc.";
        StringBuilder fullNote = new StringBuilder(baseNote)
                .append(" Thực nhận: ").append(req.receivedAmount()).append(" VND (yêu cầu cọc: ")
                .append(contract.deposit()).append(" VND).");
        if (req.receivedAmount() > contract.deposit()) {
            long excess = req.receivedAmount() - contract.deposit();
            fullNote.append(" Khoản vượt mức cọc ").append(excess)
                    .append(" VND không tự trừ vào tiền thuê và cần đối soát riêng.");
        }

        recordHistory(
                contractId, "AWAITING", "SIGNED", "UNPAID", "PAID",
                "SIGN_AND_DEPOSIT_CONFIRMED", actor.id(), truncate(fullNote.toString(), MAX_NOTE_LENGTH)
        );
        return getContractForActorInternal(actor, contractId, today);
    }

    /**
     * P0-08 + P0-06 + P1-10:
     * Luôn sử dụng thời gian máy chủ (ServerClock), khóa theo quy ước chuẩn Apartment -> Contract.
     */
    @Transactional
    public ContractDto cancelContractForNoShowAfterThreeDays(AuthUser actor, String contractId) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ người môi giới / chủ nhà mới được thực hiện hủy hợp đồng quá hạn nhận phòng.");
        }
        String apartmentId = lockService.lockApartmentAndContract(contractId);
        if (apartmentId == null) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);
        if (!"signed".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("INVALID_STATUS", "Chỉ áp dụng cho hợp đồng đã ký nhưng chưa nhận phòng.");
        }
        if (contract.actualMoveIn() != null) {
            throw AppException.conflict("ALREADY_MOVED_IN", "Khách hàng đã nhận phòng thực tế, không thể hủy theo điều khoản quá hạn dọn vào.");
        }

        LocalDate deadline = contract.moveInDeadline();
        if (!today.isAfter(deadline)) {
            throw AppException.conflict(
                    "WITHIN_GRACE_PERIOD",
                    "Chưa hết thời hạn 3 ngày kể từ ngày dọn vào quy định trên hợp đồng (" + deadline + ")."
            );
        }

        if (!executeLockedNoShowCancellation(contractId, today, actor.id())) {
            throw AppException.conflict("CONTRACT_CHANGED", "Hợp đồng đã thay đổi. Vui lòng tải lại để kiểm tra trạng thái.");
        }
        return getContractForActorInternal(actor, contractId, today);
    }

    /**
     * Quét tự động các hợp đồng quá hạn nhận phòng 3 ngày theo ServerClock.
     * Mỗi hợp đồng được khóa theo thứ tự chuẩn (Apartment -> Contract) và xử lý độc lập.
     */
    public int autoCancelExpiredNoShowContracts() {
        LocalDate today = serverClock.today();
        List<String> candidateIds = jdbcTemplate.queryForList(
                """
                SELECT id
                FROM contracts
                WHERE status = 'SIGNED' AND actual_move_in IS NULL AND move_in_deadline < ?
                ORDER BY apartment_id ASC, id ASC
                """,
                String.class,
                Date.valueOf(today)
        );
        int count = 0;
        for (String contractId : candidateIds) {
            Boolean cancelled = perItemTransactionTemplate.execute(status -> {
                String apartmentId = lockService.lockApartmentAndContract(contractId);
                if (apartmentId == null) {
                    return false;
                }
                return executeLockedNoShowCancellation(contractId, today, null);
            });
            if (Boolean.TRUE.equals(cancelled)) {
                count++;
            }
        }
        return count;
    }

    private boolean executeLockedNoShowCancellation(String contractId, LocalDate today, String actorId) {
        record LockedContractRow(LocalDate startDate, String status, String paymentStatus,
                                 LocalDate actualMoveIn, LocalDate moveInDeadline, int version) {}
        List<LockedContractRow> rows = jdbcTemplate.query(
                """
                SELECT start_date, status, payment_status, actual_move_in, move_in_deadline, version
                FROM contracts
                WHERE id = ?
                """,
                (rs, rowNum) -> new LockedContractRow(
                        rs.getDate("start_date").toLocalDate(),
                        rs.getString("status"),
                        rs.getString("payment_status"),
                        rs.getDate("actual_move_in") != null ? rs.getDate("actual_move_in").toLocalDate() : null,
                        rs.getDate("move_in_deadline").toLocalDate(),
                        rs.getInt("version")
                ),
                contractId
        );
        if (rows.isEmpty()) {
            return false;
        }
        LockedContractRow row = rows.get(0);
        if (!"SIGNED".equalsIgnoreCase(row.status())
                || !"PAID".equalsIgnoreCase(row.paymentStatus())
                || row.actualMoveIn() != null
                || !today.isAfter(row.moveInDeadline())) {
            return false;
        }

        String reason = "Khách hàng không dọn vào trong 3 ngày kể từ ngày quy định trên hợp đồng ("
                + row.startDate() + "); hợp đồng bị hủy và khách mất tiền đặt cọc.";
        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET status = 'CANCELLED',
                    deposit_forfeited = 1,
                    cancelled_at = CURRENT_TIMESTAMP,
                    cancellation_reason = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'SIGNED' AND payment_status = 'PAID'
                  AND actual_move_in IS NULL AND move_in_deadline < ? AND version = ?
                """,
                reason, contractId, Date.valueOf(today), row.version()
        );
        if (updated == 0) {
            return false;
        }
        recordHistory(
                contractId, "SIGNED", "CANCELLED", "PAID", "PAID",
                "CANCELLED_NO_SHOW_3_DAYS", actorId, reason
        );
        return true;
    }

    @Transactional
    public ContractDto recordCheckIn(AuthUser actor, String contractId, CheckInRequest req) {
        if (req == null || req.actualMoveIn() == null) {
            throw AppException.badRequest("MISSING_MOVE_IN_DATE", "Vui lòng nhập ngày nhận phòng thực tế.");
        }
        return applyHandover(actor, contractId, req.actualMoveIn(), null, req.initialElectric(), req.initialWater());
    }

    @Transactional
    public ContractDto recordCheckOut(AuthUser actor, String contractId, CheckOutRequest req) {
        if (req == null || req.actualMoveOut() == null) {
            throw AppException.badRequest("MISSING_MOVE_OUT_DATE", "Vui lòng nhập ngày trả phòng thực tế.");
        }
        return applyHandover(actor, contractId, null, req.actualMoveOut(), null, null);
    }

    /**
     * P0-04 + P0-06 + P0-07:
     * - Trường null/vắng mặt tuyệt đối KHÔNG xóa ngày nhận phòng hoặc ngày trả phòng đã lưu.
     * - Khi ghi nhận nhận phòng lần đầu, bắt buộc ghi chỉ số điện/nước bàn giao ban đầu (không mặc định 0).
     * - Bảo toàn hoàn toàn hóa đơn phạt nhận phòng sớm đã PAID.
     */
    @Transactional
    public ContractDto recordHandover(AuthUser actor, String contractId, HandoverRequest req) {
        if (req == null || (req.actualMoveIn() == null && req.actualMoveOut() == null
                && req.initialElectric() == null && req.initialWater() == null)) {
            throw AppException.badRequest("EMPTY_HANDOVER_REQUEST", "Vui lòng nhập thông tin bàn giao nhận phòng hoặc trả phòng.");
        }
        return applyHandover(actor, contractId, req.actualMoveIn(), req.actualMoveOut(),
                req.initialElectric(), req.initialWater());
    }

    private ContractDto applyHandover(
            AuthUser actor,
            String contractId,
            LocalDate reqMoveIn,
            LocalDate reqMoveOut,
            Integer reqInitialElectric,
            Integer reqInitialWater
    ) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới được ghi nhận bàn giao căn hộ.");
        }

        String apartmentId = lockService.lockApartmentAndContract(contractId);
        if (apartmentId == null) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }

        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);
        if (!"signed".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("INVALID_STATUS", "Chỉ ghi nhận bàn giao cho hợp đồng đã ký.");
        }

        // Preserve existing recorded dates when omitted/null (P0-06)
        LocalDate effectiveMoveIn = (reqMoveIn != null) ? reqMoveIn : contract.actualMoveIn();
        LocalDate effectiveMoveOut = (reqMoveOut != null) ? reqMoveOut : contract.actualMoveOut();

        if (effectiveMoveOut != null && effectiveMoveIn == null) {
            throw AppException.badRequest("MOVE_IN_REQUIRED_BEFORE_MOVE_OUT",
                    "Cần ghi nhận ngày nhận phòng thực tế trước khi ghi nhận ngày trả phòng.");
        }
        if ((effectiveMoveIn != null && effectiveMoveIn.isAfter(today))
                || (effectiveMoveOut != null && effectiveMoveOut.isAfter(today))) {
            throw AppException.badRequest("FUTURE_HANDOVER_DATE", "Ngày bàn giao thực tế phải là hôm nay hoặc trước đó.");
        }
        if (effectiveMoveIn != null && effectiveMoveOut != null && effectiveMoveOut.isBefore(effectiveMoveIn)) {
            throw AppException.badRequest("INVALID_MOVE_OUT_DATE", "Ngày trả phòng phải từ ngày nhận phòng trở đi.");
        }
        if (effectiveMoveIn != null && effectiveMoveIn.isAfter(contract.moveInDeadline())) {
            throw AppException.conflict(
                    "EXCEEDED_3_DAY_MOVE_IN_DEADLINE",
                    "Khách dọn vào sau quá 3 ngày kể từ ngày quy định trên hợp đồng (" + contract.startDate() + "); hợp đồng thuộc diện hủy và mất tiền cọc."
            );
        }

        // Handle initial handover utility meters (P0-04)
        Integer effectiveElectric;
        Integer effectiveWater;
        String effectiveMeterStatus;
        boolean hasMonthly = hasMonthlyInvoices(contractId);

        if (reqInitialElectric != null || reqInitialWater != null) {
            if (effectiveMoveIn == null) {
                throw AppException.badRequest("MOVE_IN_REQUIRED_FOR_METERS",
                        "Cần có ngày nhận phòng thực tế để ghi nhận chỉ số điện nước bàn giao.");
            }
            if (reqInitialElectric == null || reqInitialWater == null
                    || reqInitialElectric < 0 || reqInitialElectric > MAX_METER_READING
                    || reqInitialWater < 0 || reqInitialWater > MAX_METER_READING) {
                throw AppException.badRequest("INVALID_HANDOVER_METERS",
                        "Chỉ số điện và nước bàn giao phải đầy đủ, không âm và nằm trong giới hạn hợp lệ.");
            }
            boolean metersDiffer = !Objects.equals(reqInitialElectric, contract.handoverElectricReading())
                    || !Objects.equals(reqInitialWater, contract.handoverWaterReading());
            if (metersDiffer && hasMonthly) {
                throw AppException.conflict("HANDOVER_METERS_LOCKED_BY_INVOICES",
                        "Không thể thay đổi chỉ số điện nước bàn giao sau khi hợp đồng đã lập hóa đơn hàng tháng.");
            }
            effectiveElectric = reqInitialElectric;
            effectiveWater = reqInitialWater;
            effectiveMeterStatus = "VERIFIED";
        } else {
            if (contract.actualMoveIn() == null && effectiveMoveIn != null) {
                throw AppException.badRequest("MISSING_HANDOVER_METERS",
                        "Vui lòng nhập chỉ số điện và nước ban đầu khi ghi nhận nhận phòng thực tế.");
            }
            effectiveElectric = contract.handoverElectricReading();
            effectiveWater = contract.handoverWaterReading();
            effectiveMeterStatus = contract.handoverMeterStatus().toUpperCase(Locale.ROOT);
        }

        // Idempotent repeat: nothing changed -> preserve state, penalty, and history
        if (Objects.equals(effectiveMoveIn, contract.actualMoveIn())
                && Objects.equals(effectiveMoveOut, contract.actualMoveOut())
                && Objects.equals(effectiveElectric, contract.handoverElectricReading())
                && Objects.equals(effectiveWater, contract.handoverWaterReading())
                && effectiveMeterStatus.equalsIgnoreCase(contract.handoverMeterStatus())) {
            return contract;
        }

        // Overlap check for early move-in
        if (effectiveMoveIn != null && effectiveMoveIn.isBefore(contract.startDate())) {
            if (hasOverlappingSignedContract(contract.apartmentId(), effectiveMoveIn, contract.startDate().minusDays(1), contractId)) {
                throw AppException.conflict("EARLY_MOVE_IN_OVERLAP", "Ngày nhận phòng sớm trùng với hợp đồng khác.");
            }
        }

        // P0-07: manage EARLY_MOVE_IN penalty safely without touching PAID invoice
        upsertEarlyMoveInPenaltyIfApplicable(contract, effectiveMoveIn);

        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET actual_move_in = ?,
                    actual_move_out = ?,
                    handover_electric_reading = ?,
                    handover_water_reading = ?,
                    handover_meter_status = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'SIGNED' AND version = ?
                """,
                effectiveMoveIn != null ? Date.valueOf(effectiveMoveIn) : null,
                effectiveMoveOut != null ? Date.valueOf(effectiveMoveOut) : null,
                effectiveElectric,
                effectiveWater,
                effectiveMeterStatus,
                contractId,
                contract.version()
        );
        if (updated == 0) {
            throw AppException.conflict("CONTRACT_CHANGED", "Hợp đồng đã thay đổi trạng thái. Vui lòng tải lại.");
        }

        StringBuilder note = new StringBuilder("Ghi nhận bàn giao thực tế");
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(effectiveMoveIn, contract.actualMoveIn())) {
            changes.add(contract.actualMoveIn() == null
                    ? "nhận phòng " + effectiveMoveIn
                    : "điều chỉnh nhận phòng " + contract.actualMoveIn() + " -> " + effectiveMoveIn);
        }
        if (!Objects.equals(effectiveMoveOut, contract.actualMoveOut())) {
            changes.add(contract.actualMoveOut() == null
                    ? "trả phòng " + effectiveMoveOut
                    : "điều chỉnh trả phòng " + contract.actualMoveOut() + " -> " + effectiveMoveOut);
        }
        if (!Objects.equals(effectiveElectric, contract.handoverElectricReading())
                || !Objects.equals(effectiveWater, contract.handoverWaterReading())) {
            changes.add("chỉ số bàn giao điện=" + effectiveElectric + " kWh, nước=" + effectiveWater + " m³");
        }
        if (!changes.isEmpty()) {
            note.append(": ").append(String.join("; ", changes)).append(".");
        } else {
            note.append(".");
        }

        recordHistory(
                contractId, "SIGNED", "SIGNED", "PAID", "PAID",
                "HANDOVER_RECORDED", actor.id(), truncate(note.toString(), MAX_NOTE_LENGTH)
        );
        return getContractForActorInternal(actor, contractId, today);
    }

    @Transactional
    public ContractDto respondRenewal(AuthUser actor, String contractId, RenewalResponseRequest req) {
        if (!"TENANT".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ khách thuê mới được gửi phản hồi gia hạn.");
        }
        if (!lockService.lockContract(contractId)) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        LocalDate today = serverClock.today();
        ContractDto contract = getContractForActorInternal(actor, contractId, today);

        String choice = (req != null && req.choice() != null) ? req.choice().trim().toLowerCase(Locale.ROOT) : "";
        if (!"signed".equalsIgnoreCase(contract.status())
                || !contract.renewalOpen()
                || contract.renewal() != null
                || (!"requested".equals(choice) && !"declined".equals(choice))) {
            throw AppException.conflict("CANNOT_UPDATE_RENEWAL", "Hợp đồng này không thể cập nhật lựa chọn gia hạn.");
        }

        String choiceDb = choice.toUpperCase(Locale.ROOT);
        Timestamp nowTs = Timestamp.valueOf(serverClock.nowMinutePrecision());
        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET renewal_choice = ?, renewal_responded_at = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'SIGNED' AND renewal_choice IS NULL AND version = ?
                """,
                choiceDb, nowTs, contractId, contract.version()
        );
        if (updated == 0) {
            throw AppException.conflict("CANNOT_UPDATE_RENEWAL", "Lựa chọn gia hạn của hợp đồng đã được ghi nhận trước đó.");
        }

        recordHistory(
                contractId, "SIGNED", "SIGNED", "PAID", "PAID",
                "RENEWAL_RESPONDED", actor.id(),
                "Khách thuê chọn: " + ("REQUESTED".equals(choiceDb) ? "Muốn gia hạn" : "Không gia hạn")
        );
        return getContractForActorInternal(actor, contractId, today);
    }

    public List<ContractDto> listRenewalNotices(AuthUser actor) {
        List<ContractDto> all = listContractsForActor(actor);
        if ("TENANT".equalsIgnoreCase(actor.role())) {
            return all.stream()
                    .filter(c -> "signed".equalsIgnoreCase(c.status()) && c.renewalOpen() && c.renewal() == null)
                    .toList();
        }
        return all.stream()
                .filter(c -> "signed".equalsIgnoreCase(c.status())
                        && "requested".equalsIgnoreCase(c.renewal())
                        && !hasRenewalChild(c.id()))
                .toList();
    }

    public NextInvoicePreviewDto getNextInvoicePreview(AuthUser actor, String contractId) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới có quyền xem kỳ lập hóa đơn tiếp theo.");
        }
        ContractDto contract = getContractForActor(actor, contractId);
        if (!"signed".equalsIgnoreCase(contract.status())) {
            return new NextInvoicePreviewDto(contractId, false, "Chỉ lập hóa đơn cho hợp đồng đã ký.",
                    null, null, null, null, null, null, null, null, null, "unverified");
        }

        LeaseRentCalculator.LeaseTermBreakDown breakdown = LeaseRentCalculator.calculateLeaseBreakdown(
                contract.startDate(), contract.endDate(), contract.rent()
        );

        Integer maxExistingPeriod = jdbcTemplate.queryForObject(
                "SELECT MAX(period_index) FROM invoices WHERE contract_id = ? AND kind = 'MONTHLY'",
                Integer.class,
                contractId
        );
        int nextPeriodIndex = (maxExistingPeriod == null) ? 1 : maxExistingPeriod + 1;

        Optional<LeaseRentCalculator.BillingPeriodQuote> nextQuoteOpt = breakdown.periods().stream()
                .filter(p -> p.periodIndex() == nextPeriodIndex)
                .findFirst();
        if (nextQuoteOpt.isEmpty()) {
            return new NextInvoicePreviewDto(contractId, false, "Hợp đồng đã lập đủ hóa đơn cho tất cả các kỳ thuê.",
                    null, null, null, null, null, null, null, null, null, "complete");
        }

        LeaseRentCalculator.BillingPeriodQuote quote = nextQuoteOpt.get();
        if (nextPeriodIndex == 1) {
            if (contract.actualMoveIn() == null || !"verified".equalsIgnoreCase(contract.handoverMeterStatus())
                    || contract.handoverElectricReading() == null || contract.handoverWaterReading() == null) {
                return new NextInvoicePreviewDto(
                        contractId, false,
                        "Cần ghi nhận ngày nhận phòng thực tế và chỉ số điện nước bàn giao ban đầu trước khi lập hóa đơn kỳ đầu.",
                        quote.periodIndex(), quote.billingMonth(), quote.periodStart(), quote.periodEnd(),
                        quote.daysInPeriod(), quote.monthBasisDays(), quote.rentAmount(),
                        null, null, "unverified"
                );
            }
            return new NextInvoicePreviewDto(
                    contractId, true, null,
                    quote.periodIndex(), quote.billingMonth(), quote.periodStart(), quote.periodEnd(),
                    quote.daysInPeriod(), quote.monthBasisDays(), quote.rentAmount(),
                    contract.handoverElectricReading(), contract.handoverWaterReading(), "handover"
            );
        }

        PreviousPeriodMeters prev = findPreviousPeriodMeters(contractId, nextPeriodIndex - 1);
        if (prev == null) {
            return new NextInvoicePreviewDto(
                    contractId, false,
                    "Cần lập hóa đơn tuần tự từ kỳ " + (nextPeriodIndex - 1) + " trước khi lập kỳ " + nextPeriodIndex + ".",
                    quote.periodIndex(), quote.billingMonth(), quote.periodStart(), quote.periodEnd(),
                    quote.daysInPeriod(), quote.monthBasisDays(), quote.rentAmount(),
                    null, null, "missing_previous"
            );
        }
        if ("LEGACY_DISCREPANCY".equalsIgnoreCase(prev.meterVerificationStatus())) {
            return new NextInvoicePreviewDto(
                    contractId, false,
                    "Hợp đồng có sai lệch chỉ số điện nước lịch sử cần đối soát trước khi lập hóa đơn mới.",
                    quote.periodIndex(), quote.billingMonth(), quote.periodStart(), quote.periodEnd(),
                    quote.daysInPeriod(), quote.monthBasisDays(), quote.rentAmount(),
                    null, null, "discrepancy"
            );
        }
        return new NextInvoicePreviewDto(
                contractId, true, null,
                quote.periodIndex(), quote.billingMonth(), quote.periodStart(), quote.periodEnd(),
                quote.daysInPeriod(), quote.monthBasisDays(), quote.rentAmount(),
                prev.newElectric(), prev.newWater(), "previous_invoice"
        );
    }

    /**
     * P0-04 + P0-05 + P1-13:
     * - Khóa hợp đồng trong transaction để chống lập trùng hóa đơn đồng thời.
     * - Xác định chính thức kỳ thuê, billing_month, period_start, period_end từ LeaseRentCalculator (từ chối mâu thuẫn).
     * - Bắt buộc lập tuần tự theo kỳ và liên tục chỉ số điện nước (kỳ 1 lấy từ bàn giao đã xác minh; kỳ k lấy từ kỳ k-1).
     */
    @Transactional
    public InvoiceDto createPeriodInvoice(AuthUser actor, String contractId, CreateInvoiceRequest req) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới có quyền lập hóa đơn.");
        }
        if (req == null) {
            throw AppException.badRequest("INVALID_INVOICE", "Thiếu thông tin lập hóa đơn.");
        }

        if (!lockService.lockContract(contractId)) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }

        ContractDto contract = getContractForActor(actor, contractId);
        if (!"signed".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("CONTRACT_NOT_SIGNED", "Chỉ lập hóa đơn cho hợp đồng đã ký.");
        }

        LeaseRentCalculator.LeaseTermBreakDown breakdown = LeaseRentCalculator.calculateLeaseBreakdown(
                contract.startDate(), contract.endDate(), contract.rent()
        );
        LeaseRentCalculator.BillingPeriodQuote targetPeriod = resolveTargetPeriod(breakdown, req.periodIndex(), req.month());

        // P0-05: canonical billingMonth and periodIndex come strictly from targetPeriod
        int periodIndex = targetPeriod.periodIndex();
        String billingMonth = targetPeriod.billingMonth();

        Integer existingPeriodCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE contract_id = ? AND kind = 'MONTHLY' AND (period_index = ? OR billing_month = ?)",
                Integer.class,
                contractId, periodIndex, billingMonth
        );
        if (existingPeriodCount != null && existingPeriodCount > 0) {
            throw AppException.conflict("DUPLICATE_INVOICE", "Hợp đồng đã có hóa đơn thuê cho kỳ " + periodIndex + " (" + billingMonth + ").");
        }

        // P0-04: sequential billing & meter continuity
        int expectedOldElectric;
        int expectedOldWater;
        if (periodIndex == 1) {
            if (contract.actualMoveIn() == null
                    || !"verified".equalsIgnoreCase(contract.handoverMeterStatus())
                    || contract.handoverElectricReading() == null
                    || contract.handoverWaterReading() == null) {
                throw AppException.conflict(
                        "UNVERIFIED_HANDOVER_METERS",
                        "Chưa có chỉ số điện nước bàn giao đã xác minh. Vui lòng ghi nhận bàn giao nhận phòng và chỉ số ban đầu trước khi lập hóa đơn kỳ 1."
                );
            }
            expectedOldElectric = contract.handoverElectricReading();
            expectedOldWater = contract.handoverWaterReading();
        } else {
            PreviousPeriodMeters prev = findPreviousPeriodMeters(contractId, periodIndex - 1);
            if (prev == null) {
                throw AppException.conflict(
                        "PREVIOUS_PERIOD_REQUIRED",
                        "Cần lập hóa đơn tuần tự: chưa có hóa đơn cho kỳ " + (periodIndex - 1) + " trước khi lập kỳ " + periodIndex + "."
                );
            }
            if ("LEGACY_DISCREPANCY".equalsIgnoreCase(prev.meterVerificationStatus())) {
                throw AppException.conflict(
                        "LEGACY_METER_DISCREPANCY",
                        "Hợp đồng có sai lệch chỉ số điện nước lịch sử cần đối soát trước khi lập hóa đơn tiếp theo."
                );
            }
            expectedOldElectric = prev.newElectric();
            expectedOldWater = prev.newWater();
        }

        if (req.oldElectric() != null && req.oldElectric() != expectedOldElectric) {
            throw AppException.badRequest(
                    "ELECTRIC_METER_CONTINUITY_ERROR",
                    "Chỉ số điện cũ (" + req.oldElectric() + ") không khớp với chỉ số chốt liền trước (" + expectedOldElectric + " kWh)."
            );
        }
        if (req.oldWater() != null && req.oldWater() != expectedOldWater) {
            throw AppException.badRequest(
                    "WATER_METER_CONTINUITY_ERROR",
                    "Chỉ số nước cũ (" + req.oldWater() + ") không khớp với chỉ số chốt liền trước (" + expectedOldWater + " m³)."
            );
        }
        if (req.newElectric() == null || req.newWater() == null
                || req.newElectric() < expectedOldElectric || req.newWater() < expectedOldWater
                || req.newElectric() > MAX_METER_READING || req.newWater() > MAX_METER_READING) {
            throw AppException.badRequest("INVALID_METERS",
                    "Chỉ số mới phải lớn hơn hoặc bằng chỉ số cũ (điện >= " + expectedOldElectric
                            + ", nước >= " + expectedOldWater + ") và nằm trong giới hạn hợp lệ.");
        }

        int electricUsage = req.newElectric() - expectedOldElectric;
        int waterUsage = req.newWater() - expectedOldWater;
        long rentAmount = targetPeriod.rentAmount();
        long electricCost;
        long waterCost;
        long total;
        try {
            electricCost = Math.multiplyExact((long) electricUsage, contract.electricRate());
            waterCost = Math.multiplyExact((long) waterUsage, contract.waterRate());
            total = Math.addExact(Math.addExact(rentAmount, electricCost), waterCost);
        } catch (ArithmeticException ex) {
            throw AppException.badRequest("AMOUNT_OVERFLOW", "Tổng tiền hóa đơn vượt quá giới hạn tính toán.");
        }

        String invoiceId = UUID.randomUUID().toString();
        String invoiceCode = "HĐ-" + invoiceId.substring(0, 8).toUpperCase();

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO invoices (
                        id, invoice_code, contract_id, period_index, billing_month, period_start, period_end,
                        days_in_period, month_basis_days, kind, status,
                        old_electric, new_electric, old_water, new_water, meter_verification_status, total_amount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MONTHLY', 'UNPAID', ?, ?, ?, ?, 'VERIFIED', ?)
                    """,
                    invoiceId, invoiceCode, contractId, periodIndex, billingMonth,
                    Date.valueOf(targetPeriod.periodStart()), Date.valueOf(targetPeriod.periodEnd()),
                    targetPeriod.daysInPeriod(), targetPeriod.monthBasisDays(),
                    expectedOldElectric, req.newElectric(), expectedOldWater, req.newWater(), total
            );
        } catch (DuplicateKeyException ex) {
            throw AppException.conflict("DUPLICATE_INVOICE", "Hợp đồng đã có hóa đơn thuê cho kỳ " + periodIndex + ".");
        }

        String rentLineTitle = targetPeriod.fullMonth()
                ? "Tiền thuê (chu kỳ " + targetPeriod.daysInPeriod() + "/" + targetPeriod.monthBasisDays() + " ngày)"
                : "Tiền thuê (" + targetPeriod.daysInPeriod() + "/" + targetPeriod.monthBasisDays() + " ngày)";

        insertInvoiceLine(invoiceId, 1, rentLineTitle, rentAmount);
        insertInvoiceLine(invoiceId, 2, "Tiền điện (" + electricUsage + " kWh)", electricCost);
        insertInvoiceLine(invoiceId, 3, "Tiền nước (" + waterUsage + " m³)", waterCost);

        return getInvoiceById(invoiceId);
    }

    /**
     * P2-02: Loại bỏ N+1 query — nạp toàn bộ hóa đơn và invoice_lines trong đúng 1 câu truy vấn JOIN,
     * giữ nguyên kiểm tra phân quyền và thứ tự dữ liệu (created_at DESC, id DESC, line_order ASC).
     */
    public List<InvoiceDto> listInvoicesForActor(AuthUser actor) {
        String ownerOrTenantCol = "OWNER".equalsIgnoreCase(actor.role()) ? "c.owner_id" : "c.tenant_id";
        Map<String, InvoiceAccumulator> grouped = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT i.id, i.invoice_code, i.contract_id, i.period_index, i.billing_month,
                       i.period_start, i.period_end, i.days_in_period, i.month_basis_days,
                       i.kind, i.status, i.old_electric, i.new_electric, i.old_water, i.new_water,
                       i.meter_verification_status, i.total_amount, i.paid_at, i.confirmed_by,
                       l.title AS line_title, l.amount AS line_amount
                FROM invoices i
                JOIN contracts c ON c.id = i.contract_id
                LEFT JOIN invoice_lines l ON l.invoice_id = i.id
                WHERE """ + " " + ownerOrTenantCol + """
                 = ?
                ORDER BY i.created_at DESC, i.id DESC, l.line_order ASC
                """,
                rs -> {
                    String invId = rs.getString("id");
                    InvoiceAccumulator acc = grouped.get(invId);
                    if (acc == null) {
                        acc = new InvoiceAccumulator(
                                invId,
                                rs.getString("invoice_code"),
                                rs.getString("contract_id"),
                                (Integer) rs.getObject("period_index"),
                                rs.getString("billing_month"),
                                rs.getDate("period_start").toLocalDate(),
                                rs.getDate("period_end").toLocalDate(),
                                rs.getInt("days_in_period"),
                                rs.getInt("month_basis_days"),
                                "EARLY_MOVE_IN".equals(rs.getString("kind")) ? "early-move-in" : "monthly",
                                rs.getString("status").toLowerCase(Locale.ROOT),
                                (Integer) rs.getObject("old_electric"),
                                (Integer) rs.getObject("new_electric"),
                                (Integer) rs.getObject("old_water"),
                                (Integer) rs.getObject("new_water"),
                                rs.getString("meter_verification_status").toLowerCase(Locale.ROOT),
                                rs.getLong("total_amount"),
                                rs.getDate("paid_at") != null ? rs.getDate("paid_at").toLocalDate() : null,
                                rs.getString("confirmed_by")
                        );
                        grouped.put(invId, acc);
                    }
                    String lineTitle = rs.getString("line_title");
                    if (lineTitle != null) {
                        acc.lines.add(new InvoiceDto.InvoiceLineDto(lineTitle, rs.getLong("line_amount")));
                    }
                },
                actor.id()
        );
        return grouped.values().stream().map(InvoiceAccumulator::toDto).toList();
    }

    public InvoiceDto getInvoiceForActor(AuthUser actor, String invoiceId) {
        List<String> contractIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM invoices WHERE id = ?",
                String.class,
                invoiceId
        );
        if (contractIds.isEmpty()) {
            throw AppException.notFound("Không tìm thấy hóa đơn.");
        }
        getContractForActor(actor, contractIds.get(0));
        return getInvoiceById(invoiceId);
    }

    /**
     * P1-12: Xác nhận thu tiền hóa đơn idempotent và an toàn đồng thời:
     * - Khóa hợp đồng (cùng thứ tự khóa với bàn giao/phạt nhận sớm) -> kiểm tra quyền sở hữu ->
     *   UPDATE nguyên tử WHERE id = ? AND status = 'UNPAID'.
     * - Nếu đã PAID, trả kết quả đã lưu mà không ghi đè paid_at hay confirmed_by.
     */
    @Transactional
    public InvoiceDto confirmInvoicePaid(AuthUser actor, String invoiceId) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới có quyền xác nhận đã thu tiền hóa đơn.");
        }
        List<String> contractIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM invoices WHERE id = ?",
                String.class,
                invoiceId
        );
        if (contractIds.isEmpty()) {
            throw AppException.notFound("Không tìm thấy hóa đơn.");
        }
        String contractId = contractIds.get(0);
        // Level 2 lock: serializes against handover/early-move-in updates and concurrent confirm-paid
        if (!lockService.lockContract(contractId)) {
            throw AppException.notFound("Không tìm thấy hợp đồng.");
        }
        getContractForActor(actor, contractId);

        LocalDate paidDate = serverClock.today();
        jdbcTemplate.update(
                """
                UPDATE invoices
                SET status = 'PAID', paid_at = ?, confirmed_by = ?
                WHERE id = ? AND status = 'UNPAID'
                """,
                Date.valueOf(paidDate), actor.id(), invoiceId
        );
        // Whether this call transitioned UNPAID->PAID (1 row) or saw PAID already (0 rows), return persisted state
        return getInvoiceById(invoiceId);
    }

    private LeaseRentCalculator.BillingPeriodQuote resolveTargetPeriod(
            LeaseRentCalculator.LeaseTermBreakDown breakdown,
            Integer periodIndex,
            String billingMonth
    ) {
        String cleanMonth = (billingMonth != null && !billingMonth.isBlank()) ? billingMonth.trim() : null;
        if (periodIndex != null) {
            LeaseRentCalculator.BillingPeriodQuote matched = breakdown.periods().stream()
                    .filter(p -> p.periodIndex() == periodIndex)
                    .findFirst()
                    .orElseThrow(() -> AppException.badRequest("INVALID_PERIOD_INDEX", "Kỳ hóa đơn nằm ngoài thời hạn hợp đồng."));
            if (cleanMonth != null && !matched.billingMonth().equals(cleanMonth)) {
                throw AppException.badRequest(
                        "PERIOD_MONTH_MISMATCH",
                        "Tháng lập hóa đơn (" + cleanMonth + ") không khớp với kỳ thuê số " + periodIndex
                                + " (" + matched.billingMonth() + ")."
                );
            }
            return matched;
        }
        if (cleanMonth != null) {
            return breakdown.periods().stream()
                    .filter(p -> p.billingMonth().equals(cleanMonth))
                    .findFirst()
                    .orElseThrow(() -> AppException.badRequest("MONTH_OUT_OF_RANGE", "Tháng lập hóa đơn nằm ngoài chu kỳ thời hạn hợp đồng."));
        }
        throw AppException.badRequest("MISSING_BILLING_PERIOD", "Vui lòng chọn kỳ thuê hoặc tháng cần lập hóa đơn.");
    }

    /**
     * P0-07: Bảo vệ tuyệt đối hóa đơn phạt nhận phòng sớm đã PAID; cập nhật tại chỗ (in-place) khi UNPAID.
     */
    private void upsertEarlyMoveInPenaltyIfApplicable(ContractDto contract, LocalDate effectiveMoveIn) {
        record ExistingPenalty(String id, String status, LocalDate periodStart) {}
        List<ExistingPenalty> existingPenalties = jdbcTemplate.query(
                "SELECT id, status, period_start FROM invoices WHERE contract_id = ? AND kind = 'EARLY_MOVE_IN'",
                (rs, rowNum) -> new ExistingPenalty(
                        rs.getString("id"),
                        rs.getString("status"),
                        rs.getDate("period_start").toLocalDate()
                ),
                contract.id()
        );

        boolean needsPenalty = effectiveMoveIn != null && effectiveMoveIn.isBefore(contract.startDate());

        if (!existingPenalties.isEmpty()) {
            ExistingPenalty existing = existingPenalties.get(0);
            if ("PAID".equalsIgnoreCase(existing.status())) {
                if (needsPenalty && Objects.equals(effectiveMoveIn, contract.actualMoveIn())) {
                    // Same early move-in date while updating checkout/metadata -> keep PAID invoice 100% untouched!
                    return;
                }
                throw AppException.conflict("PENALTY_ALREADY_PAID",
                        "Hóa đơn phạt nhận phòng sớm đã được xác nhận thu tiền; không thể thay đổi ngày nhận phòng làm ảnh hưởng đến hóa đơn này.");
            }

            // Existing penalty is UNPAID
            if (!needsPenalty) {
                int deleted = jdbcTemplate.update("DELETE FROM invoices WHERE id = ? AND status = 'UNPAID'", existing.id());
                if (deleted == 0) {
                    throw AppException.conflict("PENALTY_ALREADY_PAID", "Hóa đơn phạt đã được xác nhận thu tiền.");
                }
                return;
            }
            if (effectiveMoveIn.equals(existing.periodStart())) {
                return; // Idempotent: unchanged early move-in date
            }
            String month = effectiveMoveIn.toString().substring(0, 7);
            int basisDays = LeaseRentCalculator.monthBasisDays(effectiveMoveIn.getMonthValue());
            int updated = jdbcTemplate.update(
                    """
                    UPDATE invoices
                    SET billing_month = ?, period_start = ?, period_end = ?, month_basis_days = ?
                    WHERE id = ? AND status = 'UNPAID'
                    """,
                    month, Date.valueOf(effectiveMoveIn), Date.valueOf(contract.startDate().minusDays(1)),
                    basisDays, existing.id()
            );
            if (updated == 0) {
                throw AppException.conflict("PENALTY_ALREADY_PAID", "Hóa đơn phạt đã được xác nhận thu tiền.");
            }
            return;
        }

        if (needsPenalty) {
            String invoiceId = UUID.randomUUID().toString();
            String code = "HĐ-P" + invoiceId.substring(0, 7).toUpperCase();
            String month = effectiveMoveIn.toString().substring(0, 7);
            int basisDays = LeaseRentCalculator.monthBasisDays(effectiveMoveIn.getMonthValue());
            jdbcTemplate.update(
                    """
                    INSERT INTO invoices (
                        id, invoice_code, contract_id, period_index, billing_month, period_start, period_end,
                        days_in_period, month_basis_days, kind, status, meter_verification_status, total_amount
                    ) VALUES (?, ?, ?, NULL, ?, ?, ?, 1, ?, 'EARLY_MOVE_IN', 'UNPAID', 'NOT_APPLICABLE', ?)
                    """,
                    invoiceId, code, contract.id(), month,
                    Date.valueOf(effectiveMoveIn), Date.valueOf(contract.startDate().minusDays(1)),
                    basisDays, contract.rent()
            );
            insertInvoiceLine(invoiceId, 1, "Phạt nhận phòng trước ngày thỏa thuận", contract.rent());
        }
    }

    private record PreviousPeriodMeters(int newElectric, int newWater, String meterVerificationStatus) {}

    private PreviousPeriodMeters findPreviousPeriodMeters(String contractId, int previousPeriodIndex) {
        List<PreviousPeriodMeters> rows = jdbcTemplate.query(
                """
                SELECT new_electric, new_water, meter_verification_status
                FROM invoices
                WHERE contract_id = ? AND kind = 'MONTHLY' AND period_index = ?
                """,
                (rs, rowNum) -> new PreviousPeriodMeters(
                        rs.getInt("new_electric"),
                        rs.getInt("new_water"),
                        rs.getString("meter_verification_status")
                ),
                contractId, previousPeriodIndex
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean hasMonthlyInvoices(String contractId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE contract_id = ? AND kind = 'MONTHLY'",
                Integer.class,
                contractId
        );
        return count != null && count > 0;
    }

    private void insertInvoiceLine(String invoiceId, int order, String title, long amount) {
        jdbcTemplate.update(
                "INSERT INTO invoice_lines (id, invoice_id, line_order, title, amount) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), invoiceId, order, title, amount
        );
    }

    private InvoiceDto getInvoiceById(String invoiceId) {
        List<InvoiceDto.InvoiceLineDto> lines = jdbcTemplate.query(
                "SELECT title, amount FROM invoice_lines WHERE invoice_id = ? ORDER BY line_order ASC",
                (rs, rowNum) -> new InvoiceDto.InvoiceLineDto(rs.getString("title"), rs.getLong("amount")),
                invoiceId
        );
        return jdbcTemplate.queryForObject(
                """
                SELECT id, invoice_code, contract_id, period_index, billing_month, period_start, period_end,
                       days_in_period, month_basis_days, kind, status,
                       old_electric, new_electric, old_water, new_water, meter_verification_status,
                       total_amount, paid_at, confirmed_by
                FROM invoices
                WHERE id = ?
                """,
                (rs, rowNum) -> new InvoiceDto(
                        rs.getString("id"),
                        rs.getString("invoice_code"),
                        rs.getString("contract_id"),
                        (Integer) rs.getObject("period_index"),
                        rs.getString("billing_month"),
                        rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate(),
                        rs.getInt("days_in_period"),
                        rs.getInt("month_basis_days"),
                        "EARLY_MOVE_IN".equals(rs.getString("kind")) ? "early-move-in" : "monthly",
                        rs.getString("status").toLowerCase(Locale.ROOT),
                        (Integer) rs.getObject("old_electric"),
                        (Integer) rs.getObject("new_electric"),
                        (Integer) rs.getObject("old_water"),
                        (Integer) rs.getObject("new_water"),
                        rs.getString("meter_verification_status").toLowerCase(Locale.ROOT),
                        rs.getLong("total_amount"),
                        rs.getDate("paid_at") != null ? rs.getDate("paid_at").toLocalDate() : null,
                        rs.getString("confirmed_by"),
                        lines
                ),
                invoiceId
        );
    }

    private String normalizeTermUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CUSTOM";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!"MONTH".equals(normalized) && !"YEAR".equals(normalized) && !"CUSTOM".equals(normalized)) {
            throw AppException.badRequest("INVALID_TERM_UNIT", "Đơn vị thời hạn thuê phải là MONTH, YEAR hoặc CUSTOM.");
        }
        return normalized;
    }

    private void validateLeaseDates(LocalDate startDate, LocalDate endDate, LocalDate today) {
        if (startDate == null || endDate == null || startDate.isBefore(today) || endDate.isBefore(startDate)) {
            throw AppException.badRequest("INVALID_DATES", "Kiểm tra lại thời hạn hợp đồng.");
        }
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (totalDays > LeaseRentCalculator.MAX_LEASE_DAYS) {
            throw AppException.badRequest("TERM_TOO_LONG", "Thời hạn thuê vượt quá giới hạn tối đa cho phép.");
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }

    private boolean isTenantUser(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND role = 'TENANT' AND active = 1",
                Integer.class,
                userId
        );
        return count != null && count > 0;
    }

    public boolean hasRenewalChild(String baseContractId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contracts WHERE renewal_of_id = ? AND status <> 'CANCELLED'",
                Integer.class,
                baseContractId
        );
        return count != null && count > 0;
    }

    private boolean hasOverlappingSignedContract(
            String apartmentId,
            LocalDate startDate,
            LocalDate endDate,
            String excludeContractId
    ) {
        String sql = """
                SELECT COUNT(*) FROM contracts
                WHERE apartment_id = ?
                  AND status = 'SIGNED'
                  AND (CASE WHEN actual_move_in IS NOT NULL AND actual_move_in < start_date THEN actual_move_in ELSE start_date END) <= ?
                  AND end_date >= ?
                """;
        Integer count;
        if (excludeContractId == null) {
            count = jdbcTemplate.queryForObject(sql, Integer.class, apartmentId, Date.valueOf(endDate), Date.valueOf(startDate));
        } else {
            count = jdbcTemplate.queryForObject(
                    sql + " AND id <> ?",
                    Integer.class,
                    apartmentId, Date.valueOf(endDate), Date.valueOf(startDate), excludeContractId
            );
        }
        return count != null && count > 0;
    }

    private boolean hasOverlappingContractOrRequest(
            String apartmentId,
            String tenantId,
            LocalDate startDate,
            LocalDate endDate,
            String excludeContractId
    ) {
        String sql = """
                SELECT COUNT(*) FROM contracts
                WHERE apartment_id = ?
                  AND status <> 'CANCELLED'
                  AND (status = 'SIGNED' OR tenant_id = ?)
                  AND (CASE WHEN actual_move_in IS NOT NULL AND actual_move_in < start_date THEN actual_move_in ELSE start_date END) <= ?
                  AND end_date >= ?
                """;
        Integer count;
        if (excludeContractId == null) {
            count = jdbcTemplate.queryForObject(
                    sql, Integer.class, apartmentId, tenantId, Date.valueOf(endDate), Date.valueOf(startDate)
            );
        } else {
            count = jdbcTemplate.queryForObject(
                    sql + " AND id <> ?",
                    Integer.class,
                    apartmentId, tenantId, Date.valueOf(endDate), Date.valueOf(startDate), excludeContractId
            );
        }
        return count != null && count > 0;
    }

    private void recordHistory(
            String contractId,
            String fromStatus,
            String toStatus,
            String fromPayment,
            String toPayment,
            String actionType,
            String actorId,
            String note
    ) {
        String historyId = String.format(
                Locale.ROOT,
                "%013d-%06d-%s",
                System.currentTimeMillis(),
                HISTORY_SEQ.incrementAndGet() % 1_000_000L,
                UUID.randomUUID().toString().substring(0, 8)
        );
        jdbcTemplate.update(
                """
                INSERT INTO contract_status_history (
                    id, contract_id, from_status, to_status, from_payment_status, to_payment_status,
                    action_type, actor_id, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                historyId, contractId, fromStatus, toStatus, fromPayment, toPayment,
                actionType, actorId, note
        );
    }

    private static final class InvoiceAccumulator {
        final String id;
        final String code;
        final String contractId;
        final Integer periodIndex;
        final String month;
        final LocalDate periodStart;
        final LocalDate periodEnd;
        final int daysInPeriod;
        final int monthBasisDays;
        final String kind;
        final String status;
        final Integer oldElectric;
        final Integer newElectric;
        final Integer oldWater;
        final Integer newWater;
        final String meterVerificationStatus;
        final long totalAmount;
        final LocalDate paidAt;
        final String confirmedBy;
        final List<InvoiceDto.InvoiceLineDto> lines = new ArrayList<>();

        InvoiceAccumulator(
                String id, String code, String contractId, Integer periodIndex, String month,
                LocalDate periodStart, LocalDate periodEnd, int daysInPeriod, int monthBasisDays,
                String kind, String status, Integer oldElectric, Integer newElectric,
                Integer oldWater, Integer newWater, String meterVerificationStatus,
                long totalAmount, LocalDate paidAt, String confirmedBy
        ) {
            this.id = id;
            this.code = code;
            this.contractId = contractId;
            this.periodIndex = periodIndex;
            this.month = month;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.daysInPeriod = daysInPeriod;
            this.monthBasisDays = monthBasisDays;
            this.kind = kind;
            this.status = status;
            this.oldElectric = oldElectric;
            this.newElectric = newElectric;
            this.oldWater = oldWater;
            this.newWater = newWater;
            this.meterVerificationStatus = meterVerificationStatus;
            this.totalAmount = totalAmount;
            this.paidAt = paidAt;
            this.confirmedBy = confirmedBy;
        }

        InvoiceDto toDto() {
            return new InvoiceDto(
                    id, code, contractId, periodIndex, month, periodStart, periodEnd,
                    daysInPeriod, monthBasisDays, kind, status,
                    oldElectric, newElectric, oldWater, newWater, meterVerificationStatus,
                    totalAmount, paidAt, confirmedBy, List.copyOf(lines)
            );
        }
    }

    private static final String BASE_CONTRACT_SELECT = """
            SELECT
                c.id,
                c.contract_code,
                c.apartment_id,
                c.tenant_id,
                ut.full_name AS tenant_name,
                c.owner_id,
                uo.full_name AS owner_name,
                c.renewal_of_id,
                c.apartment_title,
                c.room_code,
                c.property_name,
                c.signing_location,
                c.terms_version,
                c.start_date,
                c.end_date,
                c.lease_term_unit,
                c.lease_term_value,
                c.monthly_rent,
                c.deposit_amount,
                c.deposit_received_amount,
                c.deposit_verification_status,
                c.electric_rate,
                c.water_rate,
                c.status,
                c.payment_status,
                c.appointment_at,
                c.signed_at,
                c.signed_confirmed_by,
                c.payment_confirmed_at,
                c.payment_confirmed_by,
                c.move_in_deadline,
                c.actual_move_in,
                c.actual_move_out,
                c.handover_electric_reading,
                c.handover_water_reading,
                c.handover_meter_status,
                c.deposit_forfeited,
                c.cancelled_at,
                c.cancellation_reason,
                c.renewal_choice,
                c.renewal_responded_at,
                c.version
            FROM contracts c
            JOIN users ut ON ut.id = c.tenant_id
            JOIN users uo ON uo.id = c.owner_id
            """;

    private static RowMapper<ContractDto> contractRowMapper(LocalDate today) {
        return (rs, rowNum) -> {
            LocalDate startDate = rs.getDate("start_date").toLocalDate();
            LocalDate endDate = rs.getDate("end_date").toLocalDate();
            long rent = rs.getLong("monthly_rent");
            String status = rs.getString("status").toLowerCase(Locale.ROOT);
            boolean expired = "signed".equals(status) && endDate.isBefore(today);
            boolean renewalOpen = "signed".equals(status) && !endDate.isAfter(today.plusDays(30));
            LeaseRentCalculator.LeaseTermBreakDown breakdown =
                    LeaseRentCalculator.calculateLeaseBreakdown(startDate, endDate, rent);
            Object depRecvObj = rs.getObject("deposit_received_amount");
            Long depositReceivedAmount = depRecvObj != null ? ((Number) depRecvObj).longValue() : null;
            return new ContractDto(
                    rs.getString("id"),
                    rs.getString("contract_code"),
                    rs.getString("apartment_id"),
                    rs.getString("tenant_id"),
                    rs.getString("tenant_name"),
                    rs.getString("owner_id"),
                    rs.getString("owner_name"),
                    rs.getString("renewal_of_id"),
                    rs.getString("apartment_title"),
                    rs.getString("room_code"),
                    rs.getString("property_name"),
                    rs.getString("signing_location"),
                    rs.getString("terms_version"),
                    startDate,
                    endDate,
                    rs.getString("lease_term_unit"),
                    rs.getInt("lease_term_value"),
                    rent,
                    rs.getLong("deposit_amount"),
                    depositReceivedAmount,
                    rs.getString("deposit_verification_status").toLowerCase(Locale.ROOT),
                    rs.getLong("electric_rate"),
                    rs.getLong("water_rate"),
                    status,
                    rs.getString("payment_status").toLowerCase(Locale.ROOT),
                    rs.getTimestamp("appointment_at") != null ? rs.getTimestamp("appointment_at").toLocalDateTime() : null,
                    rs.getTimestamp("signed_at") != null ? rs.getTimestamp("signed_at").toLocalDateTime() : null,
                    rs.getString("signed_confirmed_by"),
                    rs.getTimestamp("payment_confirmed_at") != null ? rs.getTimestamp("payment_confirmed_at").toLocalDateTime() : null,
                    rs.getString("payment_confirmed_by"),
                    rs.getDate("move_in_deadline").toLocalDate(),
                    rs.getDate("actual_move_in") != null ? rs.getDate("actual_move_in").toLocalDate() : null,
                    rs.getDate("actual_move_out") != null ? rs.getDate("actual_move_out").toLocalDate() : null,
                    (Integer) rs.getObject("handover_electric_reading"),
                    (Integer) rs.getObject("handover_water_reading"),
                    rs.getString("handover_meter_status").toLowerCase(Locale.ROOT),
                    rs.getBoolean("deposit_forfeited"),
                    rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toLocalDateTime() : null,
                    rs.getString("cancellation_reason"),
                    rs.getString("renewal_choice") != null ? rs.getString("renewal_choice").toLowerCase(Locale.ROOT) : null,
                    rs.getTimestamp("renewal_responded_at") != null ? rs.getTimestamp("renewal_responded_at").toLocalDateTime() : null,
                    expired,
                    renewalOpen,
                    breakdown.firstPeriodRent(),
                    breakdown.lastPeriodRent(),
                    rs.getInt("version")
            );
        };
    }
}
