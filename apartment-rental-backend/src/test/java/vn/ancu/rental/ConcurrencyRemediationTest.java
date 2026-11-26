package vn.ancu.rental;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ancu.rental.apartment.ApartmentDto;
import vn.ancu.rental.apartment.OwnerPropertyService;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.contract.ContractDto;
import vn.ancu.rental.contract.ContractLifecycleService;
import vn.ancu.rental.contract.InvoiceDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm thử đồng thời thực tế với các transaction độc lập (không bọc @Transactional ở lớp test)
 * và CountDownLatch barrier:
 * Bao phủ T01, T03, T04, T10, T12, T13 (concurrent), T16, T18 trên H2.
 * Lưu ý: H2 không thay thế bằng chứng khóa SQL Server; xem thêm SqlServerVerificationIntegrationTest cho T02/SQL Server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:ancu_concurrency_test;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@Import(TestClockSupport.MutableClockTestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class ConcurrencyRemediationTest {

    private static final AuthUser OWNER1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
    private static final AuthUser TENANT1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
    private static final AuthUser TENANT2 = AuthUser.of("t2", "hoangnam@ancu.vn", "Lê Hoàng Nam", "TENANT");

    @Autowired ContractLifecycleService contractService;
    @Autowired OwnerPropertyService ownerPropertyService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestClockSupport.MutableClock testClock;

    @AfterEach
    void resetClock() {
        testClock.reset();
    }

    @Test
    void t01_concurrentOverlappingSignAllowsExactlyOneAndKeepsRejectedAwaiting() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt = createFreshApartment();

        ContractDto c1 = createScheduledContract(apt.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));
        ContractDto c2 = createScheduledContract(apt.id(), TENANT2, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14));

        var results = runConcurrently(
                () -> contractService.confirmSignAndDepositByOwner(
                        OWNER1, c1.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c1.deposit(), null)),
                () -> contractService.confirmSignAndDepositByOwner(
                        OWNER1, c2.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c2.deposit(), null))
        );

        long successes = results.stream().filter(Outcome::succeeded).count();
        long failures = results.stream().filter(r -> !r.succeeded()).count();
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(1);

        ContractDto r1 = contractService.getContractForActor(OWNER1, c1.id());
        ContractDto r2 = contractService.getContractForActor(OWNER1, c2.id());
        assertThat(List.of(r1.status(), r2.status())).containsExactlyInAnyOrder("signed", "awaiting");
        assertThat(ownerPropertyService.getOwnerApartment("o1", apt.id()).published()).isFalse();
    }

    @Test
    void t03_concurrentSignOnDifferentApartmentsBothSucceed() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt1 = createFreshApartment();
        ApartmentDto apt2 = createFreshApartment();

        ContractDto c1 = createScheduledContract(apt1.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));
        ContractDto c2 = createScheduledContract(apt2.id(), TENANT2, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));

        var results = runConcurrently(
                () -> contractService.confirmSignAndDepositByOwner(
                        OWNER1, c1.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c1.deposit(), null)),
                () -> contractService.confirmSignAndDepositByOwner(
                        OWNER1, c2.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c2.deposit(), null))
        );

        assertThat(results).allMatch(Outcome::succeeded);
    }

    @Test
    void t04_midTransactionFailureRollsBackContractListingAndHistory() {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt = createFreshApartment();
        ContractDto c = createScheduledContract(apt.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));
        int historyBefore = contractService.getContractHistory(OWNER1, c.id()).size();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            contractService.confirmSignAndDepositByOwner(
                    OWNER1, c.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c.deposit(), null));
            throw new IllegalStateException("Simulated mid-transaction crash");
        })).hasMessageContaining("Simulated mid-transaction crash");

        ContractDto afterRollback = contractService.getContractForActor(OWNER1, c.id());
        assertThat(afterRollback.status()).isEqualTo("awaiting");
        assertThat(afterRollback.payment()).isEqualTo("unpaid");
        assertThat(afterRollback.depositReceivedAmount()).isNull();
        assertThat(ownerPropertyService.getOwnerApartment("o1", apt.id()).published()).isTrue();
        assertThat(contractService.getContractHistory(OWNER1, c.id())).hasSize(historyBefore);
    }

    @Test
    void t10_concurrentPeriodInvoiceCreationProducesExactlyOneInvoiceAndThreeLines() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt = createFreshApartment();
        ContractDto c = createSignedContract(apt.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 11, 10));

        testClock.fixTo(LocalDate.of(2026, 9, 10));
        contractService.recordCheckIn(OWNER1, c.id(),
                new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 9, 10), 100, 10));

        var results = runConcurrently(
                () -> contractService.createPeriodInvoice(OWNER1, c.id(),
                        new ContractLifecycleService.CreateInvoiceRequest("2026-09", 1, 100, 150, 10, 15)),
                () -> contractService.createPeriodInvoice(OWNER1, c.id(),
                        new ContractLifecycleService.CreateInvoiceRequest("2026-09", 1, 100, 160, 10, 16))
        );

        assertThat(results.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.succeeded()).count()).isEqualTo(1);

        List<InvoiceDto> monthly = contractService.listInvoicesForActor(OWNER1).stream()
                .filter(i -> i.contractId().equals(c.id()) && "monthly".equals(i.kind()))
                .toList();
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).lines()).hasSize(3);
    }

    @Test
    void t12_concurrentCheckInAndNoShowCancellationEndInConsistentState() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt = createFreshApartment();
        ContractDto c = createSignedContract(apt.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));

        // On day 4 (2026-09-14), attempt check-in with valid move-in date 2026-09-12 concurrently with no-show cancellation
        testClock.fixTo(LocalDate.of(2026, 9, 14));
        var results = runConcurrently(
                () -> contractService.recordCheckIn(OWNER1, c.id(),
                        new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 9, 12), 100, 10)),
                () -> contractService.cancelContractForNoShowAfterThreeDays(OWNER1, c.id())
        );

        assertThat(results.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        ContractDto finalState = contractService.getContractForActor(OWNER1, c.id());
        if ("signed".equals(finalState.status())) {
            assertThat(finalState.actualMoveIn()).isEqualTo(LocalDate.of(2026, 9, 12));
            assertThat(finalState.depositForfeited()).isFalse();
        } else {
            assertThat(finalState.status()).isEqualTo("cancelled");
            assertThat(finalState.actualMoveIn()).isNull();
            assertThat(finalState.depositForfeited()).isTrue();
        }
    }

    @Test
    void t13_concurrentHandoverAndConfirmEarlyPenaltyPaidNeverDeletesPaidPenalty() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt = createFreshApartment();
        ContractDto c = createSignedContract(apt.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));

        testClock.fixTo(LocalDate.of(2026, 9, 15));
        contractService.recordCheckIn(OWNER1, c.id(),
                new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 9, 7), 100, 10));
        InvoiceDto penalty = contractService.listInvoicesForActor(OWNER1).stream()
                .filter(i -> i.contractId().equals(c.id()) && "early-move-in".equals(i.kind()))
                .findFirst().orElseThrow();

        // Race: owner tries to move check-in to on-time (which would remove UNPAID penalty) vs owner confirming penalty PAID
        runConcurrently(
                () -> contractService.recordCheckIn(OWNER1, c.id(),
                        new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 9, 10), 100, 10)),
                () -> contractService.confirmInvoicePaid(OWNER1, penalty.id())
        );

        ContractDto finalContract = contractService.getContractForActor(OWNER1, c.id());
        List<InvoiceDto> penalties = contractService.listInvoicesForActor(OWNER1).stream()
                .filter(i -> i.contractId().equals(c.id()) && "early-move-in".equals(i.kind()))
                .toList();
        if (LocalDate.of(2026, 9, 7).equals(finalContract.actualMoveIn())) {
            // Confirm-paid won -> PAID penalty MUST still exist intact
            assertThat(penalties).hasSize(1);
            assertThat(penalties.get(0).status()).isEqualTo("paid");
            assertThat(penalties.get(0).confirmedBy()).isEqualTo("o1");
        } else {
            // Check-in update to on-time won first -> penalty removed while UNPAID
            assertThat(penalties).isEmpty();
        }
    }

    @Test
    void t16_p1_10_concurrentRenewalMaintenanceAndAwaitingMutationsMaintainInvariants() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));

        // 1. Two concurrent renewals of the same base contract -> strictly 1 succeeds
        ApartmentDto aptRenew = createFreshApartment();
        ContractDto base = createSignedContract(aptRenew.id(), TENANT1, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20));
        contractService.respondRenewal(TENANT1, base.id(), new ContractLifecycleService.RenewalResponseRequest("requested"));

        var renewResults = runConcurrently(
                () -> contractService.createContract(OWNER1, new ContractLifecycleService.CreateContractRequest(
                        aptRenew.id(), TENANT1.id(), LocalDate.of(2026, 9, 21), null, "MONTH", 1, null, null, base.id())),
                () -> contractService.createContract(OWNER1, new ContractLifecycleService.CreateContractRequest(
                        aptRenew.id(), TENANT1.id(), LocalDate.of(2026, 9, 21), null, "MONTH", 1, null, null, base.id()))
        );
        assertThat(renewResults.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        Integer renewalChildren = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contracts WHERE renewal_of_id = ? AND status <> 'CANCELLED'",
                Integer.class, base.id());
        assertThat(renewalChildren).isEqualTo(1);

        // 2. Maintenance switch vs signing on the same apartment -> mutually exclusive!
        ApartmentDto aptMaint = createFreshApartment();
        ContractDto awaiting = createScheduledContract(aptMaint.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));
        var maintResults = runConcurrently(
                () -> contractService.confirmSignAndDepositByOwner(
                        OWNER1, awaiting.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, awaiting.deposit(), null)),
                () -> ownerPropertyService.updateApartment("o1", aptMaint.id(), new OwnerPropertyService.ApartmentUpsertRequest(
                        aptMaint.buildingId(), aptMaint.room(), aptMaint.title(), aptMaint.price(), aptMaint.area(),
                        aptMaint.beds(), aptMaint.baths(), "maintenance", aptMaint.description(), aptMaint.image()))
        );
        assertThat(maintResults.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        ApartmentDto finalApt = ownerPropertyService.getOwnerApartment("o1", aptMaint.id());
        ContractDto finalAwaiting = contractService.getContractForActor(OWNER1, awaiting.id());
        assertThat("maintenance".equals(finalApt.condition()) && "signed".equals(finalAwaiting.status())).isFalse();

        // 3. Sign vs Cancel awaiting -> mutually exclusive!
        ApartmentDto aptCancel = createFreshApartment();
        ContractDto cCancel = createScheduledContract(aptCancel.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));
        var cancelResults = runConcurrently(
                () -> contractService.confirmSignAndDepositByOwner(
                        OWNER1, cCancel.id(), new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, cCancel.deposit(), null)),
                () -> contractService.cancelAwaitingContract(TENANT1, cCancel.id(),
                        new ContractLifecycleService.CancelAwaitingContractRequest("Đổi ý"))
        );
        assertThat(cancelResults.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(contractService.getContractForActor(OWNER1, cCancel.id()).status()).isIn("signed", "cancelled");
    }

    @Test
    void t18_p1_12_repeatedAndConcurrentConfirmInvoicePaidIsIdempotentAndPreservesMetadata() throws Exception {
        testClock.fixTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        ApartmentDto apt = createFreshApartment();
        ContractDto c = createSignedContract(apt.id(), TENANT1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9));

        testClock.fixTo(LocalDate.of(2026, 9, 10));
        contractService.recordCheckIn(OWNER1, c.id(),
                new ContractLifecycleService.CheckInRequest(LocalDate.of(2026, 9, 10), 100, 10));
        InvoiceDto inv = contractService.createPeriodInvoice(OWNER1, c.id(),
                new ContractLifecycleService.CreateInvoiceRequest("2026-09", 1, 100, 150, 10, 15));

        // 4 concurrent confirm-paid calls
        testClock.fixTo(LocalDate.of(2026, 9, 15));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<InvoiceDto>> futures = List.of(1, 2, 3, 4).stream().map(i -> pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return contractService.confirmInvoicePaid(OWNER1, inv.id());
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<InvoiceDto> f : futures) {
                InvoiceDto res = f.get(10, TimeUnit.SECONDS);
                assertThat(res.status()).isEqualTo("paid");
                assertThat(res.paidAt()).isEqualTo(LocalDate.of(2026, 9, 15));
                assertThat(res.confirmedBy()).isEqualTo("o1");
            }
        } finally {
            pool.shutdownNow();
        }

        // Subsequent call on a later date MUST preserve original paid_at (2026-09-15), NOT overwrite with 2026-09-20
        testClock.fixTo(LocalDate.of(2026, 9, 20));
        InvoiceDto repeated = contractService.confirmInvoicePaid(OWNER1, inv.id());
        assertThat(repeated.paidAt()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(repeated.confirmedBy()).isEqualTo("o1");
    }

    private ApartmentDto createFreshApartment() {
        String room = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return ownerPropertyService.createApartment("o1", new OwnerPropertyService.ApartmentUpsertRequest(
                "b1", room, "Test Apt " + room, 11_000_000L, new BigDecimal("50.00"), 2, 1,
                "ready", "Test apartment", "river", null, true
        ));
    }

    private ContractDto createScheduledContract(String apartmentId, AuthUser tenant, LocalDate start, LocalDate end) {
        ContractDto created = contractService.createContract(
                OWNER1,
                new ContractLifecycleService.CreateContractRequest(apartmentId, tenant.id(), start, end, "CUSTOM", 1, null, null, null)
        );
        contractService.scheduleAppointment(tenant, created.id(),
                new ContractLifecycleService.ScheduleAppointmentRequest(LocalDateTime.of(2026, 9, 2, 10, 0)));
        return contractService.getContractForActor(OWNER1, created.id());
    }

    private ContractDto createSignedContract(String apartmentId, AuthUser tenant, LocalDate start, LocalDate end) {
        ContractDto awaiting = createScheduledContract(apartmentId, tenant, start, end);
        return contractService.confirmSignAndDepositByOwner(
                OWNER1, awaiting.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, awaiting.deposit(), null)
        );
    }

    private record Outcome(Object value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    private List<Outcome> runConcurrently(Callable<?> task1, Callable<?> task2) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Outcome> wrap1 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome(task1.call(), null);
                } catch (Throwable ex) {
                    return new Outcome(null, ex);
                }
            };
            Callable<Outcome> wrap2 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome(task2.call(), null);
                } catch (Throwable ex) {
                    return new Outcome(null, ex);
                }
            };
            Future<Outcome> f1 = pool.submit(wrap1);
            Future<Outcome> f2 = pool.submit(wrap2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
