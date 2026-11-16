package vn.ancu.rental.contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.ancu.rental.common.VietnamTime;

@Component
public class NoShowCancellationJob {
    private static final Logger log = LoggerFactory.getLogger(NoShowCancellationJob.class);

    private final ContractLifecycleService contracts;

    public NoShowCancellationJob(ContractLifecycleService contracts) {
        this.contracts = contracts;
    }

    @Scheduled(cron = "${ancu.contracts.no-show-cron:0 * * * * *}", zone = VietnamTime.ZONE_ID)
    public void cancelOverdueContracts() {
        int cancelled = contracts.autoCancelExpiredNoShowContracts(VietnamTime.today());
        if (cancelled > 0) {
            log.info("Automatically cancelled {} overdue move-in contract(s)", cancelled);
        }
    }
}
