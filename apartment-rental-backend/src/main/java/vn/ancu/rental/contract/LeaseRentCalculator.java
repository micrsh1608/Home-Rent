package vn.ancu.rental.contract;

import vn.ancu.rental.common.AppException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Thực thi Quyết định 2 đã chốt:
 * - Tính tiền thuê kỳ đầu, các kỳ giữa và kỳ cuối bắt đầu từ ngày dọn vào trên hợp đồng (startDate)
 *   tính theo 1 tháng, 1 năm hay thời gian khách hàng muốn thuê.
 * - 1 tháng tùy theo tháng có 30 hay 31 ngày; riêng đối với tháng 2 vẫn tính theo 30 ngày
 *   kể từ ngày bắt đầu trên hợp đồng.
 * - Khác với trường hợp khách tự trả phòng sớm (trả phòng sớm vẫn giữ nguyên tiền thuê theo thỏa thuận).
 */
public final class LeaseRentCalculator {

    private LeaseRentCalculator() {
    }

    public record BillingPeriodQuote(
            int periodIndex,
            String billingMonth,
            LocalDate periodStart,
            LocalDate periodEnd,
            int daysInPeriod,
            int monthBasisDays,
            boolean fullMonth,
            long rentAmount
    ) {
    }

    public record LeaseTermBreakDown(
            LocalDate startDate,
            LocalDate endDate,
            long monthlyRent,
            int totalPeriods,
            long firstPeriodRent,
            long lastPeriodRent,
            long totalContractRent,
            List<BillingPeriodQuote> periods
    ) {
    }

    /**
     * Trả về số ngày quy ước của 1 tháng dựa vào tháng bắt đầu chu kỳ:
     * - Tháng 1, 3, 5, 7, 8, 10, 12: 31 ngày
     * - Tháng 4, 6, 9, 11: 30 ngày
     * - Tháng 2 (dù năm thường hay năm nhuận): luôn tính 30 ngày kể từ ngày bắt đầu.
     */
    public static int monthBasisDays(int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> 31;
            case 4, 6, 9, 11 -> 30;
            case 2 -> 30;
            default -> throw new IllegalArgumentException("Tháng không hợp lệ: " + month);
        };
    }

    /**
     * Tính ngày kết thúc hợp đồng khi khách chọn thuê theo số tháng (MONTH) hoặc số năm (YEAR)
     * bắt đầu từ ngày dọn vào trên hợp đồng (startDate).
     */
    public static LocalDate calculateEndDateForTerm(LocalDate startDate, String unit, int value) {
        if (startDate == null || value <= 0) {
            throw AppException.badRequest("INVALID_TERM", "Kiểm tra lại ngày bắt đầu và thời hạn thuê.");
        }
        int totalMonths = switch (unit == null ? "MONTH" : unit.toUpperCase()) {
            case "MONTH" -> value;
            case "YEAR" -> value * 12;
            default -> throw AppException.badRequest("INVALID_TERM_UNIT", "Đơn vị thời hạn thuê phải là MONTH hoặc YEAR.");
        };

        LocalDate cursor = startDate;
        for (int i = 0; i < totalMonths; i++) {
            int days = monthBasisDays(cursor.getMonthValue());
            cursor = cursor.plusDays(days);
        }
        return cursor.minusDays(1);
    }

    /**
     * Tính chi tiết tất cả các kỳ thuê (kỳ đầu, các kỳ tiếp theo, kỳ cuối) kể từ ngày dọn vào trên hợp đồng (startDate).
     */
    public static LeaseTermBreakDown calculateLeaseBreakdown(LocalDate startDate, LocalDate endDate, long monthlyRent) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate) || monthlyRent <= 0) {
            throw AppException.badRequest("INVALID_CONTRACT_TERMS", "Kiểm tra lại thời hạn và tiền thuê.");
        }

        List<BillingPeriodQuote> periods = new ArrayList<>();
        LocalDate cursor = startDate;
        int index = 1;

        while (!cursor.isAfter(endDate)) {
            int basisDays = monthBasisDays(cursor.getMonthValue());
            LocalDate fullPeriodEnd = cursor.plusDays(basisDays - 1L);
            String billingMonth = cursor.toString().substring(0, 7);

            if (!endDate.isBefore(fullPeriodEnd)) {
                periods.add(new BillingPeriodQuote(
                        index,
                        billingMonth,
                        cursor,
                        fullPeriodEnd,
                        basisDays,
                        basisDays,
                        true,
                        monthlyRent
                ));
                cursor = fullPeriodEnd.plusDays(1);
            } else {
                int actualDays = (int) (ChronoUnit.DAYS.between(cursor, endDate) + 1);
                long proratedRent = Math.round((double) monthlyRent * actualDays / basisDays);
                periods.add(new BillingPeriodQuote(
                        index,
                        billingMonth,
                        cursor,
                        endDate,
                        actualDays,
                        basisDays,
                        actualDays == basisDays,
                        proratedRent
                ));
                break;
            }
            index++;
        }

        long firstRent = periods.get(0).rentAmount();
        long lastRent = periods.get(periods.size() - 1).rentAmount();
        long totalRent = periods.stream().mapToLong(BillingPeriodQuote::rentAmount).sum();

        return new LeaseTermBreakDown(
                startDate,
                endDate,
                monthlyRent,
                periods.size(),
                firstRent,
                lastRent,
                totalRent,
                periods
        );
    }
}
