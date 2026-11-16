package vn.ancu.rental;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.common.VietnamTime;
import vn.ancu.rental.contract.ContractDto;
import vn.ancu.rental.contract.ContractLifecycleService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ancu.contracts.no-show-cron=* * * * * *",
        "spring.datasource.url=jdbc:h2:mem:ancu_no_show_test;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE"
})
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class NoShowCancellationIntegrationTest {
    private static final AuthUser OWNER = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
    private static final AuthUser OTHER_OWNER = AuthUser.of("o2", "giabao@ancu.vn", "Phạm Gia Bảo", "OWNER");
    private static final AuthUser TENANT = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");

    @Autowired
    private ContractLifecycleService contracts;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void schedulerCancelsOnlyOverdueSignedContractsWithoutMoveIn() {
        LocalDate today = VietnamTime.today();
        LocalDate overdueStart = today.minusDays(4);
        List<ContractDto> fixtures = transactions.execute(transaction -> {
            ContractDto overdue = createContract("a1", OWNER, overdueStart, true);
            ContractDto dueToday = createContract("a3", OWNER, today.minusDays(3), true);
            ContractDto movedIn = createContract("a2", OTHER_OWNER, overdueStart, true);
            ContractDto unsigned = createContract("a4", OTHER_OWNER, overdueStart, false);
            contracts.recordHandover(
                    OTHER_OWNER, movedIn.id(), new ContractLifecycleService.HandoverRequest(overdueStart, null), today
            );
            return List.of(overdue, dueToday, movedIn, unsigned);
        });

        ContractDto overdue = fixtures.get(0);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            ContractDto cancelled = contracts.getContractForActor(TENANT, overdue.id(), today);
            assertThat(cancelled.status()).isEqualTo("cancelled");
            assertThat(cancelled.depositForfeited()).isTrue();
            assertThat(cancelled.cancelledAt()).isNotNull();
        });

        assertThat(contracts.autoCancelExpiredNoShowContracts(today)).isZero();
        assertThat(contracts.getContractHistory(TENANT, overdue.id()))
                .filteredOn(entry -> "CANCELLED_NO_SHOW_3_DAYS".equals(entry.actionType()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.actorId()).isNull();
                    assertThat(entry.actorName()).isEqualTo("Hệ thống");
                });
        assertThat(fixtures.subList(1, fixtures.size()).stream()
                .map(contract -> contracts.getContractForActor(TENANT, contract.id(), today)))
                .extracting(ContractDto::status, ContractDto::depositForfeited)
                .containsExactly(
                        tuple("signed", false),
                        tuple("signed", false),
                        tuple("awaiting", false)
                );
    }

    private ContractDto createContract(String apartmentId, AuthUser owner, LocalDate startDate, boolean sign) {
        LocalDate signingDay = startDate.minusDays(1);
        ContractDto contract = contracts.createContract(
                owner,
                new ContractLifecycleService.CreateContractRequest(
                        apartmentId, TENANT.id(), startDate, null, "MONTH", 1, null, null, null
                ),
                signingDay.minusDays(1)
        );
        if (!sign) {
            return contract;
        }
        contracts.scheduleAppointment(TENANT, contract.id(), new ContractLifecycleService.ScheduleAppointmentRequest(
                signingDay.atTime(10, 0), signingDay.atTime(9, 0)
        ));
        return contracts.confirmSignAndDepositByOwner(
                owner, contract.id(),
                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, contract.deposit(), null),
                signingDay
        );
    }
}
