package vn.ancu.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.jdbc.SqlScriptsTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import vn.ancu.rental.apartment.OwnerPropertyService;
import vn.ancu.rental.apartment.PublicApartmentSearchResponse;
import vn.ancu.rental.apartment.PublicApartmentService;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.contract.ContractDto;
import vn.ancu.rental.contract.ContractLifecycleService;
import vn.ancu.rental.contract.InvoiceDto;
import vn.ancu.rental.contract.LeaseRentCalculator;
import vn.ancu.rental.contract.NextInvoicePreviewDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bộ kiểm thử tích hợp bao phủ T03, T05, T06, T07, T08, T09, T11, T13, T14, T17, T19, T22, T23 và P2.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ancu_remediation_core;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Import(TestClockSupport.MutableClockTestConfig.class)
@AutoConfigureMockMvc
@Transactional
@TestExecutionListeners(
        listeners = {
                ServletTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class,
                TransactionalTestExecutionListener.class,
                SqlScriptsTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class RemediationCoreIntegrationTest {

    private static final AuthUser OWNER1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
    private static final AuthUser OWNER2 = AuthUser.of("o2", "giabao@ancu.vn", "Phạm Gia Bảo", "OWNER");
    private static final AuthUser TENANT1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
    private static final AuthUser TENANT2 = AuthUser.of("t2", "hoangnam@ancu.vn", "Lê Hoàng Nam", "TENANT");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContractLifecycleService contractService;
    @Autowired OwnerPropertyService ownerPropertyService;
    @Autowired PublicApartmentService publicApartmentService;
    @Autowired TestClockSupport.MutableClock testClock;

    @AfterEach
    void resetClock() {
        testClock.reset();
    }

    @Test
    void t03_nonOverlappingPeriodsOnSameApartmentBothSignCleanly() {
        testClock.fixTo(LocalDateTime.of(2026, 6, 1, 9, 0));
        ContractDto first = createAndSchedule(OWNER1, TENANT1, "a1", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 9));
        ContractDto second = createAndSchedule(OWNER1, TENANT2, "a1", LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 9));

        ContractDto s1 = contractService.confirmSignAndDepositByOwner(
                OWNER1, first.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, first.deposit(), null));
        ContractDto s2 = contractService.confirmSignAndDepositByOwner(
                OWNER1, second.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, second.deposit(), null));

        assertThat(s1.status()).isEqualTo("signed");
        assertThat(s2.status()).isEqualTo("signed");
    }

    @Test
    void t05_p0_02_depositAmountRequiredNonNegativeSufficientAndSupportsZeroAndExcessPolicy() {
        testClock.fixTo(LocalDateTime.of(2026, 6, 1, 9, 0));
        ContractDto c = createAndSchedule(OWNER1, TENANT1, "a1", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 9));
        int historyBefore = contractService.getContractHistory(OWNER1, c.id()).size();

        // null rejected
        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                OWNER1, c.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, null, null)))
                .hasMessageContaining("nhập số tiền đặt cọc thực nhận");

        // negative rejected
        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                OWNER1, c.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, -100L, null)))
                .hasMessageContaining("không hợp lệ");

        // insufficient rejected
        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                OWNER1, c.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c.deposit() - 1, null)))
                .hasMessageContaining("phải đủ mức tiền đặt cọc");

        // No history recorded on failed validations
        assertThat(contractService.getContractHistory(OWNER1, c.id())).hasSize(historyBefore);

        // Excess deposit allowed with explicit audit note and exact received amount persisted
        long excessAmount = c.deposit() + 500_000L;
        ContractDto signed = contractService.confirmSignAndDepositByOwner(
                OWNER1, c.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, excessAmount, null));
        assertThat(signed.status()).isEqualTo("signed");
        assertThat(signed.payment()).isEqualTo("paid");
        assertThat(signed.depositReceivedAmount()).isEqualTo(excessAmount);
        assertThat(signed.depositVerificationStatus()).isEqualTo("verified");
        assertThat(contractService.getContractHistory(OWNER1, c.id()).getLast().note())
                .contains("vượt mức cọc")
                .contains("đối soát riêng");

        // Zero deposit contract allowed ONLY with explicit receivedAmount = 0 and depositReceived = true
        ContractDto zeroDep = contractService.createContract(
                OWNER1,
                new ContractLifecycleService.CreateContractRequest(
                        "a3", TENANT2.id(), LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 9),
                        "CUSTOM", 1, 15_200_000L, 0L, null, LocalDateTime.of(2026, 6, 2, 10, 0)
                )
        );
        ContractDto signedZero = contractService.confirmSignAndDepositByOwner(
                OWNER1, zeroDep.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 0L, null));
        assertThat(signedZero.depositReceivedAmount()).isEqualTo(0L);
        assertThat(signedZero.depositVerificationStatus()).isEqualTo("verified");
    }

    @Test
    void t06_p0_03_tenantCannotTamperPriceOrDeposit_ownerIsolation_andSnapshotImmutability() {
        testClock.fixTo(LocalDateTime.of(2026, 6, 1, 9, 0));

        // TENANT forging rent=1000 rejected
        assertThatThrownBy(() -> contractService.createContract(
                TENANT1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 9),
                        "MONTH", 1, 1_000L, 12_500_000L, null)))
                .hasMessageContaining("không được tự thay đổi giá thuê");

        // TENANT forging deposit=0 rejected
        assertThatThrownBy(() -> contractService.createContract(
                TENANT1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 9),
                        "MONTH", 1, 12_500_000L, 0L, null)))
                .hasMessageContaining("không được tự thay đổi mức tiền đặt cọc");

        // TENANT creating contract without rent/deposit uses DB listing price (12,500,000)
        ContractDto c = contractService.createContract(
                TENANT1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, LocalDate.of(2026, 6, 10), null, "MONTH", 1, null, null, null));
        assertThat(c.rent()).isEqualTo(12_500_000L);
        assertThat(c.deposit()).isEqualTo(12_500_000L);

        // TENANT cannot alter rent when updating awaiting contract
        assertThatThrownBy(() -> contractService.updateAwaitingContract(
                TENANT1, c.id(),
                new ContractLifecycleService.UpdateContractRequest(
                        LocalDate.of(2026, 6, 12), null, "MONTH", 1, 5_000_000L, null)))
                .hasMessageContaining("không có quyền thay đổi giá thuê");

        // Non-owning OWNER2 cannot create contract on OWNER1's apartment a1
        assertThatThrownBy(() -> contractService.createContract(
                OWNER2,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", TENANT2.id(), LocalDate.of(2026, 8, 1), null, "MONTH", 1, 10_000_000L, 10_000_000L, null)))
                .hasMessageContaining("căn hộ của mình");

        // Sign c, then change apartment a1 listing price -> signed snapshot stays unchanged
        contractService.scheduleAppointment(TENANT1, c.id(),
                new ContractLifecycleService.ScheduleAppointmentRequest(LocalDateTime.of(2026, 6, 2, 10, 0)));
        contractService.confirmSignAndDepositByOwner(OWNER1, c.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, null));

        var unit = ownerPropertyService.getOwnerApartment("o1", "a1");
        ownerPropertyService.updateApartment("o1", "a1", new OwnerPropertyService.ApartmentUpsertRequest(
                unit.buildingId(), unit.room(), unit.title(), 19_000_000L, unit.area(), unit.beds(), unit.baths(),
                "ready", unit.description(), unit.image()
        ));
        ContractDto reloaded = contractService.getContractForActor(TENANT1, c.id());
        assertThat(reloaded.rent()).isEqualTo(12_500_000L);
        assertThat(reloaded.deposit()).isEqualTo(12_500_000L);
    }

    @Test
    void t07_t08_t09_p0_04_p0_05_meterContinuitySequentialBillingAndCanonicalPeriodIdentity() {
        testClock.fixTo(LocalDateTime.of(2026, 4, 1, 9, 0));
        ContractDto c = createAndSchedule(OWNER1, TENANT1, "a1", LocalDate.of(2026, 4, 10), LocalDate.of(2026, 7, 10));
        contractService.confirmSignAndDepositByOwner(OWNER1, c.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c.deposit(), null));

        // T08: Cannot bill Period 1 before verified handover meters are recorded
        NextInvoicePreviewDto blockedPreview = contractService.getNextInvoicePreview(OWNER1, c.id());
        assertThat(blockedPreview.billable()).isFalse();
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 0, 100, 0, 10)))
                .hasMessageContaining("chỉ số điện nước bàn giao đã xác minh");

        // Check-in WITHOUT initial meters is rejected
        testClock.fixTo(LocalDate.of(2026, 4, 10));
        assertThatThrownBy(() -> contractService.recordHandover(
                OWNER1, c.id(), new ContractLifecycleService.HandoverRequest(LocalDate.of(2026, 4, 10), null)))
                .hasMessageContaining("chỉ số điện và nước ban đầu");

        // Record check-in with initial electric=100, water=20
        ContractDto checkedIn = contractService.recordCheckIn(
                OWNER1, c.id(), new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 4, 10), 100, 20));
        assertThat(checkedIn.handoverElectricReading()).isEqualTo(100);
        assertThat(checkedIn.handoverWaterReading()).isEqualTo(20);
        assertThat(checkedIn.handoverMeterStatus()).isEqualTo("verified");

        NextInvoicePreviewDto p1Preview = contractService.getNextInvoicePreview(OWNER1, c.id());
        assertThat(p1Preview.billable()).isTrue();
        assertThat(p1Preview.periodIndex()).isEqualTo(1);
        assertThat(p1Preview.expectedOldElectric()).isEqualTo(100);
        assertThat(p1Preview.expectedOldWater()).isEqualTo(20);

        // T07: Wrong starting electric/water for Period 1 rejected
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 50, 200, 20, 30)))
                .hasMessageContaining("Chỉ số điện cũ");
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 100, 200, 10, 30)))
                .hasMessageContaining("Chỉ số nước cũ");

        // T09: Mismatched periodIndex=1 with month="2026-05" rejected
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-05", 1, 100, 200, 20, 30)))
                .hasMessageContaining("không khớp với kỳ thuê số 1");

        // Create Period 1 cleanly (100->200 electric, 20->30 water)
        InvoiceDto inv1 = contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 100, 200, 20, 30));
        assertThat(inv1.periodIndex()).isEqualTo(1);
        assertThat(inv1.month()).isEqualTo("2026-04");
        assertThat(inv1.totalAmount()).isEqualTo(12_500_000L + 100 * 3500L + 10 * 20_000L);

        // T09: Trying to create Period 1 again with another month rejected
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-05", 1, 200, 250, 30, 35)))
                .hasMessageContaining("không khớp với kỳ thuê số 1");
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest(null, 1, 200, 250, 30, 35)))
                .hasMessageContaining("đã có hóa đơn");

        // T08: Skipping Period 2 to Period 3 rejected
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-06", 3, 200, 300, 30, 40)))
                .hasMessageContaining("tuần tự");

        // T07: Period 2 sending discontinuous oldElectric=50 or oldWater=15 rejected
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-05", 2, 50, 300, 30, 40)))
                .hasMessageContaining("Chỉ số điện cũ");
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest("2026-05", 2, 200, 300, 15, 40)))
                .hasMessageContaining("Chỉ số nước cũ");

        // Period 2 succeeds with server-inferred old meters (passing null oldElectric/oldWater)
        InvoiceDto inv2 = contractService.createPeriodInvoice(
                OWNER1, c.id(), new ContractLifecycleService.CreateInvoiceRequest(null, 2, null, 300, null, 45));
        assertThat(inv2.periodIndex()).isEqualTo(2);
        assertThat(inv2.month()).isEqualTo("2026-05");
        assertThat(inv2.oldElectric()).isEqualTo(200);
        assertThat(inv2.oldWater()).isEqualTo(30);
        assertThat(inv2.totalAmount()).isEqualTo(12_500_000L + 100 * 3500L + 15 * 20_000L);

        // Handover meters cannot be altered after invoices exist
        assertThatThrownBy(() -> contractService.recordCheckIn(
                OWNER1, c.id(), new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 4, 10), 999, 20)))
                .hasMessageContaining("Không thể thay đổi chỉ số điện nước bàn giao");

        // T08: Legacy contract HD-1002 (LEGACY_UNVERIFIED handover meters, no invoices) cannot bill Period 1
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                OWNER2, "HD-1002", new ContractLifecycleService.CreateInvoiceRequest(null, 1, 0, 50, 0, 5)))
                .hasMessageContaining("chỉ số điện nước bàn giao đã xác minh");
    }

    @Test
    void t11_t13_p0_06_p0_07_handoverPreservesMoveInAndPaidEarlyPenaltyIsNeverDeleted() {
        testClock.fixTo(LocalDateTime.of(2026, 4, 1, 9, 0));
        ContractDto c = createAndSchedule(OWNER1, TENANT1, "a1", LocalDate.of(2026, 4, 10), LocalDate.of(2026, 6, 9));
        contractService.confirmSignAndDepositByOwner(OWNER1, c.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c.deposit(), null));

        testClock.fixTo(LocalDate.of(2026, 4, 15));
        // T11: Check-out before check-in rejected
        assertThatThrownBy(() -> contractService.recordCheckOut(
                OWNER1, c.id(), new ContractLifecycleService.CheckOutRequest(LocalDate.of(2026, 4, 14))))
                .hasMessageContaining("Cần ghi nhận ngày nhận phòng thực tế trước");

        // Future move-in rejected
        assertThatThrownBy(() -> contractService.recordCheckIn(
                OWNER1, c.id(), new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 4, 16), 100, 10)))
                .hasMessageContaining("hôm nay hoặc trước đó");

        // Early move-in (07/04 < 10/04) creates 1 EARLY_MOVE_IN penalty
        contractService.recordCheckIn(OWNER1, c.id(),
                new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 4, 7), 100, 10));

        InvoiceDto penalty = contractService.listInvoicesForActor(OWNER1).stream()
                .filter(i -> i.contractId().equals(c.id()) && "early-move-in".equals(i.kind()))
                .findFirst().orElseThrow();

        // Owner confirms early move-in penalty PAID
        InvoiceDto paidPenalty = contractService.confirmInvoicePaid(OWNER1, penalty.id());
        assertThat(paidPenalty.status()).isEqualTo("paid");
        assertThat(paidPenalty.paidAt()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(paidPenalty.confirmedBy()).isEqualTo("o1");

        // T11 + T13: Recording check-out via /check-out or /handover with null actualMoveIn:
        // 1) preserves actual_move_in (07/04) and handover meters (100, 10)
        // 2) preserves PAID early move-in penalty completely untouched (ID, status, paidAt, confirmedBy, lines)
        ContractDto afterCheckout = contractService.recordHandover(
                OWNER1, c.id(), new ContractLifecycleService.HandoverRequest(null, LocalDate.of(2026, 4, 14)));
        assertThat(afterCheckout.actualMoveIn()).isEqualTo(LocalDate.of(2026, 4, 7));
        assertThat(afterCheckout.actualMoveOut()).isEqualTo(LocalDate.of(2026, 4, 14));
        assertThat(afterCheckout.handoverElectricReading()).isEqualTo(100);
        assertThat(afterCheckout.handoverWaterReading()).isEqualTo(10);

        InvoiceDto penaltyAfter = contractService.getInvoiceForActor(OWNER1, paidPenalty.id());
        assertThat(penaltyAfter.id()).isEqualTo(paidPenalty.id());
        assertThat(penaltyAfter.status()).isEqualTo("paid");
        assertThat(penaltyAfter.totalAmount()).isEqualTo(paidPenalty.totalAmount());
        assertThat(penaltyAfter.paidAt()).isEqualTo(paidPenalty.paidAt());
        assertThat(penaltyAfter.confirmedBy()).isEqualTo(paidPenalty.confirmedBy());
        assertThat(penaltyAfter.lines()).isEqualTo(paidPenalty.lines());

        // T13: Changing actualMoveIn when penalty is PAID must be rejected
        assertThatThrownBy(() -> contractService.recordCheckIn(
                OWNER1, c.id(), new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 4, 10), 100, 10)))
                .hasMessageContaining("đã được xác nhận thu tiền");

        // T11: Idempotent repeat does not duplicate history
        int historyCount = contractService.getContractHistory(OWNER1, c.id()).size();
        contractService.recordHandover(OWNER1, c.id(),
                new ContractLifecycleService.HandoverRequest(LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 14), 100, 10));
        assertThat(contractService.getContractHistory(OWNER1, c.id())).hasSize(historyCount);
    }

    @Test
    void t14_p0_08_clientTodayAndNowSpoofingAreIgnoredByServerClock() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 11, 20, 10, 0));

        TestClockSupport.AuthSession ownerSession = loginHttp("thuha@ancu.vn", "AnCu@123");
        TestClockSupport.AuthSession tenantSession = loginHttp("minhanh@ancu.vn", "AnCu@123");

        // Create contract starting 2026-11-20 (deadline = 2026-11-23)
        MvcResult createRes = mockMvc.perform(tenantSession.attach(post("/api/contracts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apartmentId\":\"a1\",\"startDate\":\"2026-11-20\",\"leaseTermUnit\":\"MONTH\",\"leaseTermValue\":1}"))
                .andExpect(status().isCreated())
                .andReturn();
        String contractId = json.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        // Spoofed `now` in past cannot schedule a past appointment
        mockMvc.perform(tenantSession.attach(post("/api/contracts/" + contractId + "/schedule"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointment\":\"2026-11-19T10:00\",\"now\":\"2026-11-18T09:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT"));

        // Valid future appointment -> sign
        mockMvc.perform(tenantSession.attach(post("/api/contracts/" + contractId + "/schedule"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointment\":\"2026-11-20T15:00\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(ownerSession.attach(post("/api/contracts/" + contractId + "/confirm-sign-and-deposit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signedAtLocation\":true,\"depositReceived\":true,\"receivedAmount\":12500000}"))
                .andExpect(status().isOk());

        // Day 3 (2026-11-23): spoofed ?today=2099-01-01 MUST NOT cancel before grace period expires!
        testClock.fixTo(LocalDate.of(2026, 11, 23));
        mockMvc.perform(ownerSession.attach(post("/api/contracts/" + contractId + "/cancel-no-show"))
                        .param("today", "2099-01-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHIN_GRACE_PERIOD"));

        // Day 4 (2026-11-24): server clock passes deadline -> cancels cleanly
        testClock.fixTo(LocalDate.of(2026, 11, 24));
        mockMvc.perform(ownerSession.attach(post("/api/contracts/" + contractId + "/cancel-no-show")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.depositForfeited").value(true));
    }

    @Test
    void t17_p1_11_databaseConstraintsRejectInvalidStatesDirectlyAtDbLayer() {
        // 1. SIGNED + UNPAID rejected by ck_contracts_status_payment_strict
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO contracts (
                    id, contract_code, apartment_id, tenant_id, owner_id,
                    apartment_title, room_code, property_name, signing_location,
                    start_date, end_date, lease_term_unit, lease_term_value,
                    monthly_rent, deposit_amount, deposit_verification_status,
                    electric_rate, water_rate, status, payment_status,
                    signed_at, signed_confirmed_by, payment_confirmed_at, payment_confirmed_by,
                    move_in_deadline, handover_meter_status, deposit_forfeited
                ) VALUES (
                    'bad-c1', 'HD-BAD1', 'a1', 't1', 'o1',
                    'Title', 'A-12.06', 'Prop', 'Loc',
                    DATE '2026-12-01', DATE '2026-12-31', 'MONTH', 1,
                    10000000, 10000000, 'NOT_APPLICABLE',
                    3500, 20000, 'SIGNED', 'UNPAID',
                    CURRENT_TIMESTAMP, 'o1', CURRENT_TIMESTAMP, 'o1',
                    DATE '2026-12-04', 'NOT_RECORDED', 0
                )
                """)).isInstanceOf(DataIntegrityViolationException.class);

        // 2. actual_move_out without actual_move_in rejected by ck_contracts_handover_dates_strict
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE contracts SET actual_move_in = NULL, actual_move_out = DATE '2026-12-10', handover_meter_status = 'NOT_RECORDED' WHERE id = 'HD-1001'"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 3. deposit_forfeited = 1 on SIGNED contract rejected by ck_contracts_forfeit_requires_cancelled_paid
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE contracts SET deposit_forfeited = 1 WHERE id = 'HD-1001'"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 4. Duplicate MONTHLY period_index rejected by uq_invoices_contract_monthly_period
        Integer existingPeriod = jdbc.queryForObject("SELECT period_index FROM invoices WHERE id = 'INV-1001'", Integer.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO invoices (
                    id, invoice_code, contract_id, period_index, billing_month, period_start, period_end,
                    days_in_period, month_basis_days, kind, status,
                    old_electric, new_electric, old_water, new_water, meter_verification_status, total_amount
                ) VALUES (
                    'dup-inv', 'HĐ-DUP1', 'HD-1001', ?, '2026-12', DATE '2026-12-01', DATE '2026-12-30',
                    30, 30, 'MONTHLY', 'UNPAID',
                    785, 800, 41, 45, 'VERIFIED', 11200000
                )
                """, existingPeriod)).isInstanceOf(DataIntegrityViolationException.class);

        // 5. MONTHLY invoice with NULL meters rejected by ck_invoices_monthly_meters_strict
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO invoices (
                    id, invoice_code, contract_id, period_index, billing_month, period_start, period_end,
                    days_in_period, month_basis_days, kind, status,
                    old_electric, new_electric, old_water, new_water, meter_verification_status, total_amount
                ) VALUES (
                    'null-meter-inv', 'HĐ-NULLM', 'HD-1001', 1, '2026-07', DATE '2026-07-01', DATE '2026-07-30',
                    30, 30, 'MONTHLY', 'UNPAID',
                    NULL, NULL, NULL, NULL, 'VERIFIED', 11200000
                )
                """)).isInstanceOf(DataIntegrityViolationException.class);

        // 6. PAID invoice without confirmed_by rejected by ck_invoices_paid_metadata
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE invoices SET status = 'PAID', paid_at = DATE '2026-11-01', confirmed_by = NULL WHERE id = 'INV-1001'"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void t19_p1_13_csrfProtectionOriginValidationAuthorizationAndInputBounds() throws Exception {
        TestClockSupport.AuthSession owner1 = loginHttp("thuha@ancu.vn", "AnCu@123");
        TestClockSupport.AuthSession tenant1 = loginHttp("minhanh@ancu.vn", "AnCu@123");
        TestClockSupport.AuthSession tenant2 = loginHttp("hoangnam@ancu.vn", "AnCu@123");

        // Missing CSRF token on authenticated state-changing request -> 403 CSRF_INVALID
        mockMvc.perform(post("/api/favorites/a1/toggle").session(tenant1.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        // Wrong CSRF token -> 403 CSRF_INVALID
        mockMvc.perform(post("/api/favorites/a1/toggle")
                        .session(tenant1.session())
                        .header("X-CSRF-Token", "forged-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        // Evil Origin header -> 403 CSRF_ORIGIN_REJECTED
        mockMvc.perform(owner1.attach(post("/api/favorites/a1/toggle"))
                        .header("Origin", "https://evil.example.org"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ORIGIN_REJECTED"));

        // Valid CSRF token -> 200 OK
        mockMvc.perform(tenant1.attach(post("/api/favorites/a1/toggle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true));

        // Tenant B cannot view Tenant A's contract HD-1001 or invoice INV-1001
        mockMvc.perform(get("/api/contracts/HD-1001").session(tenant2.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/invoices/INV-1001").session(tenant2.session()))
                .andExpect(status().isForbidden());

        // Public /rent-preview rejects huge term / overflow inputs safely without stacktrace
        mockMvc.perform(get("/api/contracts/rent-preview")
                        .param("startDate", "2026-01-01")
                        .param("unit", "YEAR")
                        .param("value", "500")
                        .param("monthlyRent", "10000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TERM_TOO_LONG"));

        mockMvc.perform(get("/api/contracts/rent-preview")
                        .param("startDate", "2026-01-01")
                        .param("unit", "MONTH")
                        .param("value", "1")
                        .param("monthlyRent", "999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RENT_TOO_HIGH"));
    }

    @Test
    void t22_fullEndToEndHttpLifecycleThroughAllEndpoints() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 8, 1, 9, 0));
        TestClockSupport.AuthSession owner = loginHttp("thuha@ancu.vn", "AnCu@123");
        TestClockSupport.AuthSession tenant = loginHttp("minhanh@ancu.vn", "AnCu@123");

        // 1. Owner creates a new building & apartment
        String buildingId = json.readTree(mockMvc.perform(owner.attach(post("/api/owner/buildings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Skyline Thủ Đức\",\"district\":\"TP. Thủ Đức\",\"address\":\"10 Võ Nguyên Giáp\",\"electricRate\":3800,\"waterRate\":22000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        String aptId = json.readTree(mockMvc.perform(owner.attach(post("/api/owner/apartments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buildingId\":\"" + buildingId + "\",\"room\":\"S-10.01\",\"title\":\"Skyline Studio\",\"price\":10000000,\"area\":40,\"beds\":1,\"baths\":1,\"condition\":\"ready\",\"published\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        // 2. Tenant requests contract (without sending rent/deposit)
        String contractId = json.readTree(mockMvc.perform(tenant.attach(post("/api/contracts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apartmentId\":\"" + aptId + "\",\"startDate\":\"2026-08-10\",\"leaseTermUnit\":\"MONTH\",\"leaseTermValue\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rent").value(10_000_000))
                .andExpect(jsonPath("$.deposit").value(10_000_000))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // 3. Tenant schedules appointment
        mockMvc.perform(tenant.attach(post("/api/contracts/" + contractId + "/schedule"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointment\":\"2026-08-05T10:00\"}"))
                .andExpect(status().isOk());

        // 4. Owner confirms sign + actual received deposit
        mockMvc.perform(owner.attach(post("/api/contracts/" + contractId + "/confirm-sign-and-deposit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signedAtLocation\":true,\"depositReceived\":true,\"receivedAmount\":10000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("signed"))
                .andExpect(jsonPath("$.depositReceivedAmount").value(10_000_000))
                .andExpect(jsonPath("$.depositVerificationStatus").value("verified"));

        // 5. Owner records check-in with initial meters
        testClock.fixTo(LocalDate.of(2026, 8, 10));
        mockMvc.perform(owner.attach(post("/api/contracts/" + contractId + "/check-in"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualMoveIn\":\"2026-08-10\",\"initialElectric\":500,\"initialWater\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoverElectricReading").value(500))
                .andExpect(jsonPath("$.handoverWaterReading").value(50));

        // 6. Owner previews & creates Period 1 invoice, confirms paid
        mockMvc.perform(get("/api/contracts/" + contractId + "/next-invoice-preview").session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billable").value(true))
                .andExpect(jsonPath("$.periodIndex").value(1))
                .andExpect(jsonPath("$.expectedOldElectric").value(500));

        String inv1Id = json.readTree(mockMvc.perform(owner.attach(post("/api/contracts/" + contractId + "/invoices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodIndex\":1,\"newElectric\":550,\"newWater\":55}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodIndex").value(1))
                .andExpect(jsonPath("$.totalAmount").value(10_000_000 + 50 * 3800 + 5 * 22_000))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(owner.attach(post("/api/invoices/" + inv1Id + "/confirm-paid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.confirmedBy").value("o1"));

        // 7. Tenant responds renewal within 30 days & Owner creates renewal contract
        testClock.fixTo(LocalDate.of(2026, 9, 25));
        mockMvc.perform(tenant.attach(post("/api/contracts/" + contractId + "/renewal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"requested\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewal").value("requested"));

        mockMvc.perform(owner.attach(post("/api/contracts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apartmentId\":\"" + aptId + "\",\"tenantId\":\"t1\",\"startDate\":\"2026-10-10\",\"leaseTermUnit\":\"MONTH\",\"leaseTermValue\":6,\"renewalOf\":\"" + contractId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.renewalOf").value(contractId));
    }

    @Test
    void t23_rentCalculatorEdgeCases_februaryLeapAndNonLeap_monthEnd_singleDay() {
        // February in non-leap year 2026: 1 month = 30 days (Feb 1 -> Mar 2)
        assertThat(LeaseRentCalculator.calculateEndDateForTerm(LocalDate.of(2026, 2, 1), "MONTH", 1))
                .isEqualTo(LocalDate.of(2026, 3, 2));
        // February in leap year 2028: 1 month STILL 30 days (Feb 1 -> Mar 1)
        assertThat(LeaseRentCalculator.calculateEndDateForTerm(LocalDate.of(2028, 2, 1), "MONTH", 1))
                .isEqualTo(LocalDate.of(2028, 3, 1));
        assertThat(LeaseRentCalculator.calculateLeaseBreakdown(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1), 9_000_000L)
                .totalContractRent()).isEqualTo(9_000_000L);

        // Single-day lease in 31-day month (Jan 31 -> Jan 31): round(10_000_000 * 1 / 31) = 322_581
        LeaseRentCalculator.LeaseTermBreakDown oneDay =
                LeaseRentCalculator.calculateLeaseBreakdown(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31), 10_000_000L);
        assertThat(oneDay.totalPeriods()).isEqualTo(1);
        assertThat(oneDay.firstPeriodRent()).isEqualTo(Math.round(10_000_000.0 / 31));

        // 1-year lease produces 12 full monthly periods
        LocalDate yearEnd = LeaseRentCalculator.calculateEndDateForTerm(LocalDate.of(2026, 1, 15), "YEAR", 1);
        LeaseRentCalculator.LeaseTermBreakDown yearBreakdown =
                LeaseRentCalculator.calculateLeaseBreakdown(LocalDate.of(2026, 1, 15), yearEnd, 12_000_000L);
        assertThat(yearBreakdown.totalPeriods()).isEqualTo(12);
        assertThat(yearBreakdown.totalContractRent()).isEqualTo(144_000_000L);
    }

    @Test
    void p2_sqlPublicApartmentSearchSortingPaginationAndBatchInvoiceListing() {
        // Ascending sort + pagination in SQL
        PublicApartmentSearchResponse ascPage0 = publicApartmentService.searchPublicApartments(
                null, null, null, null, null, null, "ascending", 0, 2);
        assertThat(ascPage0.totalElements()).isEqualTo(4);
        assertThat(ascPage0.totalPages()).isEqualTo(2);
        assertThat(ascPage0.items()).hasSize(2);
        assertThat(ascPage0.items().get(0).price()).isLessThanOrEqualTo(ascPage0.items().get(1).price());

        PublicApartmentSearchResponse ascPage1 = publicApartmentService.searchPublicApartments(
                null, null, null, null, null, null, "ascending", 1, 2);
        assertThat(ascPage1.items()).hasSize(2);
        assertThat(ascPage0.items().get(1).price()).isLessThanOrEqualTo(ascPage1.items().get(0).price());

        // Area descending + minArea filter + Vietnamese accent-insensitive search ("thu duc")
        PublicApartmentSearchResponse vnSearch = publicApartmentService.searchPublicApartments(
                null, null, null, null, new BigDecimal("50"), "thu duc", "area", 0, 10);
        assertThat(vnSearch.totalElements()).isEqualTo(1);
        assertThat(vnSearch.items().get(0).id()).isEqualTo("a1");

        // Batch invoice listing preserves lines and order without N+1
        List<InvoiceDto> owner1Invoices = contractService.listInvoicesForActor(OWNER1);
        assertThat(owner1Invoices).isNotEmpty();
        assertThat(owner1Invoices.get(0).id()).isEqualTo("INV-1001");
        assertThat(owner1Invoices.get(0).lines()).hasSize(3);
    }

    private ContractDto createAndSchedule(AuthUser owner, AuthUser tenant, String aptId, LocalDate start, LocalDate end) {
        ContractDto created = contractService.createContract(
                owner,
                new ContractLifecycleService.CreateContractRequest(aptId, tenant.id(), start, end, "CUSTOM", 1, null, null, null)
        );
        contractService.scheduleAppointment(tenant, created.id(),
                new ContractLifecycleService.ScheduleAppointmentRequest(LocalDateTime.of(2026, 6, 2, 10, 0)));
        return contractService.getContractForActor(owner, created.id());
    }

    private TestClockSupport.AuthSession loginHttp(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return TestClockSupport.extractAuthSession(result);
    }
}
