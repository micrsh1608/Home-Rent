package vn.ancu.rental.common;

import vn.ancu.rental.contract.LeaseRentCalculator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kiểm tra an toàn trước khi nâng cấp lược đồ (Preflight Validator):
 * 1. Nhận diện chính xác dấu vết lược đồ (EMPTY / FLYWAY_MANAGED / VERIFIED_V3 / VERIFIED_V4 / UNRECOGNIZED).
 * 2. Không tự baseline một lược đồ không rõ nguồn gốc hoặc thiếu bảng/cột.
 * 3. Kiểm tra toàn bộ dữ liệu hiện có trước khi áp dụng V5-V7:
 *    - Xung đột cấu trúc/nghiệp vụ nghiêm trọng (blockingConflicts) -> DỪNG migration, không xóa/sửa đè bản ghi.
 *    - Dữ liệu lịch sử cần đối soát (legacyAuditWarnings) -> ghi báo cáo minh bạch, đánh dấu LEGACY_UNVERIFIED.
 */
public final class MigrationPreflightValidator {

    private static final Set<String> V1_TO_V3_TABLES = Set.of(
            "users", "buildings", "apartments", "contracts",
            "contract_status_history", "invoices", "invoice_lines", "saved_apartments"
    );

    private static final Set<String> V4_EXTRA_TABLES = Set.of(
            "email_otp_challenges", "email_otp_rate_limits"
    );

    public enum SchemaFingerprint {
        EMPTY,
        FLYWAY_MANAGED,
        LEGACY_V3,
        LEGACY_V4,
        UNRECOGNIZED
    }

    public record PreflightReport(
            SchemaFingerprint fingerprint,
            List<String> blockingConflicts,
            List<String> legacyAuditWarnings
    ) {
        public boolean hasBlockingConflicts() {
            return !blockingConflicts.isEmpty();
        }
    }

    private MigrationPreflightValidator() {
    }

