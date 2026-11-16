package vn.ancu.rental;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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

    @Test
    void milestone1_healthAndSeedDataAreLoadedViaFlyway() throws Exception {
        Map<String, Object> health = healthController.checkHealth();
        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("seededUsers")).isEqualTo(4);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
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

        MockHttpSession ownerASession = (MockHttpSession) ownerLogin.getRequest().getSession(false);
        assertThat(ownerASession).isNotNull();

        mockMvc.perform(get("/api/owner/apartments").session(ownerASession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // Chủ nhà A bị từ chối khi truy cập/sửa căn hộ của Chủ nhà B dù biết mã bản ghi
        mockMvc.perform(get("/api/owner/apartments/a2").session(ownerASession))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/owner/apartments/a2")
                        .session(ownerASession)
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

        MockHttpSession tenantSession = (MockHttpSession) tenantLogin.getRequest().getSession(false);
        mockMvc.perform(get("/api/owner/apartments").session(tenantSession))
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
        MockHttpSession ownerSession = (MockHttpSession) ownerLogin.getRequest().getSession(false);

        mockMvc.perform(patch("/api/owner/apartments/a1/publish")
                        .session(ownerSession)
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
        LocalDate today = LocalDate.of(2026, 11, 14);
        LocalDate startDate = LocalDate.of(2026, 11, 20);

        // Lập 2 hợp đồng chờ ký của 2 khách thuê cho cùng căn a1 và cùng kỳ thuê
        ContractDto c1 = contractService.createContract(
                tenant1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, startDate, null, "MONTH", 1, 12_500_000L, 12_500_000L, null
                ),
                today
        );
        ContractDto c2 = contractService.createContract(
                tenant2,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, startDate, null, "MONTH", 1, 12_500_000L, 12_500_000L, null
                ),
                today
        );
        assertThat(c1.code()).isNotEqualTo(c2.code());
        assertThat(c1.moveInDeadline()).isEqualTo(LocalDate.of(2026, 11, 23));

        // Sửa địa điểm ký và đơn giá điện nước của tòa nhà b1 về sau KHÔNG làm đổi nội dung hợp đồng c1 đã lập
        BuildingDto b1 = ownerPropertyService.getOwnerBuilding("o1", "b1");
        ownerPropertyService.updateBuilding(
                "o1", "b1",
                new OwnerPropertyService.BuildingUpsertRequest(
                        b1.name(), b1.district(), b1.address(), "Văn phòng mới Tầng 2", 4200L, 25000L
                )
        );
        ContractDto c1Reloaded = contractService.getContractForActor(tenant1, c1.id(), today);
        assertThat(c1Reloaded.location()).isEqualTo(b1.location());
        assertThat(c1Reloaded.electricRate()).isEqualTo(3500L);
        assertThat(c1Reloaded.waterRate()).isEqualTo(20000L);

        // Đặt lịch ký cho cả 2 hợp đồng: trạng thái vẫn là awaiting & unpaid
        LocalDateTime slot = LocalDateTime.of(2026, 11, 16, 9, 30);
        LocalDateTime now = LocalDateTime.of(2026, 11, 14, 10, 0);
        contractService.scheduleAppointment(tenant1, c1.id(), new ContractLifecycleService.ScheduleAppointmentRequest(slot, now));
        contractService.scheduleAppointment(tenant2, c2.id(), new ContractLifecycleService.ScheduleAppointmentRequest(slot, now));

        // Hợp đồng chưa ký không thể chuyển sang Đã thanh toán
        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                owner1, c1.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(false, true, 12_500_000L, null), today
        )).hasMessageContaining("Hợp đồng chưa ký không thể chuyển sang Đã thanh toán");

        // Người môi giới / Chủ nhà xác nhận ký và thu tiền cọc cho c1 thành công
        ContractDto signedC1 = contractService.confirmSignAndDepositByOwner(
                owner1,
                c1.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, "Đã ký và thu cọc tại điểm hẹn"),
                today
        );
        assertThat(signedC1.status()).isEqualTo("signed");
        assertThat(signedC1.payment()).isEqualTo("paid");
        assertThat(ownerPropertyService.getOwnerApartment("o1", "a1").published()).isFalse();

        // Yêu cầu ký thứ 2 (c2) trùng căn hộ và kỳ thuê bị từ chối, vẫn unpaid
        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                owner1,
                c2.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, null),
                today
        )).hasMessageContaining("trùng thời gian");
        assertThat(contractService.getContractForActor(tenant2, c2.id(), today).payment()).isEqualTo("unpaid");

        // Bấm xác nhận ký lại trên c1 bị từ chối và không ghi thêm lần ký vào lịch sử
        assertThatThrownBy(() -> contractService.confirmSignAndDepositByOwner(
                owner1,
                c1.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_500_000L, null),
                today
        )).hasMessageContaining("đã được xác nhận ký");

        List<ContractHistoryDto> historyBeforeCancel = contractService.getContractHistory(owner1, c1.id());
        assertThat(historyBeforeCancel).extracting(ContractHistoryDto::actionType)
                .containsExactly("CREATED", "SCHEDULED", "SIGN_AND_DEPOSIT_CONFIRMED");

        // Sau 3 ngày kể từ ngày dọn vào quy định (20/11 -> ngày 24/11) khách không nhận phòng:
        // Hợp đồng bị hủy (cancelled) và khách mất tiền đặt cọc (depositForfeited = true)
        ContractDto cancelled = contractService.cancelContractForNoShowAfterThreeDays(
                owner1, c1.id(), LocalDate.of(2026, 11, 24)
        );
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

        // Tháng 4 (30 ngày), Tháng 5 (31 ngày), Tháng 2 (luôn tính 30 ngày kể từ ngày bắt đầu trên hợp đồng)
        assertThat(LeaseRentCalculator.monthBasisDays(4)).isEqualTo(30);
        assertThat(LeaseRentCalculator.monthBasisDays(5)).isEqualTo(31);
        assertThat(LeaseRentCalculator.monthBasisDays(2)).isEqualTo(30);

        LocalDate febStart = LocalDate.of(2026, 2, 10);
        LocalDate febOneMonthEnd = LeaseRentCalculator.calculateEndDateForTerm(febStart, "MONTH", 1);
        assertThat(febOneMonthEnd).isEqualTo(febStart.plusDays(29)); // 30 ngày tính từ 10/02/2026
        LeaseRentCalculator.LeaseTermBreakDown febHalf =
                LeaseRentCalculator.calculateLeaseBreakdown(febStart, LocalDate.of(2026, 2, 24), monthlyRent);
        assertThat(febHalf.firstPeriodRent()).isEqualTo(6_000_000L); // 15/30 ngày

        // Tạo hợp đồng, nhận phòng sớm và trả phòng sớm
        AuthUser tenant1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
        AuthUser owner1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
        AuthUser owner2 = AuthUser.of("o2", "giabao@ancu.vn", "Phạm Gia Bảo", "OWNER");
        LocalDate today = LocalDate.of(2026, 4, 15);
        LocalDate startDate = LocalDate.of(2026, 4, 10);
        LocalDate endDate = LocalDate.of(2026, 6, 19); // 2 tháng tròn + kỳ cuối 10 ngày trong tháng 6 (30 ngày)

        ContractDto contract = contractService.createContract(
                tenant1,
                new ContractLifecycleService.CreateContractRequest(
                        "a1", null, startDate, endDate, "CUSTOM", 3, 12_000_000L, 12_000_000L, null
                ),
                LocalDate.of(2026, 4, 1)
        );
        contractService.scheduleAppointment(
                tenant1, contract.id(),
                new ContractLifecycleService.ScheduleAppointmentRequest(
                        LocalDateTime.of(2026, 4, 5, 10, 0),
                        LocalDateTime.of(2026, 4, 1, 9, 0)
                )
        );
        contractService.confirmSignAndDepositByOwner(
                owner1, contract.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, 12_000_000L, null),
                LocalDate.of(2026, 4, 5)
        );

        // Nhận phòng sớm (07/04 < 10/04) và trả phòng sớm (14/04 < 19/06)
        contractService.recordHandover(
                owner1, contract.id(),
                new ContractLifecycleService.HandoverRequest(LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 14)),
                today
        );
        // Cập nhật lại ngày nhận phòng sớm (08/04) vẫn chỉ giữ đúng 1 hóa đơn phạt bằng 1 tháng tiền thuê
        contractService.recordHandover(
                owner1, contract.id(),
                new ContractLifecycleService.HandoverRequest(LocalDate.of(2026, 4, 8), LocalDate.of(2026, 4, 14)),
                today
        );

        // Lập hóa đơn kỳ đầu (10/04 -> 09/05): trả phòng sớm vẫn giữ nguyên tiền thuê 12.000.000 ₫
        InvoiceDto firstInvoice = contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 100, 120, 10, 13)
        );
        assertThat(firstInvoice.lines().get(0).amount()).isEqualTo(12_000_000L);

        // Không được lập hóa đơn trùng cho cùng kỳ/tháng
        assertThatThrownBy(() -> contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-04", 1, 120, 130, 13, 15)
        )).hasMessageContaining("đã có hóa đơn");

        // Lập hóa đơn kỳ cuối (10/06 -> 19/06, 10/30 ngày): 4.000.000 ₫
        InvoiceDto lastInvoice = contractService.createPeriodInvoice(
                owner1, contract.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-06", 3, 120, 130, 13, 15)
        );
        assertThat(lastInvoice.lines().get(0).amount()).isEqualTo(4_000_000L);

        // Kiểm tra đúng 1 khoản phạt nhận phòng sớm bằng 1 tháng tiền thuê
        List<InvoiceDto> ownerInvoices = contractService.listInvoicesForActor(owner1).stream()
                .filter(i -> i.contractId().equals(contract.id()))
                .toList();
        List<InvoiceDto> penalties = ownerInvoices.stream().filter(i -> "early-move-in".equals(i.kind())).toList();
        assertThat(penalties).hasSize(1);
        assertThat(penalties.get(0).totalAmount()).isEqualTo(12_000_000L);

        // Chủ nhà B (o2) không được xác nhận thu tiền hóa đơn của Chủ nhà A (o1)
        assertThatThrownBy(() -> contractService.confirmInvoicePaid(owner2, firstInvoice.id(), today))
                .hasMessageContaining("không có quyền");

        // Chủ nhà A xác nhận đã thu tiền hóa đơn
        InvoiceDto paidInvoice = contractService.confirmInvoicePaid(owner1, firstInvoice.id(), today);
        assertThat(paidInvoice.status()).isEqualTo("paid");
        assertThat(paidInvoice.paidAt()).isEqualTo(today);
    }

    @Test
    void milestone5_and_6_renewalFlow_republishListing_ownerDashboard_and_favorites() throws Exception {
        AuthUser tenant1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
        AuthUser owner1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
        AuthUser owner2 = AuthUser.of("o2", "giabao@ancu.vn", "Phạm Gia Bảo", "OWNER");
        LocalDate today = VietnamTime.today();

        // 1. HD-1001 của Minh Anh sắp hết hạn trong 8 ngày -> xuất hiện trong danh sách nhắc gia hạn 30 ngày
        List<ContractDto> tenantNotices = contractService.listRenewalNotices(tenant1, today);
        assertThat(tenantNotices).extracting(ContractDto::id).contains("HD-1001");

        // 2. Minh Anh phản hồi Muốn gia hạn -> lưu lựa chọn và thời điểm phản hồi
        ContractDto responded = contractService.respondRenewal(
                tenant1, "HD-1001", new ContractLifecycleService.RenewalResponseRequest("requested"), today
        );
        assertThat(responded.renewal()).isEqualTo("requested");
        assertThat(responded.renewalRespondedAt()).isNotNull();

        // 3. Chủ nhà Trần Thu Hà thấy thông báo yêu cầu gia hạn và lập hợp đồng kỳ mới liên kết với HD-1001
        OwnerDashboardDto dashboardBefore = ownerDashboardService.getDashboard(owner1, today);
        assertThat(dashboardBefore.notificationCount()).isEqualTo(1);
        assertThat(dashboardBefore.expiringContracts()).extracting(ContractDto::id).contains("HD-1001");

        ContractDto renewalContract = contractService.createContract(
                owner1,
                new ContractLifecycleService.CreateContractRequest(
                        "a5", "t1", responded.endDate().plusDays(1), null,
                        "MONTH", 6, responded.rent(), responded.rent(), "HD-1001"
                ),
                today
        );
        assertThat(renewalContract.id()).isNotEqualTo("HD-1001");
        assertThat(renewalContract.renewalOf()).isEqualTo("HD-1001");
        assertThat(renewalContract.status()).isEqualTo("awaiting");

        // 4. HD-1002 của Lê Hoàng Nam đã hết hạn và chọn Không gia hạn -> Chủ nhà Phạm Gia Bảo mở lại tin đăng căn a6
        ownerPropertyService.updatePublishStatus("o2", "a6", new OwnerPropertyService.PublishToggleRequest(true));
        mockMvc.perform(get("/api/public/apartments/a6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a6"))
                .andExpect(jsonPath("$.published").value(true));

        // 5. Lưu căn hộ yêu thích theo tài khoản đăng nhập (Mốc 6)
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"t1\",\"password\":\"AnCu@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession tenantSession = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(post("/api/favorites/a1/toggle").session(tenantSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.favoriteIds[0]").value("a1"));

        mockMvc.perform(get("/api/favorites").session(tenantSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("a1"));
    }
}
