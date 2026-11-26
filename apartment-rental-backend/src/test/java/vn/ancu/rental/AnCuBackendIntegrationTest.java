package vn.ancu.rental;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import vn.ancu.rental.apartment.BuildingDto;
import vn.ancu.rental.apartment.OwnerDashboardDto;
import vn.ancu.rental.apartment.OwnerDashboardService;
import vn.ancu.rental.apartment.OwnerPropertyService;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.common.VietnamTime;
import vn.ancu.rental.contract.ContractDto;
import vn.ancu.rental.contract.ContractHistoryDto;
import vn.ancu.rental.contract.ContractLifecycleService;
import vn.ancu.rental.contract.InvoiceDto;
import vn.ancu.rental.contract.LeaseRentCalculator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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
class AnCuBackendIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContractLifecycleService contractService;

    @Autowired
    private OwnerPropertyService ownerPropertyService;

    @Autowired
    private OwnerDashboardService ownerDashboardService;

    @Autowired
    private vn.ancu.rental.health.HealthController healthController;

    @Autowired
    private TestClockSupport.MutableClock testClock;

    @AfterEach
    void resetClock() {
        testClock.reset();
    }

    @Test
    void emailOperationsFailClearlyUntilSmtpIsConfigured() throws Exception {
        mockMvc.perform(post("/api/auth/register/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Khách mới\",\"email\":\"newcustomer@gmail.com\",\"password\":\"Original123!\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_CONFIGURED"));
    }

    @Test
    void milestone1_healthAndSeedDataAreLoadedViaFlyway() throws Exception {
        ResponseEntity<Map<String, Object>> healthResponse = healthController.checkHealth();
        assertThat(healthResponse.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> health = healthResponse.getBody();
        assertThat(health).isNotNull();
        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("seededUsers")).isEqualTo(4);
        assertThat(health.get("databaseEngine")).isEqualTo("H2");

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.databaseEngine").value("H2"))
                .andExpect(jsonPath("$.seededUsers").value(4));
    }

    @Test
    void milestone2_authAndRoleSessionAndOwnershipIsolation() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/owner/apartments"))
                .andExpect(status().isUnauthorized());

        MvcResult ownerLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"thuha@ancu.vn\",\"password\":\"AnCu@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("o1"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.redirectPath").value("/owner"))
                .andReturn();

        TestClockSupport.AuthSession ownerA = TestClockSupport.extractAuthSession(ownerLogin);
        assertThat(ownerA.session()).isNotNull();
        assertThat(ownerA.csrfToken()).isNotBlank();

        mockMvc.perform(get("/api/owner/apartments").session(ownerA.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/owner/apartments/a2").session(ownerA.session()))
                .andExpect(status().isForbidden());

        mockMvc.perform(ownerA.attach(put("/api/owner/apartments/a2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buildingId\":\"b2\",\"room\":\"B-08.02\",\"title\":\"Sửa trái phép\",\"price\":10000000,\"area\":45,\"beds\":1,\"baths\":1,\"condition\":\"ready\"}"))
                .andExpect(status().isForbidden());

        MvcResult tenantLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"minhanh@ancu.vn\",\"password\":\"AnCu@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t1"))
                .andExpect(jsonPath("$.role").value("TENANT"))
                .andExpect(jsonPath("$.redirectPath").value("/tenant"))
                .andReturn();

        TestClockSupport.AuthSession tenantSession = TestClockSupport.extractAuthSession(tenantLogin);
        mockMvc.perform(get("/api/owner/apartments").session(tenantSession.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void milestone2_publicDiscoveryFiltersMaintenanceAndHiddenListings() throws Exception {
        mockMvc.perform(get("/api/public/apartments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.districtCounts['TP. Thủ Đức']").value(1))
                .andExpect(jsonPath("$.districtCounts['Quận 7']").value(1));

        mockMvc.perform(get("/api/public/apartments")
                        .param("districts", "TP. Thủ Đức", "Quận 7")
                        .param("minPrice", "10000000")
                        .param("maxPrice", "13000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value("a1"));

        mockMvc.perform(get("/api/public/apartments").param("query", "thao dien"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value("a1"));

        MvcResult ownerLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"o1\",\"password\":\"AnCu@123\"}"))
                .andReturn();
        TestClockSupport.AuthSession ownerSession = TestClockSupport.extractAuthSession(ownerLogin);

        mockMvc.perform(ownerSession.attach(patch("/api/owner/apartments/a1/publish"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false));

        mockMvc.perform(get("/api/public/apartments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/public/apartments/a1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void milestone3_and_decision1_snapshotImmutability_overlapPrevention_history_and_3DayNoShowCancellation() {
        AuthUser tenant1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
        AuthUser tenant2 = AuthUser.of("t2", "hoangnam@ancu.vn", "Lê Hoàng Nam", "TENANT");
        AuthUser owner1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
        testClock.fixTo(LocalDateTime.of(2026, 11, 14, 10, 0));
        LocalDate startDate = LocalDate.of(2026, 11, 20);

        ContractDto c1 = contractService.createContract(
                tenant1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, startDate, null, "MONTH", 1, null, null, null
                )
        );
        ContractDto c2 = contractService.createContract(
                tenant2,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, startDate, null, "MONTH", 1, null, null, null
                )
        );
        assertThat(c1.code()).isNotEqualTo(c2.code());
        assertThat(c1.moveInDeadline()).isEqualTo(LocalDate.of(2026, 11, 23));

        BuildingDto b1 = ownerPropertyService.getOwnerBuilding("o1", "b1");
        ownerPropertyService.updateBuilding(
                "o1", "b1",
                new OwnerPropertyService.BuildingUpsertRequest(
                        b1.name(), b1.district(), b1.address(), "Văn phòng mới Tầng 2", 4200L, 25000L
                )
        );
        ContractDto c1Reloaded = contractService.getContractForActor(tenant1, c1.id());
        assertThat(c1Reloaded.location()).isEqualTo(b1.location());
        assertThat(c1Reloaded.electricRate()).isEqualTo(3500L);
        assertThat(c1Reloaded.waterRate()).isEqualTo(20000L);

        LocalDateTime slot = LocalDateTime.of(2026, 11, 16, 9, 30);
        contractService.scheduleAppointment(tenant1, c1.id(), new ContractLifecycleService.ScheduleAppointmentRequest(slot));
        contractService.scheduleAppointment(tenant2, c2.id(), new ContractLifecycleService.ScheduleAppointmentRequest(slot));

        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                owner1, c1.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(false, true, 12_500_000L, null)
        )).hasMessageContaining("Hợp đồng chưa ký không thể chuyển sang Đã thanh toán");

        ContractDto signedC1 = contractService.confirmSignAndDepositByOwner(
                owner1,
                c1.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, "Đã ký và thu cọc tại điểm hẹn")
        );
        assertThat(signedC1.status()).isEqualTo("signed");
        assertThat(signedC1.payment()).isEqualTo("paid");
        assertThat(signedC1.depositReceivedAmount()).isEqualTo(12_500_000L);
        assertThat(signedC1.depositVerificationStatus()).isEqualTo("verified");
        assertThat(ownerPropertyService.getOwnerApartment("o1", "a1").published()).isFalse();

        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                owner1,
                c2.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, null)
        )).hasMessageContaining("trùng thời gian");
        assertThat(contractService.getContractForActor(tenant2, c2.id()).payment()).isEqualTo("unpaid");

        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                owner1,
                c1.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, null)
        )).hasMessageContaining("đã được xác nhận ký");

        List<ContractHistoryDto> historyBeforeCancel = contractService.getContractHistory(owner1, c1.id());
        assertThat(historyBeforeCancel).extracting(ContractHistoryDto::actionType)
                .containsExactly("CREATED", "SCHEDULED", "SIGN_AND_DEPOSIT_CONFIRMED");

        // Advance server clock to day 4 (24/11) -> overdue no-show cancellation succeeds
        testClock.fixTo(LocalDate.of(2026, 11, 24));
        ContractDto cancelled = contractService.cancelContractForNoShowAfterThreeDays(owner1, c1.id());
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(cancelled.depositForfeited()).isTrue();

        List<ContractHistoryDto> historyAfterCancel = contractService.getContractHistory(owner1, c1.id());
        assertThat(historyAfterCancel).hasSize(4);
        assertThat(historyAfterCancel.get(3).actionType()).isEqualTo("CANCELLED_NO_SHOW_3_DAYS");
        assertThat(historyAfterCancel.get(3).actorId()).isEqualTo("o1");
    }

    @Test
    void milestone4_and_decision2_rentCalculation_earlyMoveInPenalty_and_invoiceManagement() {
        long monthlyRent = 12_000_000L;

        assertThat(LeaseRentCalculator.monthBasisDays(4)).isEqualTo(30);
        assertThat(LeaseRentCalculator.monthBasisDays(5)).isEqualTo(31);
        assertThat(LeaseRentCalculator.monthBasisDays(2)).isEqualTo(30);

        LocalDate febStart = LocalDate.of(2026, 2, 10);
        LocalDate febOneMonthEnd = LeaseRentCalculator.calculateEndDateForTerm(febStart, "MONTH", 1);
        assertThat(febOneMonthEnd).isEqualTo(febStart.plusDays(29));
        LeaseRentCalculator.LeaseTermBreakDown febHalf =
                LeaseRentCalculator.calculateLeaseBreakdown(febStart, LocalDate.of(2026, 2, 24), monthlyRent);
        assertThat(febHalf.firstPeriodRent()).isEqualTo(6_000_000L);

        AuthUser tenant1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
        AuthUser owner1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
        AuthUser owner2 = AuthUser.of("o2", "giabao@ancu.vn", "Phạm Gia Bảo", "OWNER");
        LocalDate startDate = LocalDate.of(2026, 4, 10);
        LocalDate endDate = LocalDate.of(2026, 6, 19);

        testClock.fixTo(LocalDateTime.of(2026, 4, 1, 9, 0));
        // Owner creates custom-rent contract (12,000,000) for a1
        ContractDto contract = contractService.createContract(
                owner1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", tenant1.id(), startDate, endDate, "CUSTOM", 3, 12_000_000L, 12_000_000L, null
                )
        );
        contractService.scheduleAppointment(
                tenant1, contract.id(),
                new ContractLifecycleService.ScheduleAppointmentRequest(LocalDateTime.of(2026, 4, 5, 10, 0))
        );
        testClock.fixTo(LocalDateTime.of(2026, 4, 5, 11, 0));
        contractService.confirmSignAndDepositByOwner(
                owner1, contract.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_000_000L, null)
        );

        testClock.fixTo(LocalDateTime.of(2026, 4, 15, 10, 0));
        // Nhận phòng sớm (07/04 < 10/04) kèm chỉ số bàn giao ban đầu (100, 10) và trả phòng sớm (14/04 < 19/06)
        contractService.recordHandover(
                owner1, contract.id(),
                new ContractLifecycleService.HandoverRequest(LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 14), 100, 10)
        );
        // Cập nhật lại ngày nhận phòng sớm (08/04) khi hóa đơn phạt còn UNPAID vẫn giữ đúng 1 hóa đơn phạt
        contractService.recordHandover(
                owner1, contract.id(),
                new ContractLifecycleService.HandoverRequest(LocalDate.of(2026, 4, 8), LocalDate.of(2026, 4, 14), 100, 10)
        );

        // Lập hóa đơn tuần tự: Kỳ 1 -> Kỳ 2 -> Kỳ 3
        InvoiceDto firstInvoice = contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 100, 120, 10, 13)
        );
        assertThat(firstInvoice.periodIndex()).isEqualTo(1);
        assertThat(firstInvoice.lines().get(0).amount()).isEqualTo(12_000_000L);

        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 120, 130, 13, 15)
        )).hasMessageContaining("đã có hóa đơn");

        // Bỏ qua kỳ 2 để lập kỳ 3 phải bị từ chối (P0-04)
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-06", 3, 120, 130, 13, 15)
        )).hasMessageContaining("tuần tự");

        InvoiceDto secondInvoice = contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-05", 2, 120, 145, 13, 16)
        );
        assertThat(secondInvoice.periodIndex()).isEqualTo(2);
        assertThat(secondInvoice.lines().get(0).amount()).isEqualTo(12_000_000L);

        InvoiceDto lastInvoice = contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-06", 3, 145, 155, 16, 18)
        );
        assertThat(lastInvoice.periodIndex()).isEqualTo(3);
        assertThat(lastInvoice.lines().get(0).amount()).isEqualTo(4_000_000L);

        List<InvoiceDto> ownerInvoices = contractService.listInvoicesForActor(owner1).stream()
                .filter(i -> i.contractId().equals(contract.id()))
                .toList();
        List<InvoiceDto> penalties = ownerInvoices.stream().filter(i -> "early-move-in".equals(i.kind())).toList();
        assertThat(penalties).hasSize(1);
        assertThat(penalties.get(0).totalAmount()).isEqualTo(12_000_000L);

        assertThatThrownBy(() -> contractService.confirmInvoicePaid(owner2, firstInvoice.id()))
                .hasMessageContaining("không có quyền");

        InvoiceDto paidInvoice = contractService.confirmInvoicePaid(owner1, firstInvoice.id());
        assertThat(paidInvoice.status()).isEqualTo("paid");
        assertThat(paidInvoice.paidAt()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(paidInvoice.confirmedBy()).isEqualTo("o1");
    }

    @Test
    void milestone5_and_6_renewalFlow_republishListing_ownerDashboard_and_favorites() throws Exception {
        AuthUser tenant1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
        AuthUser owner1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");

        List<ContractDto> tenantNotices = contractService.listRenewalNotices(tenant1);
        assertThat(tenantNotices).extracting(ContractDto::id).contains("HD-1001");

        ContractDto responded = contractService.respondRenewal(
                tenant1, "HD-1001", new ContractLifecycleService.RenewalResponseRequest("requested")
        );
        assertThat(responded.renewal()).isEqualTo("requested");
        assertThat(responded.renewalRespondedAt()).isNotNull();

        OwnerDashboardDto dashboardBefore = ownerDashboardService.getDashboard(owner1);
        assertThat(dashboardBefore.notificationCount()).isEqualTo(1);
        assertThat(dashboardBefore.expiringContracts()).extracting(ContractDto::id).contains("HD-1001");

        ContractDto renewalContract = contractService.createContract(
                owner1,
                new ContractLifecycleService.CreateContractRequest(
                        "a5", "t1", responded.endDate().plusDays(1), null,
                        "MONTH", 6, responded.rent(), responded.rent(), "HD-1001"
                )
        );
        assertThat(renewalContract.id()).isNotEqualTo("HD-1001");
        assertThat(renewalContract.renewalOf()).isEqualTo("HD-1001");
        assertThat(renewalContract.status()).isEqualTo("awaiting");

        ownerPropertyService.updatePublishStatus("o2", "a6", new OwnerPropertyService.PublishToggleRequest(true));
        mockMvc.perform(get("/api/public/apartments/a6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a6"))
                .andExpect(jsonPath("$.published").value(true));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"t1\",\"password\":\"AnCu@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        TestClockSupport.AuthSession tenantSession = TestClockSupport.extractAuthSession(loginResult);

        mockMvc.perform(tenantSession.attach(post("/api/favorites/a1/toggle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.favoriteIds[0]").value("a1"));

        mockMvc.perform(get("/api/favorites").session(tenantSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("a1"));
    }

    @Test
    void contractManagement_ownerSchedule_updateAwaiting_and_cancelAwaiting() throws Exception {
        AuthUser tenant1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
        AuthUser owner1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
        testClock.fixTo(LocalDateTime.of(2026, 11, 18, 9, 0));
        LocalDate startDate = LocalDate.of(2026, 11, 20);

        ContractDto created = contractService.createContract(
                owner1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", "t1", startDate, null, "MONTH", 6, 12_500_000L, 12_500_000L, null
                )
        );
        assertThat(created.status()).isEqualTo("awaiting");

        LocalDateTime slot = LocalDateTime.of(2026, 11, 19, 14, 0);
        ContractDto scheduledByOwner = contractService.scheduleAppointment(
                owner1, created.id(),
                new ContractLifecycleService.ScheduleAppointmentRequest(slot)
        );
        assertThat(scheduledByOwner.appointment()).isEqualTo(slot);

        ContractDto updated = contractService.updateAwaitingContract(
                owner1, created.id(),
                new ContractLifecycleService.UpdateContractRequest(
                        LocalDate.of(2026, 11, 22), null, "YEAR", 1, 12_000_000L, null
                )
        );
        assertThat(updated.startDate()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(updated.leaseTermUnit()).isEqualTo("YEAR");
        assertThat(updated.leaseTermValue()).isEqualTo(1);
        assertThat(updated.rent()).isEqualTo(12_000_000L);
        assertThat(updated.deposit()).isEqualTo(12_000_000L);

        ContractDto cancelledAwaiting = contractService.cancelAwaitingContract(
                tenant1, created.id(),
                new ContractLifecycleService.CancelAwaitingContractRequest("Khách thuê đổi kế hoạch thời gian thuê")
        );
        assertThat(cancelledAwaiting.status()).isEqualTo("cancelled");
        assertThat(cancelledAwaiting.depositForfeited()).isFalse();
        assertThat(cancelledAwaiting.cancellationReason()).contains("Khách thuê đổi kế hoạch");

        List<ContractHistoryDto> history = contractService.getContractHistory(owner1, created.id());
        assertThat(history).extracting(ContractHistoryDto::actionType)
                .containsExactly("CREATED", "SCHEDULED", "UPDATED", "CANCELLED_AWAITING");

        testClock.reset();
        MvcResult ownerLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"thuha@ancu.vn\",\"password\":\"AnCu@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        TestClockSupport.AuthSession ownerSession = TestClockSupport.extractAuthSession(ownerLogin);

        LocalDate futureStart = VietnamTime.today().plusDays(1);
        LocalDateTime futureSlot = VietnamTime.today().plusDays(1).atTime(10, 30);

        ContractDto recreated = contractService.createContract(
                tenant1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, futureStart, null, "MONTH", 6, null, null, null
                )
        );
        assertThat(recreated.status()).isEqualTo("awaiting");

        mockMvc.perform(ownerSession.attach(post("/api/contracts/" + recreated.id() + "/schedule"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointment\":\"" + futureSlot + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("awaiting"));

        mockMvc.perform(ownerSession.attach(put("/api/contracts/" + recreated.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"" + futureStart.plusDays(1) + "\",\"leaseTermUnit\":\"MONTH\",\"leaseTermValue\":6,\"rent\":12200000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rent").value(12200000));

        mockMvc.perform(ownerSession.attach(post("/api/contracts/" + recreated.id() + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Chủ nhà hủy dự thảo chờ ký\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.depositForfeited").value(false));
    }
}
