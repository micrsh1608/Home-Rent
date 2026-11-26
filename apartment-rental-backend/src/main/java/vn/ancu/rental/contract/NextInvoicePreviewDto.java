package vn.ancu.rental.contract;

import java.time.LocalDate;

public record NextInvoicePreviewDto(
        String contractId,
        boolean billable,
        String blockedReason,
        Integer periodIndex,
        String billingMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer daysInPeriod,
        Integer monthBasisDays,
        Long rentAmount,
        Integer expectedOldElectric,
        Integer expectedOldWater,
        String meterSource
) {
}
