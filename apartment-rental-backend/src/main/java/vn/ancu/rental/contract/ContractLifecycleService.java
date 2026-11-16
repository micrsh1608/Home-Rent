package vn.ancu.rental.contract;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ancu.rental.apartment.ApartmentDto;
import vn.ancu.rental.apartment.OwnerPropertyService;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.VietnamTime;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContractLifecycleService {

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, Object> apartmentLocks = new ConcurrentHashMap<>();

    public ContractLifecycleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
            String renewalOf
    ) {
    }

    public record ScheduleAppointmentRequest(
            LocalDateTime appointment,
            LocalDateTime now
    ) {
    }

    public record ConfirmSignAndDepositRequest(
            Boolean signedAtLocation,
            Boolean depositReceived,
            Long receivedAmount,
            String note
    ) {
    }

    public record HandoverRequest(
            LocalDate actualMoveIn,
            LocalDate actualMoveOut
    ) {
    }

    public record RenewalResponseRequest(
            String choice
    ) {
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
        return listContractsForActor(actor, VietnamTime.today());
    }

    public List<ContractDto> listContractsForActor(AuthUser actor, LocalDate todayInput) {
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        String column = "OWNER".equalsIgnoreCase(actor.role()) ? "c.owner_id" : "c.tenant_id";
        return jdbcTemplate.query(
                BASE_CONTRACT_SELECT + " WHERE " + column + " = ? ORDER BY c.created_at DESC, c.id DESC",
                contractRowMapper(today),
                actor.id()
        );
    }

    public ContractDto getContractForActor(AuthUser actor, String contractId) {
        return getContractForActor(actor, contractId, VietnamTime.today());
    }

    public ContractDto getContractForActor(AuthUser actor, String contractId, LocalDate todayInput) {
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
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
    public ContractDto createContract(AuthUser actor, CreateContractRequest req, LocalDate todayInput) {
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        if (req == null || req.apartmentId() == null || req.apartmentId().isBlank() || req.startDate() == null) {
            throw AppException.badRequest("INVALID_CONTRACT", "Kiểm tra lại căn hộ và ngày bắt đầu hợp đồng.");
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
        if ("OWNER".equalsIgnoreCase(actor.role())) {
            if (!unit.ownerId().equals(actor.id())) {
                throw AppException.forbidden("Chủ nhà chỉ được tạo hợp đồng cho căn hộ của mình.");
            }
            tenantId = req.tenantId();
        } else {
            if (!unit.published() || !"ready".equals(unit.unitStatus())) {
                throw AppException.conflict("APARTMENT_UNAVAILABLE", "Căn hộ không khả dụng để tạo hợp đồng.");
            }
            tenantId = actor.id();
        }

        if (tenantId == null || tenantId.isBlank() || !isTenantUser(tenantId)) {
            throw AppException.badRequest("INVALID_TENANT", "Chọn khách thuê hợp lệ.");
        }

        String termUnit = (req.leaseTermUnit() != null && !req.leaseTermUnit().isBlank())
                ? req.leaseTermUnit().trim().toUpperCase()
                : "CUSTOM";
        int termValue = (req.leaseTermValue() != null && req.leaseTermValue() > 0) ? req.leaseTermValue() : 1;

        LocalDate startDate = req.startDate();
        LocalDate endDate = req.endDate();
        if (endDate == null && ("MONTH".equals(termUnit) || "YEAR".equals(termUnit))) {
            endDate = LeaseRentCalculator.calculateEndDateForTerm(startDate, termUnit, termValue);
        }
        if (endDate == null || startDate.isBefore(today) || endDate.isBefore(startDate)) {
            throw AppException.badRequest("INVALID_DATES", "Kiểm tra lại thời hạn hợp đồng.");
        }

        long monthlyRent = (req.rent() != null && req.rent() > 0) ? req.rent() : unit.price();
        long depositAmount = (req.deposit() != null && req.deposit() >= 0) ? req.deposit() : monthlyRent;

        if (req.renewalOf() != null && !req.renewalOf().isBlank()) {
            ContractDto base = getContractForActor(actor, req.renewalOf(), today);
            if (!"OWNER".equalsIgnoreCase(actor.role())
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

        String id = UUID.randomUUID().toString();
        String code = "HD-" + id.substring(0, 8).toUpperCase();
        LocalDate moveInDeadline = startDate.plusDays(3);

        jdbcTemplate.update(
                """
                INSERT INTO contracts (
                    id, contract_code, apartment_id, tenant_id, owner_id, renewal_of_id,
                    apartment_title, room_code, property_name, signing_location, terms_version,
                    start_date, end_date, lease_term_unit, lease_term_value,
                    monthly_rent, deposit_amount, electric_rate, water_rate,
                    status, payment_status, move_in_deadline, deposit_forfeited
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'v1.0', ?, ?, ?, ?, ?, ?, ?, ?, 'AWAITING', 'UNPAID', ?, 0)
                """,
                id, code, unit.id(), tenantId, unit.ownerId(), req.renewalOf(),
                unit.title(), unit.room(), unit.propertyName(), unit.location(),
                Date.valueOf(startDate), Date.valueOf(endDate), termUnit, termValue,
                monthlyRent, depositAmount, unit.electricRate(), unit.waterRate(),
                Date.valueOf(moveInDeadline)
        );

        recordHistory(id, null, "AWAITING", null, "UNPAID", "CREATED", actor.id(), "Tạo hợp đồng chờ ký.");
        return getContractForActor(actor, id, today);
    }

    @Transactional
    public ContractDto scheduleAppointment(
            AuthUser actor,
            String contractId,
            ScheduleAppointmentRequest req
    ) {
        ContractDto contract = getContractForActor(actor, contractId);
        if (!"TENANT".equalsIgnoreCase(actor.role()) || !"awaiting".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("CANNOT_SCHEDULE", "Hợp đồng này không thể đặt hoặc đổi lịch ký.");
        }
        LocalDateTime now = (req != null && req.now() != null) ? req.now() : VietnamTime.nowMinutePrecision();
        if (req == null || req.appointment() == null || !req.appointment().isAfter(now)) {
            throw AppException.badRequest("INVALID_APPOINTMENT", "Chọn ngày giờ ký trong tương lai theo giờ Việt Nam.");
        }

        jdbcTemplate.update(
                "UPDATE contracts SET appointment_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                Timestamp.valueOf(req.appointment()), contractId
        );
        recordHistory(
                contractId, "AWAITING", "AWAITING", "UNPAID", "UNPAID",
                "SCHEDULED", actor.id(), "Khách thuê đặt/đổi lịch hẹn ký tại " + contract.location()
        );
        return getContractForActor(actor, contractId);
    }

    /**
     * Thực thi Mốc 3 & Quyết định 1 đã chốt:
     * - Người môi giới / Chủ nhà (OWNER) xác nhận khách đã ký tại địa điểm hẹn VÀ ghi nhận tiền đặt cọc đã nhận.
     * - Khóa theo căn hộ (lock per apartment + SELECT ... FOR UPDATE) để chống 2 yêu cầu ký đồng thời trùng kỳ thuê.
     * - Hợp đồng chưa ký không thể chuyển sang Đã thanh toán.
     * - Bấm xác nhận ký lại trên hợp đồng đã ký không ghi thêm lần ký.
     */
    @Transactional
    public ContractDto confirmSignAndDepositByOwner(
            AuthUser actor,
            String contractId,
            ConfirmSignAndDepositRequest req,
            LocalDate todayInput
    ) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ người môi giới / chủ nhà quản lý căn hộ mới được xác nhận ký và thu tiền ban đầu.");
        }
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        ContractDto initial = getContractForActor(actor, contractId, today);
        Object lock = apartmentLocks.computeIfAbsent(initial.apartmentId(), k -> new Object());

        synchronized (lock) {
            ContractDto contract = getContractForActor(actor, contractId, today);
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
            if (req.receivedAmount() != null && req.receivedAmount() < contract.deposit()) {
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

            Timestamp nowTs = Timestamp.valueOf(VietnamTime.nowMinutePrecision());
            int updated = jdbcTemplate.update(
                    """
                    UPDATE contracts
                    SET status = 'SIGNED',
                        payment_status = 'PAID',
                        signed_at = ?,
                        signed_confirmed_by = ?,
                        payment_confirmed_at = ?,
                        payment_confirmed_by = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'AWAITING'
                    """,
                    nowTs, actor.id(), nowTs, actor.id(), contractId
            );
            if (updated == 0) {
                throw AppException.conflict("CONCURRENT_SIGN_CONFLICT", "Hợp đồng đã được cập nhật bởi yêu cầu khác.");
            }

            jdbcTemplate.update(
                    "UPDATE apartments SET published = 0, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    contract.apartmentId()
            );

            String note = (req.note() != null && !req.note().isBlank())
                    ? req.note().trim()
                    : "Người môi giới xác nhận khách đã ký tại địa điểm hẹn và đã thu tiền đặt cọc.";
            recordHistory(
                    contractId, "AWAITING", "SIGNED", "UNPAID", "PAID",
                    "SIGN_AND_DEPOSIT_CONFIRMED", actor.id(), note
            );
            return getContractForActor(actor, contractId, today);
        }
    }

    /**
     * Thực thi Quyết định 1 đã chốt:
     * Nếu ngày dọn vào trong hợp đồng (startDate) đã quy định mà khách hàng không dọn vào trong 3 ngày
     * (tức sau startDate + 3 ngày vẫn chưa có actualMoveIn), hợp đồng sẽ bị hủy (CANCELLED)
     * và khách hàng mất tiền đặt cọc trên hợp đồng (depositForfeited = true).
     */
    @Transactional
    public ContractDto cancelContractForNoShowAfterThreeDays(
            AuthUser actor,
            String contractId,
            LocalDate todayInput
    ) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ người môi giới / chủ nhà mới được thực hiện hủy hợp đồng quá hạn nhận phòng.");
        }
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        ContractDto contract = getContractForActor(actor, contractId, today);
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

        if (!cancelNoShow(new NoShowCandidate(contractId, contract.startDate(), contract.payment()), today, actor.id())) {
            throw AppException.conflict("CONTRACT_CHANGED", "Hợp đồng đã thay đổi. Vui lòng tải lại để kiểm tra trạng thái.");
        }
        return getContractForActor(actor, contractId, today);
    }

    private record NoShowCandidate(String id, LocalDate startDate, String paymentStatus) {}

    private boolean cancelNoShow(NoShowCandidate contract, LocalDate today, String actorId) {
        String reason = "Khách hàng không dọn vào trong 3 ngày kể từ ngày quy định trên hợp đồng (" +
                contract.startDate() + "); hợp đồng bị hủy và khách mất tiền đặt cọc.";
        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET status = 'CANCELLED',
                    deposit_forfeited = 1,
                    cancelled_at = CURRENT_TIMESTAMP,
                    cancellation_reason = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'SIGNED' AND actual_move_in IS NULL AND move_in_deadline < ?
                """,
                reason, contract.id(), Date.valueOf(today)
        );
        if (updated == 0) {
            return false;
        }
        String payment = contract.paymentStatus().toUpperCase(Locale.ROOT);
        recordHistory(
                contract.id(), "SIGNED", "CANCELLED", payment, payment,
                "CANCELLED_NO_SHOW_3_DAYS", actorId, reason
        );
        return true;
    }

    @Transactional
    public int autoCancelExpiredNoShowContracts(LocalDate todayInput) {
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        List<NoShowCandidate> expiredNoShows = jdbcTemplate.query(
                """
                SELECT id, start_date, payment_status
                FROM contracts
                WHERE status = 'SIGNED' AND actual_move_in IS NULL AND move_in_deadline < ?
                ORDER BY id
                """,
                (rs, rowNum) -> new NoShowCandidate(
                        rs.getString("id"), rs.getDate("start_date").toLocalDate(), rs.getString("payment_status")
                ),
                Date.valueOf(today)
        );
        int count = 0;
        for (NoShowCandidate contract : expiredNoShows) {
            if (cancelNoShow(contract, today, null)) {
                count++;
            }
        }
        return count;
    }

    @Transactional
    public ContractDto recordHandover(
            AuthUser actor,
            String contractId,
            HandoverRequest req,
            LocalDate todayInput
    ) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới được ghi nhận bàn giao căn hộ.");
        }
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        ContractDto contract = getContractForActor(actor, contractId, today);
        if (!"signed".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("INVALID_STATUS", "Chỉ ghi nhận bàn giao cho hợp đồng đã ký.");
        }

        LocalDate moveIn = req != null ? req.actualMoveIn() : null;
        LocalDate moveOut = req != null ? req.actualMoveOut() : null;

        if ((moveIn != null && moveIn.isAfter(today)) || (moveOut != null && moveOut.isAfter(today))) {
            throw AppException.badRequest("FUTURE_HANDOVER_DATE", "Ngày bàn giao thực tế phải là hôm nay hoặc trước đó.");
        }
        if (moveIn != null && moveOut != null && moveOut.isBefore(moveIn)) {
            throw AppException.badRequest("INVALID_MOVE_OUT_DATE", "Ngày trả phòng phải từ ngày nhận phòng trở đi.");
        }
        if (moveIn != null && moveIn.isAfter(contract.startDate().plusDays(3))) {
            throw AppException.conflict(
                    "EXCEEDED_3_DAY_MOVE_IN_DEADLINE",
                    "Khách dọn vào sau quá 3 ngày kể từ ngày quy định trên hợp đồng (" + contract.startDate() + "); hợp đồng thuộc diện hủy và mất tiền cọc."
            );
        }

        if (moveIn != null && moveIn.isBefore(contract.startDate())) {
            if (hasOverlappingSignedContract(contract.apartmentId(), moveIn, contract.startDate().minusDays(1), contractId)) {
                throw AppException.conflict("EARLY_MOVE_IN_OVERLAP", "Ngày nhận phòng sớm trùng với hợp đồng khác.");
            }
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE contracts
                SET actual_move_in = ?, actual_move_out = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'SIGNED'
                """,
                moveIn != null ? Date.valueOf(moveIn) : null,
                moveOut != null ? Date.valueOf(moveOut) : null,
                contractId
        );
        if (updated == 0) {
            throw AppException.conflict("INVALID_STATUS", "Chỉ ghi nhận bàn giao cho hợp đồng đã ký.");
        }
        upsertEarlyMoveInPenaltyIfApplicable(contract, moveIn);

        recordHistory(
                contractId, "SIGNED", "SIGNED", "PAID", "PAID",
                "HANDOVER_RECORDED", actor.id(), "Ghi nhận ngày bàn giao thực tế."
        );
        return getContractForActor(actor, contractId, today);
    }

    /**
     * Thực thi Mốc 5:
     * Ghi nhận phản hồi gia hạn (REQUESTED / DECLINED) của khách thuê trong mốc nhắc 30 ngày.
     */
    @Transactional
    public ContractDto respondRenewal(
            AuthUser actor,
            String contractId,
            RenewalResponseRequest req,
            LocalDate todayInput
    ) {
        if (!"TENANT".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ khách thuê mới được gửi phản hồi gia hạn.");
        }
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        ContractDto contract = getContractForActor(actor, contractId, today);

        String choice = (req != null && req.choice() != null) ? req.choice().trim().toLowerCase() : "";
        if (!"signed".equalsIgnoreCase(contract.status())
                || !contract.renewalOpen()
                || contract.renewal() != null
                || (!"requested".equals(choice) && !"declined".equals(choice))) {
            throw AppException.conflict("CANNOT_UPDATE_RENEWAL", "Hợp đồng này không thể cập nhật lựa chọn gia hạn.");
        }

        String choiceDb = choice.toUpperCase();
        Timestamp nowTs = Timestamp.valueOf(VietnamTime.nowMinutePrecision());
        jdbcTemplate.update(
                """
                UPDATE contracts
                SET renewal_choice = ?, renewal_responded_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                choiceDb, nowTs, contractId
        );

        recordHistory(
                contractId, "SIGNED", "SIGNED", "PAID", "PAID",
                "RENEWAL_RESPONDED", actor.id(),
                "Khách thuê chọn: " + ("REQUESTED".equals(choiceDb) ? "Muốn gia hạn" : "Không gia hạn")
        );
        return getContractForActor(actor, contractId, today);
    }

    public List<ContractDto> listRenewalNotices(AuthUser actor, LocalDate todayInput) {
        LocalDate today = todayInput != null ? todayInput : VietnamTime.today();
        List<ContractDto> all = listContractsForActor(actor, today);
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

    /**
     * Thực thi Mốc 4 & Quyết định 2 khi lập hóa đơn theo kỳ:
     * Tính tiền thuê kỳ đầu, các kỳ giữa và kỳ cuối bắt đầu từ ngày dọn vào trên hợp đồng (startDate),
     * với 1 tháng là 30 hoặc 31 ngày tùy tháng và riêng tháng 2 vẫn tính theo 30 ngày kể từ ngày bắt đầu.
     */
    @Transactional
    public InvoiceDto createPeriodInvoice(AuthUser actor, String contractId, CreateInvoiceRequest req) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới có quyền lập hóa đơn.");
        }
        ContractDto contract = getContractForActor(actor, contractId);
        if (!"signed".equalsIgnoreCase(contract.status())) {
            throw AppException.conflict("CONTRACT_NOT_SIGNED", "Chỉ lập hóa đơn cho hợp đồng đã ký.");
        }

        if (req == null || req.oldElectric() == null || req.newElectric() == null
                || req.oldWater() == null || req.newWater() == null
                || req.oldElectric() < 0 || req.oldWater() < 0
                || req.newElectric() < req.oldElectric() || req.newWater() < req.oldWater()) {
            throw AppException.badRequest("INVALID_METERS", "Chỉ số mới phải lớn hơn hoặc bằng chỉ số cũ.");
        }

        LeaseRentCalculator.LeaseTermBreakDown breakdown = LeaseRentCalculator.calculateLeaseBreakdown(
                contract.startDate(), contract.endDate(), contract.rent()
        );

        LeaseRentCalculator.BillingPeriodQuote targetPeriod = resolveTargetPeriod(breakdown, req.periodIndex(), req.month());
        String billingMonth = (req.month() != null && !req.month().isBlank()) ? req.month().trim() : targetPeriod.billingMonth();

        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE contract_id = ? AND billing_month = ? AND kind = 'MONTHLY'",
                Integer.class,
                contractId, billingMonth
        );
        if (existingCount != null && existingCount > 0) {
            throw AppException.conflict("DUPLICATE_INVOICE", "Hợp đồng đã có hóa đơn thuê cho kỳ/tháng này.");
        }

        int electricUsage = req.newElectric() - req.oldElectric();
        int waterUsage = req.newWater() - req.oldWater();
        long rentAmount = targetPeriod.rentAmount();
        long electricCost = electricUsage * contract.electricRate();
        long waterCost = waterUsage * contract.waterRate();
        long total = rentAmount + electricCost + waterCost;

        String invoiceId = UUID.randomUUID().toString();
        String invoiceCode = "HĐ-" + invoiceId.substring(0, 8).toUpperCase();

        jdbcTemplate.update(
                """
                INSERT INTO invoices (
                    id, invoice_code, contract_id, billing_month, period_start, period_end,
                    days_in_period, month_basis_days, kind, status,
                    old_electric, new_electric, old_water, new_water, total_amount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MONTHLY', 'UNPAID', ?, ?, ?, ?, ?)
                """,
                invoiceId, invoiceCode, contractId, billingMonth,
                Date.valueOf(targetPeriod.periodStart()), Date.valueOf(targetPeriod.periodEnd()),
                targetPeriod.daysInPeriod(), targetPeriod.monthBasisDays(),
                req.oldElectric(), req.newElectric(), req.oldWater(), req.newWater(), total
        );

        String rentLineTitle = targetPeriod.fullMonth()
                ? "Tiền thuê (chu kỳ " + targetPeriod.daysInPeriod() + "/" + targetPeriod.monthBasisDays() + " ngày)"
                : "Tiền thuê (" + targetPeriod.daysInPeriod() + "/" + targetPeriod.monthBasisDays() + " ngày)";

        insertInvoiceLine(invoiceId, 1, rentLineTitle, rentAmount);
        insertInvoiceLine(invoiceId, 2, "Tiền điện (" + electricUsage + " kWh)", electricCost);
        insertInvoiceLine(invoiceId, 3, "Tiền nước (" + waterUsage + " m³)", waterCost);

        return getInvoiceById(invoiceId);
    }

    public List<InvoiceDto> listInvoicesForActor(AuthUser actor) {
        String ownerOrTenantCol = "OWNER".equalsIgnoreCase(actor.role()) ? "c.owner_id" : "c.tenant_id";
        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT i.id
                FROM invoices i
                JOIN contracts c ON c.id = i.contract_id
                WHERE """ + " " + ownerOrTenantCol + " = ? ORDER BY i.created_at DESC, i.id DESC",
                String.class,
                actor.id()
        );
        return ids.stream().map(this::getInvoiceById).toList();
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

    @Transactional
    public InvoiceDto confirmInvoicePaid(AuthUser actor, String invoiceId, LocalDate todayInput) {
        if (!"OWNER".equalsIgnoreCase(actor.role())) {
            throw AppException.forbidden("Chỉ chủ nhà mới có quyền xác nhận đã thu tiền hóa đơn.");
        }
        InvoiceDto invoice = getInvoiceForActor(actor, invoiceId);
        if ("paid".equalsIgnoreCase(invoice.status())) {
            return invoice;
        }
        LocalDate paidDate = todayInput != null ? todayInput : VietnamTime.today();
        jdbcTemplate.update(
                "UPDATE invoices SET status = 'PAID', paid_at = ?, confirmed_by = ? WHERE id = ?",
                Date.valueOf(paidDate), actor.id(), invoiceId
        );
        return getInvoiceById(invoiceId);
    }

    private LeaseRentCalculator.BillingPeriodQuote resolveTargetPeriod(
            LeaseRentCalculator.LeaseTermBreakDown breakdown,
            Integer periodIndex,
            String billingMonth
    ) {
        if (periodIndex != null) {
            return breakdown.periods().stream()
                    .filter(p -> p.periodIndex() == periodIndex)
                    .findFirst()
                    .orElseThrow(() -> AppException.badRequest("INVALID_PERIOD_INDEX", "Kỳ hóa đơn nằm ngoài thời hạn hợp đồng."));
        }
        if (billingMonth != null && !billingMonth.isBlank()) {
            Optional<LeaseRentCalculator.BillingPeriodQuote> match = breakdown.periods().stream()
                    .filter(p -> p.billingMonth().equals(billingMonth.trim()))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            throw AppException.badRequest("MONTH_OUT_OF_RANGE", "Tháng lập hóa đơn nằm ngoài chu kỳ thời hạn hợp đồng.");
        }
        return breakdown.periods().get(0);
    }

    private void upsertEarlyMoveInPenaltyIfApplicable(ContractDto contract, LocalDate newMoveIn) {
        List<String> existingPenalties = jdbcTemplate.queryForList(
                "SELECT id FROM invoices WHERE contract_id = ? AND kind = 'EARLY_MOVE_IN'",
                String.class,
                contract.id()
        );
        if (!existingPenalties.isEmpty()) {
            String penaltyId = existingPenalties.get(0);
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM invoices WHERE id = ?",
                    String.class,
                    penaltyId
            );
            if ("PAID".equalsIgnoreCase(status) && !java.util.Objects.equals(newMoveIn, contract.actualMoveIn())) {
                throw AppException.conflict("PENALTY_ALREADY_PAID", "Hóa đơn phạt đã được xác nhận thu tiền.");
            }
            jdbcTemplate.update("DELETE FROM invoices WHERE id = ?", penaltyId);
        }

        if (newMoveIn != null && newMoveIn.isBefore(contract.startDate())) {
            String invoiceId = UUID.randomUUID().toString();
            String code = "HĐ-P" + invoiceId.substring(0, 7).toUpperCase();
            String month = newMoveIn.toString().substring(0, 7);
            int basisDays = LeaseRentCalculator.monthBasisDays(newMoveIn.getMonthValue());
            jdbcTemplate.update(
                    """
                    INSERT INTO invoices (
                        id, invoice_code, contract_id, billing_month, period_start, period_end,
                        days_in_period, month_basis_days, kind, status, total_amount
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'EARLY_MOVE_IN', 'UNPAID', ?)
                    """,
                    invoiceId, code, contract.id(), month,
                    Date.valueOf(newMoveIn), Date.valueOf(contract.startDate().minusDays(1)),
                    basisDays, contract.rent()
            );
            insertInvoiceLine(invoiceId, 1, "Phạt nhận phòng trước ngày thỏa thuận", contract.rent());
        }
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
                SELECT id, invoice_code, contract_id, billing_month, period_start, period_end,
                       days_in_period, month_basis_days, kind, status,
                       old_electric, new_electric, old_water, new_water, total_amount, paid_at
                FROM invoices
                WHERE id = ?
                """,
                (rs, rowNum) -> new InvoiceDto(
                        rs.getString("id"),
                        rs.getString("invoice_code"),
                        rs.getString("contract_id"),
                        rs.getString("billing_month"),
                        rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate(),
                        rs.getInt("days_in_period"),
                        rs.getInt("month_basis_days"),
                        "EARLY_MOVE_IN".equals(rs.getString("kind")) ? "early-move-in" : "monthly",
                        rs.getString("status").toLowerCase(),
                        (Integer) rs.getObject("old_electric"),
                        (Integer) rs.getObject("new_electric"),
                        (Integer) rs.getObject("old_water"),
                        (Integer) rs.getObject("new_water"),
                        rs.getLong("total_amount"),
                        rs.getDate("paid_at") != null ? rs.getDate("paid_at").toLocalDate() : null,
                        lines
                ),
                invoiceId
        );
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
        jdbcTemplate.update(
                """
                INSERT INTO contract_status_history (
                    id, contract_id, from_status, to_status, from_payment_status, to_payment_status,
                    action_type, actor_id, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), contractId, fromStatus, toStatus, fromPayment, toPayment,
                actionType, actorId, note
        );
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
                c.deposit_forfeited,
                c.cancelled_at,
                c.cancellation_reason,
                c.renewal_choice,
                c.renewal_responded_at
            FROM contracts c
            JOIN users ut ON ut.id = c.tenant_id
            JOIN users uo ON uo.id = c.owner_id
            """;

    private static RowMapper<ContractDto> contractRowMapper(LocalDate today) {
        return (rs, rowNum) -> {
            LocalDate startDate = rs.getDate("start_date").toLocalDate();
            LocalDate endDate = rs.getDate("end_date").toLocalDate();
            long rent = rs.getLong("monthly_rent");
            String status = rs.getString("status").toLowerCase();
            boolean expired = "signed".equals(status) && endDate.isBefore(today);
            boolean renewalOpen = "signed".equals(status) && !endDate.isAfter(today.plusDays(30));
            LeaseRentCalculator.LeaseTermBreakDown breakdown =
                    LeaseRentCalculator.calculateLeaseBreakdown(startDate, endDate, rent);
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
                    rs.getLong("electric_rate"),
                    rs.getLong("water_rate"),
                    status,
                    rs.getString("payment_status").toLowerCase(),
                    rs.getTimestamp("appointment_at") != null ? rs.getTimestamp("appointment_at").toLocalDateTime() : null,
                    rs.getTimestamp("signed_at") != null ? rs.getTimestamp("signed_at").toLocalDateTime() : null,
                    rs.getString("signed_confirmed_by"),
                    rs.getTimestamp("payment_confirmed_at") != null ? rs.getTimestamp("payment_confirmed_at").toLocalDateTime() : null,
                    rs.getString("payment_confirmed_by"),
                    rs.getDate("move_in_deadline").toLocalDate(),
                    rs.getDate("actual_move_in") != null ? rs.getDate("actual_move_in").toLocalDate() : null,
                    rs.getDate("actual_move_out") != null ? rs.getDate("actual_move_out").toLocalDate() : null,
                    rs.getBoolean("deposit_forfeited"),
                    rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toLocalDateTime() : null,
                    rs.getString("cancellation_reason"),
                    rs.getString("renewal_choice") != null ? rs.getString("renewal_choice").toLowerCase() : null,
                    rs.getTimestamp("renewal_responded_at") != null ? rs.getTimestamp("renewal_responded_at").toLocalDateTime() : null,
                    expired,
                    renewalOpen,
                    breakdown.firstPeriodRent(),
                    breakdown.lastPeriodRent()
            );
        };
    }
}