    public static SchemaFingerprint inspectSchemaFingerprint(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            return inspectSchemaFingerprint(conn);
        } catch (SQLException ex) {
            throw new IllegalStateException("Không thể kiểm tra lược đồ cơ sở dữ liệu trước khi migration.", ex);
        }
    }

    public static SchemaFingerprint inspectSchemaFingerprint(Connection conn) throws SQLException {
        String schema = conn.getSchema();
        Set<String> tables = listUserTables(conn, schema);

        if (tables.contains("flyway_schema_history")) {
            return SchemaFingerprint.FLYWAY_MANAGED;
        }
        if (tables.isEmpty()) {
            return SchemaFingerprint.EMPTY;
        }

        boolean hasCoreTables = tables.containsAll(V1_TO_V3_TABLES);
        if (!hasCoreTables) {
            return SchemaFingerprint.UNRECOGNIZED;
        }

        Set<String> userColumns = listTableColumns(conn, schema, "users");
        Set<String> contractColumns = listTableColumns(conn, schema, "contracts");
        Set<String> invoiceColumns = listTableColumns(conn, schema, "invoices");

        if (!contractColumns.contains("move_in_deadline") || !invoiceColumns.contains("billing_month")) {
            return SchemaFingerprint.UNRECOGNIZED;
        }

        boolean hasV4Columns = userColumns.containsAll(Set.of("email_verified", "is_demo", "auth_version", "address", "date_of_birth"));
        boolean hasV4Tables = tables.containsAll(V4_EXTRA_TABLES);

        if (hasV4Columns && hasV4Tables) {
            return SchemaFingerprint.LEGACY_V4;
        }
        if (!hasV4Columns && !hasV4Tables) {
            // Verify V3 (actor_id nullable in contract_status_history)
            if (isColumnNullable(conn, schema, "contract_status_history", "actor_id")) {
                return SchemaFingerprint.LEGACY_V3;
            }
            return SchemaFingerprint.UNRECOGNIZED;
        }
        return SchemaFingerprint.UNRECOGNIZED;
    }

    public static PreflightReport runPreflight(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            SchemaFingerprint fp = inspectSchemaFingerprint(conn);
            if (fp == SchemaFingerprint.EMPTY) {
                return new PreflightReport(fp, List.of(), List.of());
            }
            if (fp == SchemaFingerprint.UNRECOGNIZED) {
                return new PreflightReport(
                        fp,
                        List.of("Lược đồ hiện tại không khớp với phiên bản V3/V4 đã xác minh và không có flyway_schema_history. Không tự động baseline để tránh làm hỏng dữ liệu."),
                        List.of()
                );
            }

            List<String> blocking = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            String schema = conn.getSchema();
            Set<String> contractCols = listTableColumns(conn, schema, "contracts");
            Set<String> invoiceCols = listTableColumns(conn, schema, "invoices");

            // Always validate core data invariants on existing tables before applying V5-V7
            checkContractStatusAndPayment(conn, blocking);
            checkContractForfeit(conn, blocking);
            checkContractSignedMetadata(conn, blocking);
            checkHandoverDates(conn, blocking);
            checkOverlappingSignedContracts(conn, blocking);
            checkDuplicateActiveRenewals(conn, blocking);
            checkInvoicesAndPeriods(conn, blocking, warnings, invoiceCols.contains("period_index"));

            // Check legacy audit items when upgrading from pre-V5
            if (!contractCols.contains("deposit_verification_status")) {
                checkLegacyDepositAudit(conn, warnings);
            }
            if (!contractCols.contains("handover_meter_status")) {
                checkLegacyHandoverMeterAudit(conn, warnings);
            }

            return new PreflightReport(fp, List.copyOf(blocking), List.copyOf(warnings));
        } catch (SQLException ex) {
            throw new IllegalStateException("Lỗi khi chạy kiểm tra preflight dữ liệu trước migration.", ex);
        }
    }

    private static void checkContractStatusAndPayment(Connection conn, List<String> blocking) throws SQLException {
        String sql = """
                SELECT id, status, payment_status FROM contracts
                WHERE NOT (
                    (status = 'AWAITING' AND payment_status = 'UNPAID')
                    OR (status = 'SIGNED' AND payment_status = 'PAID')
                    OR (status = 'CANCELLED' AND payment_status IN ('UNPAID', 'PAID'))
                )
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hợp đồng " + rs.getString("id") + " có cặp trạng thái không hợp lệ: status="
                        + rs.getString("status") + ", payment_status=" + rs.getString("payment_status"));
            }
        }
    }

    private static void checkContractForfeit(Connection conn, List<String> blocking) throws SQLException {
        String sql = """
                SELECT id, status, payment_status, deposit_forfeited FROM contracts
                WHERE deposit_forfeited = 1 AND NOT (status = 'CANCELLED' AND payment_status = 'PAID')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hợp đồng " + rs.getString("id")
                        + " đánh dấu mất cọc (deposit_forfeited=1) nhưng không ở trạng thái CANCELLED + PAID.");
            }
        }
    }

    private static void checkContractSignedMetadata(Connection conn, List<String> blocking) throws SQLException {
        String sql = """
                SELECT id FROM contracts
                WHERE status = 'SIGNED'
                  AND (signed_at IS NULL OR signed_confirmed_by IS NULL
                       OR payment_confirmed_at IS NULL OR payment_confirmed_by IS NULL)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hợp đồng SIGNED " + rs.getString("id")
                        + " thiếu metadata xác nhận ký/thu cọc (signed_at, signed_confirmed_by, payment_confirmed_at hoặc payment_confirmed_by).");
            }
        }
    }

    private static void checkHandoverDates(Connection conn, List<String> blocking) throws SQLException {
        String sql = """
                SELECT id, actual_move_in, actual_move_out FROM contracts
                WHERE actual_move_out IS NOT NULL AND (actual_move_in IS NULL OR actual_move_out < actual_move_in)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hợp đồng " + rs.getString("id") + " có ngày bàn giao không hợp lệ: actual_move_in="
                        + rs.getDate("actual_move_in") + ", actual_move_out=" + rs.getDate("actual_move_out"));
            }
        }
    }

    private static void checkOverlappingSignedContracts(Connection conn, List<String> blocking) throws SQLException {
        record Span(String id, String apartmentId, LocalDate effectiveStart, LocalDate endDate) {}
        Map<String, List<Span>> byApartment = new LinkedHashMap<>();
        String sql = """
                SELECT id, apartment_id, start_date, end_date, actual_move_in
                FROM contracts
                WHERE status = 'SIGNED'
                ORDER BY apartment_id, start_date, id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                LocalDate start = rs.getDate("start_date").toLocalDate();
                LocalDate end = rs.getDate("end_date").toLocalDate();
                Date moveInSql = rs.getDate("actual_move_in");
                LocalDate effectiveStart = (moveInSql != null && moveInSql.toLocalDate().isBefore(start))
                        ? moveInSql.toLocalDate()
                        : start;
                byApartment.computeIfAbsent(rs.getString("apartment_id"), k -> new ArrayList<>())
                        .add(new Span(rs.getString("id"), rs.getString("apartment_id"), effectiveStart, end));
            }
        }
        for (Map.Entry<String, List<Span>> entry : byApartment.entrySet()) {
            List<Span> spans = entry.getValue();
            spans.sort(Comparator.comparing(Span::effectiveStart).thenComparing(Span::id));
            for (int i = 0; i < spans.size(); i++) {
                for (int j = i + 1; j < spans.size(); j++) {
                    Span a = spans.get(i);
                    Span b = spans.get(j);
                    if (!b.effectiveStart().isAfter(a.endDate()) && !a.effectiveStart().isAfter(b.endDate())) {
                        blocking.add("Căn hộ " + entry.getKey() + " có 2 hợp đồng SIGNED trùng lịch: "
                                + a.id() + " (" + a.effectiveStart() + ".." + a.endDate() + ") và "
                                + b.id() + " (" + b.effectiveStart() + ".." + b.endDate() + ").");
                    }
                }
            }
        }
    }

    private static void checkDuplicateActiveRenewals(Connection conn, List<String> blocking) throws SQLException {
        String sql = """
                SELECT renewal_of_id, COUNT(*) AS cnt
                FROM contracts
                WHERE renewal_of_id IS NOT NULL AND status <> 'CANCELLED'
                GROUP BY renewal_of_id
                HAVING COUNT(*) > 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hợp đồng gốc " + rs.getString("renewal_of_id")
                        + " có " + rs.getInt("cnt") + " hợp đồng gia hạn chưa hủy.");
            }
        }
    }

    private static void checkInvoicesAndPeriods(
            Connection conn,
            List<String> blocking,
            List<String> warnings,
            boolean hasPeriodIndexColumn
    ) throws SQLException {
        // 1. PAID metadata & meter nullability checks
        String badPaidSql = """
                SELECT id, status, paid_at, confirmed_by FROM invoices
                WHERE (status = 'PAID' AND (paid_at IS NULL OR confirmed_by IS NULL))
                   OR (status = 'UNPAID' AND (paid_at IS NOT NULL OR confirmed_by IS NOT NULL))
                """;
        try (PreparedStatement ps = conn.prepareStatement(badPaidSql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hóa đơn " + rs.getString("id") + " có metadata thanh toán không nhất quán (status="
                        + rs.getString("status") + ", paid_at=" + rs.getDate("paid_at")
                        + ", confirmed_by=" + rs.getString("confirmed_by") + ").");
            }
        }

        String badMetersSql = """
                SELECT id, kind, old_electric, new_electric, old_water, new_water FROM invoices
                WHERE (kind = 'MONTHLY' AND (old_electric IS NULL OR new_electric IS NULL OR old_electric < 0 OR new_electric < old_electric
                                             OR old_water IS NULL OR new_water IS NULL OR old_water < 0 OR new_water < old_water))
                   OR (kind = 'EARLY_MOVE_IN' AND (old_electric IS NOT NULL OR new_electric IS NOT NULL
                                                   OR old_water IS NOT NULL OR new_water IS NOT NULL))
                """;
        try (PreparedStatement ps = conn.prepareStatement(badMetersSql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hóa đơn " + rs.getString("id") + " (" + rs.getString("kind")
                        + ") có chỉ số điện/nước không hợp lệ.");
            }
        }

        String dupEarlySql = """
                SELECT contract_id, COUNT(*) AS cnt FROM invoices
                WHERE kind = 'EARLY_MOVE_IN'
                GROUP BY contract_id
                HAVING COUNT(*) > 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(dupEarlySql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                blocking.add("Hợp đồng " + rs.getString("contract_id")
                        + " có " + rs.getInt("cnt") + " hóa đơn phạt nhận phòng sớm.");
            }
        }

        // 2. Map MONTHLY invoices to exact period_index via LeaseRentCalculator and detect duplicates or meter gaps
        record ContractDates(LocalDate startDate, LocalDate endDate, long rent) {}
        Map<String, ContractDates> contractMap = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, start_date, end_date, monthly_rent FROM contracts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                contractMap.put(
                        rs.getString("id"),
                        new ContractDates(
                                rs.getDate("start_date").toLocalDate(),
                                rs.getDate("end_date").toLocalDate(),
                                rs.getLong("monthly_rent")
                        )
                );
            }
        }

        record InvRow(String id, String contractId, String billingMonth, LocalDate periodStart, LocalDate periodEnd,
                      Integer existingPeriodIndex, int oldElectric, int newElectric, int oldWater, int newWater) {}
        Map<String, List<InvRow>> monthlyByContract = new LinkedHashMap<>();
        String invSql = hasPeriodIndexColumn
                ? "SELECT id, contract_id, billing_month, period_start, period_end, period_index, old_electric, new_electric, old_water, new_water FROM invoices WHERE kind = 'MONTHLY' ORDER BY contract_id, period_start, id"
                : "SELECT id, contract_id, billing_month, period_start, period_end, NULL AS period_index, old_electric, new_electric, old_water, new_water FROM invoices WHERE kind = 'MONTHLY' ORDER BY contract_id, period_start, id";
        try (PreparedStatement ps = conn.prepareStatement(invSql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getObject("old_electric") == null || rs.getObject("new_electric") == null
                        || rs.getObject("old_water") == null || rs.getObject("new_water") == null) {
                    continue; // Already reported above
                }
                monthlyByContract.computeIfAbsent(rs.getString("contract_id"), k -> new ArrayList<>())
                        .add(new InvRow(
                                rs.getString("id"),
                                rs.getString("contract_id"),
                                rs.getString("billing_month"),
                                rs.getDate("period_start").toLocalDate(),
                                rs.getDate("period_end").toLocalDate(),
                                (Integer) rs.getObject("period_index"),
                                rs.getInt("old_electric"),
                                rs.getInt("new_electric"),
                                rs.getInt("old_water"),
                                rs.getInt("new_water")
                        ));
            }
        }

        for (Map.Entry<String, List<InvRow>> entry : monthlyByContract.entrySet()) {
            String contractId = entry.getKey();
            ContractDates cd = contractMap.get(contractId);
            if (cd == null) {
                continue;
            }
            LeaseRentCalculator.LeaseTermBreakDown breakdown =
                    LeaseRentCalculator.calculateLeaseBreakdown(cd.startDate(), cd.endDate(), cd.rent());

            Map<Integer, InvRow> byPeriod = new LinkedHashMap<>();
            for (InvRow inv : entry.getValue()) {
                Integer resolved = inv.existingPeriodIndex() != null
                        ? inv.existingPeriodIndex()
                        : resolveLegacyPeriodIndex(breakdown, inv.billingMonth(), inv.periodStart(), inv.periodEnd());
                if (resolved == null) {
                    blocking.add("Hóa đơn MONTHLY " + inv.id() + " của hợp đồng " + contractId
                            + " (tháng " + inv.billingMonth() + ", " + inv.periodStart() + ".." + inv.periodEnd()
                            + ") không ánh xạ duy nhất được vào kỳ thuê hợp lệ.");
                } else if (byPeriod.containsKey(resolved)) {
                    blocking.add("Hợp đồng " + contractId + " có hóa đơn MONTHLY trùng kỳ số " + resolved
                            + ": " + byPeriod.get(resolved).id() + " và " + inv.id() + ".");
                } else {
                    byPeriod.put(resolved, inv);
                }
            }

            // Audit sequential meter continuity (non-blocking warning, marked for reconciliation)
            List<Integer> sortedPeriods = new ArrayList<>(byPeriod.keySet());
            sortedPeriods.sort(Integer::compareTo);
            InvRow prev = null;
            Integer prevIdx = null;
            for (Integer idx : sortedPeriods) {
                InvRow curr = byPeriod.get(idx);
                if (prev != null && prevIdx != null && idx == prevIdx + 1) {
                    if (curr.oldElectric() != prev.newElectric() || curr.oldWater() != prev.newWater()) {
                        warnings.add("Cần đối soát chỉ số điện/nước lịch sử của hợp đồng " + contractId
                                + " giữa kỳ " + prevIdx + " (" + prev.id() + ") và kỳ " + idx + " (" + curr.id() + ").");
                    }
                }
                prev = curr;
                prevIdx = idx;
            }
        }
    }

    private static void checkLegacyDepositAudit(Connection conn, List<String> warnings) throws SQLException {
        String sql = "SELECT id FROM contracts WHERE payment_status = 'PAID' ORDER BY id";
        List<String> legacyIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                legacyIds.add(rs.getString("id"));
            }
        }
        if (!legacyIds.isEmpty()) {
            warnings.add("Có " + legacyIds.size() + " hợp đồng PAID lịch sử chưa có số tiền cọc thực nhận (sẽ được đánh dấu LEGACY_UNVERIFIED để đối soát, không bịa số liệu): " + legacyIds);
        }
    }

    private static void checkLegacyHandoverMeterAudit(Connection conn, List<String> warnings) throws SQLException {
        String sql = "SELECT id FROM contracts WHERE actual_move_in IS NOT NULL ORDER BY id";
        List<String> legacyIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                legacyIds.add(rs.getString("id"));
            }
        }
        if (!legacyIds.isEmpty()) {
            warnings.add("Có " + legacyIds.size() + " hợp đồng đã nhận phòng lịch sử chưa có chỉ số điện nước bàn giao ban đầu (sẽ được đánh dấu LEGACY_UNVERIFIED, không mặc định 0): " + legacyIds);
        }
    }

    /**
     * Ánh xạ duy nhất một hóa đơn MONTHLY cũ vào period_index theo LeaseRentCalculator.
     * Ưu tiên: (1) khớp chính xác period_start & period_end, (2) khớp duy nhất billing_month,
     * (3) khớp duy nhất theo số ngày giao nhau lớn nhất (>= 15 ngày). Trả về null nếu mơ hồ.
     */
    public static Integer resolveLegacyPeriodIndex(
            LeaseRentCalculator.LeaseTermBreakDown breakdown,
            String billingMonth,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        for (LeaseRentCalculator.BillingPeriodQuote p : breakdown.periods()) {
            if (p.periodStart().equals(periodStart) && p.periodEnd().equals(periodEnd)) {
                return p.periodIndex();
            }
        }
        if (billingMonth != null && !billingMonth.isBlank()) {
            List<LeaseRentCalculator.BillingPeriodQuote> monthMatches = breakdown.periods().stream()
                    .filter(p -> p.billingMonth().equals(billingMonth.trim()))
                    .toList();
            if (monthMatches.size() == 1) {
                LeaseRentCalculator.BillingPeriodQuote candidate = monthMatches.get(0);
                // Ensure the stored dates actually overlap this period (prevent mis-labeled month)
                if (overlapDays(candidate.periodStart(), candidate.periodEnd(), periodStart, periodEnd) > 0) {
                    return candidate.periodIndex();
                }
            }
        }
        long bestOverlap = 0;
        Integer bestIndex = null;
        boolean tie = false;
        for (LeaseRentCalculator.BillingPeriodQuote p : breakdown.periods()) {
            long ov = overlapDays(p.periodStart(), p.periodEnd(), periodStart, periodEnd);
            if (ov > bestOverlap) {
                bestOverlap = ov;
                bestIndex = p.periodIndex();
                tie = false;
            } else if (ov == bestOverlap && ov > 0) {
                tie = true;
            }
        }
        if (!tie && bestIndex != null && bestOverlap >= 15) {
            return bestIndex;
        }
        return null;
    }

    private static long overlapDays(LocalDate s1, LocalDate e1, LocalDate s2, LocalDate e2) {
        LocalDate start = s1.isAfter(s2) ? s1 : s2;
        LocalDate end = e1.isBefore(e2) ? e1 : e2;
        if (end.isBefore(start)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static Set<String> listUserTables(Connection conn, String schema) throws SQLException {
        Set<String> names = new HashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, schema, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if (tableName == null) {
                    continue;
                }
                if (tableSchema != null && (tableSchema.equalsIgnoreCase("INFORMATION_SCHEMA")
                        || tableSchema.equalsIgnoreCase("sys"))) {
                    continue;
                }
                names.add(tableName.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static Set<String> listTableColumns(Connection conn, String schema, String tableName) throws SQLException {
        Set<String> cols = new HashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, schema, null, null)) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    cols.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return cols;
    }

    private static boolean isColumnNullable(Connection conn, String schema, String tableName, String columnName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, schema, null, null)) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && columnName.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                }
            }
        }
        return false;
    }
}
