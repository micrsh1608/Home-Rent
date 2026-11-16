package vn.ancu.rental.contract;

import java.time.LocalDate;
import java.util.List;

public record InvoiceDto(
        String id,
        String code,
        String contractId,
        String month,
        LocalDate periodStart,
        LocalDate periodEnd,
        int daysInPeriod,
        int monthBasisDays,
        String kind,
        String status,
        Integer oldElectric,
        Integer newElectric,
        Integer oldWater,
        Integer newWater,
        long totalAmount,
        LocalDate paidAt,
        List<InvoiceLineDto> lines
) {
    public record InvoiceLineDto(String title, long amount) {
    }
}
