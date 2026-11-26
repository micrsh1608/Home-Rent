package vn.ancu.rental.db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.ancu.rental.common.MigrationPreflightValidator;
import vn.ancu.rental.contract.LeaseRentCalculator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V6__BackfillIntegrityMetadata:
 * - Gắn nhãn minh bạch cho dữ liệu lịch sử (LEGACY_UNVERIFIED) thay vì bịa đặt số tiền cọc hoặc chỉ số bàn giao = 0.
 * - Backfill invoices.period_index cho mọi hóa đơn MONTHLY hiện có bằng đúng LeaseRentCalculator.
 * - Dừng và rollback migration nếu phát hiện hóa đơn MONTHLY trùng kỳ hoặc không thể ánh xạ duy nhất.
 */
public class V6__BackfillIntegrityMetadata extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V6__BackfillIntegrityMetadata.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        // 1. Backfill deposit_verification_status (never fabricate deposit_received_amount for legacy PAID rows)
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE contracts
                SET deposit_verification_status = CASE
                    WHEN payment_status = 'UNPAID' THEN 'NOT_APPLICABLE'
                    WHEN payment_status = 'PAID' AND deposit_received_amount IS NOT NULL THEN 'VERIFIED'
                    ELSE 'LEGACY_UNVERIFIED'
                END
                """)) {
            ps.executeUpdate();
        }

        // 2. Backfill handover_meter_status (never default legacy handover meters to 0)
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE contracts
                SET handover_meter_status = CASE
                    WHEN actual_move_in IS NULL THEN 'NOT_RECORDED'
                    WHEN handover_electric_reading IS NOT NULL AND handover_water_reading IS NOT NULL THEN 'VERIFIED'
                    ELSE 'LEGACY_UNVERIFIED'
                END
                """)) {
            ps.executeUpdate();
        }

        // 3. Early move-in invoices have period_index = NULL and meter_verification_status = 'NOT_APPLICABLE'
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE invoices
                SET period_index = NULL, meter_verification_status = 'NOT_APPLICABLE'
                WHERE kind = 'EARLY_MOVE_IN'
                """)) {
            ps.executeUpdate();
        }

        // 4. Backfill MONTHLY invoices period_index & meter_verification_status
        record ContractInfo(LocalDate startDate, LocalDate endDate, long rent, String handoverMeterStatus,
                            Integer handoverElectric, Integer handoverWater) {}
        Map<String, ContractInfo> contracts = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, start_date, end_date, monthly_rent, handover_meter_status,
                       handover_electric_reading, handover_water_reading
                FROM contracts
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                contracts.put(
                        rs.getString("id"),
                        new ContractInfo(
                                rs.getDate("start_date").toLocalDate(),
                                rs.getDate("end_date").toLocalDate(),
                                rs.getLong("monthly_rent"),
                                rs.getString("handover_meter_status"),
                                (Integer) rs.getObject("handover_electric_reading"),
                                (Integer) rs.getObject("handover_water_reading")
                        )
                );
            }
        }

        record MonthlyInv(String id, String contractId, String billingMonth, LocalDate periodStart, LocalDate periodEnd,
                          int oldElectric, int newElectric, int oldWater, int newWater) {}
        Map<String, List<MonthlyInv>> byContract = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, contract_id, billing_month, period_start, period_end,
                       old_electric, new_electric, old_water, new_water
                FROM invoices
                WHERE kind = 'MONTHLY'
                ORDER BY contract_id, period_start, id
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byContract.computeIfAbsent(rs.getString("contract_id"), k -> new ArrayList<>())
                        .add(new MonthlyInv(
                                rs.getString("id"),
                                rs.getString("contract_id"),
                                rs.getString("billing_month"),
                                rs.getDate("period_start").toLocalDate(),
                                rs.getDate("period_end").toLocalDate(),
                                rs.getInt("old_electric"),
                                rs.getInt("new_electric"),
                                rs.getInt("old_water"),
                                rs.getInt("new_water")
                        ));
            }
        }

        try (PreparedStatement updateInv = conn.prepareStatement(
                "UPDATE invoices SET period_index = ?, meter_verification_status = ? WHERE id = ?"
        )) {
            for (Map.Entry<String, List<MonthlyInv>> entry : byContract.entrySet()) {
                String contractId = entry.getKey();
                ContractInfo info = contracts.get(contractId);
                if (info == null) {
                    throw new IllegalStateException("Hóa đơn tham chiếu hợp đồng không tồn tại: " + contractId);
                }
                LeaseRentCalculator.LeaseTermBreakDown breakdown =
                        LeaseRentCalculator.calculateLeaseBreakdown(info.startDate(), info.endDate(), info.rent());

                Map<Integer, MonthlyInv> mapped = new LinkedHashMap<>();
                for (MonthlyInv inv : entry.getValue()) {
                    Integer idx = MigrationPreflightValidator.resolveLegacyPeriodIndex(
                            breakdown, inv.billingMonth(), inv.periodStart(), inv.periodEnd()
                    );
                    if (idx == null) {
                        throw new IllegalStateException(
                                "Không thể ánh xạ duy nhất period_index cho hóa đơn lịch sử " + inv.id()
                                        + " của hợp đồng " + contractId + " (" + inv.billingMonth() + ")."
                        );
                    }
                    if (mapped.containsKey(idx)) {
                        throw new IllegalStateException(
                                "Phát hiện trùng period_index=" + idx + " trên hợp đồng " + contractId
                                        + " giữa hóa đơn " + mapped.get(idx).id() + " và " + inv.id() + "."
                        );
                    }
                    mapped.put(idx, inv);
                }

                List<Integer> periods = new ArrayList<>(mapped.keySet());
                periods.sort(Comparator.naturalOrder());
                MonthlyInv prev = null;
                Integer prevPeriod = null;
                for (Integer periodIdx : periods) {
                    MonthlyInv curr = mapped.get(periodIdx);
                    String meterStatus;
                    if (periodIdx == 1) {
                        if ("VERIFIED".equals(info.handoverMeterStatus())
                                && info.handoverElectric() != null && info.handoverWater() != null) {
                            meterStatus = (curr.oldElectric() == info.handoverElectric()
                                    && curr.oldWater() == info.handoverWater())
                                    ? "VERIFIED"
                                    : "LEGACY_DISCREPANCY";
                        } else {
                            meterStatus = "LEGACY_UNVERIFIED";
                        }
                    } else if (prev != null && prevPeriod != null && periodIdx == prevPeriod + 1) {
                        if (curr.oldElectric() == prev.newElectric() && curr.oldWater() == prev.newWater()) {
                            meterStatus = "LEGACY_UNVERIFIED";
                        } else {
                            meterStatus = "LEGACY_DISCREPANCY";
                            log.warn("[V6 Backfill] Phát hiện sai lệch chỉ số giữa kỳ {} và kỳ {} trên hợp đồng {} (hóa đơn {}).",
                                    prevPeriod, periodIdx, contractId, curr.id());
                        }
                    } else {
                        meterStatus = "LEGACY_UNVERIFIED";
                    }

                    updateInv.setInt(1, periodIdx);
                    updateInv.setString(2, meterStatus);
                    updateInv.setString(3, curr.id());
                    updateInv.executeUpdate();

                    prev = curr;
                    prevPeriod = periodIdx;
                }
            }
        }
    }
}
