package vn.ancu.rental.apartment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.ServerClock;

import java.math.BigDecimal;
import java.sql.Date;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P2-01: Chuyển thống kê, lọc cấu trúc, sắp xếp và phân trang xuống SQL (tương thích SQL Server & H2)
 * trong khi giữ nguyên chuẩn hóa tìm kiếm tiếng Việt không dấu (đ -> d, bỏ dấu tổ hợp).
 */
@Service
public class PublicApartmentService {

    private static final int MAX_PAGE = 10_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ServerClock serverClock;

    public PublicApartmentService(JdbcTemplate jdbcTemplate, ServerClock serverClock) {
        this.jdbcTemplate = jdbcTemplate;
        this.serverClock = serverClock;
    }

    public PublicApartmentSearchResponse searchPublicApartments(
            List<String> districts,
            Long minPrice,
            Long maxPrice,
            Integer beds,
            BigDecimal minArea,
            String query,
            String sort,
            int page,
            int size
    ) {
        Date today = Date.valueOf(serverClock.today());

        // 1. All districts + aggregate counts + budget ceiling directly in SQL
        List<String> allDistricts = jdbcTemplate.queryForList(
                "SELECT DISTINCT district FROM buildings ORDER BY district ASC",
                String.class
        );
        Map<String, Integer> districtCounts = new LinkedHashMap<>();
        for (String d : allDistricts) {
            districtCounts.put(d, 0);
        }
        jdbcTemplate.query(
                """
                SELECT b.district, COUNT(*) AS cnt
                FROM apartments a
                JOIN buildings b ON b.id = a.building_id
                WHERE a.published = 1
                  AND a.condition_status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM contracts c
                      WHERE c.apartment_id = a.id
                        AND c.status = 'SIGNED'
                        AND c.end_date >= ?
                  )
                GROUP BY b.district
                """,
                rs -> {
                    districtCounts.put(rs.getString("district"), rs.getInt("cnt"));
                },
                today
        );

        Long maxVisiblePrice = jdbcTemplate.queryForObject(
                """
                SELECT MAX(a.monthly_price)
                FROM apartments a
                WHERE a.published = 1
                  AND a.condition_status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM contracts c
                      WHERE c.apartment_id = a.id
                        AND c.status = 'SIGNED'
                        AND c.end_date >= ?
                  )
                """,
                Long.class,
                today
        );
        long budgetCeiling = 24_000_000L;
        if (maxVisiblePrice != null && maxVisiblePrice > 0) {
            long rounded = ((maxVisiblePrice + 999_999L) / 1_000_000L) * 1_000_000L;
            if (rounded > budgetCeiling) {
                budgetCeiling = rounded;
            }
        }

        // 2. Parameterized SQL WHERE clause for structural filters
        StringBuilder whereClause = new StringBuilder("""
                WHERE a.published = 1
                  AND a.condition_status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM contracts c
                      WHERE c.apartment_id = a.id
                        AND c.status = 'SIGNED'
                        AND c.end_date >= ?
                  )
                """);
        List<Object> whereParams = new ArrayList<>();
        whereParams.add(today);

        List<String> selectedDistricts = districts == null
                ? List.of()
                : districts.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!selectedDistricts.isEmpty()) {
            String placeholders = String.join(", ", Collections.nCopies(selectedDistricts.size(), "?"));
            whereClause.append(" AND b.district IN (").append(placeholders).append(")");
            whereParams.addAll(selectedDistricts);
        }
        if (minPrice != null && minPrice > 0) {
            whereClause.append(" AND a.monthly_price >= ?");
            whereParams.add(minPrice);
        }
        if (maxPrice != null && maxPrice >= 0) {
            whereClause.append(" AND a.monthly_price <= ?");
            whereParams.add(maxPrice);
        }
        if (beds != null) {
            whereClause.append(" AND a.bedrooms = ?");
            whereParams.add(beds);
        }
        if (minArea != null && minArea.compareTo(BigDecimal.ZERO) > 0) {
            whereClause.append(" AND a.area_m2 >= ?");
            whereParams.add(minArea);
        }

        // Strict whitelist for ORDER BY (zero SQL injection surface)
        String orderBy = switch (sort == null ? "featured" : sort.trim()) {
            case "ascending" -> " ORDER BY a.monthly_price ASC, a.id ASC ";
            case "descending" -> " ORDER BY a.monthly_price DESC, a.id ASC ";
            case "area" -> " ORDER BY a.area_m2 DESC, a.id ASC ";
            default -> " ORDER BY a.created_at ASC, a.id ASC ";
        };

        int safePage = Math.max(0, Math.min(page, MAX_PAGE));
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        String normalizedQuery = (query != null && !query.isBlank()) ? normalizeVietnamese(query.trim()) : "";

        List<ApartmentDto> pageItems;
        long totalElements;

        if (normalizedQuery.isEmpty()) {
            // Pure SQL COUNT + SQL OFFSET/FETCH pagination (identical in SQL Server 2012+ and H2 MSSQLServer mode)
            String countSql = "SELECT COUNT(*) FROM apartments a JOIN buildings b ON b.id = a.building_id " + whereClause;
            Long countResult = jdbcTemplate.queryForObject(countSql, Long.class, whereParams.toArray());
            totalElements = countResult != null ? countResult : 0L;

            List<Object> pageParams = new ArrayList<>();
            pageParams.add(today); // BASE_APARTMENT_SELECT unit_status CASE param
            pageParams.addAll(whereParams);
            pageParams.add((long) safePage * safeSize);
            pageParams.add(safeSize);

            String pageSql = OwnerPropertyService.BASE_APARTMENT_SELECT
                    + whereClause
                    + orderBy
                    + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            pageItems = jdbcTemplate.query(pageSql, OwnerPropertyService.apartmentRowMapper(), pageParams.toArray());
        } else {
            // Structural filters & sorting executed in SQL; accent-normalized Vietnamese search preserves exact behavior across dialects
            List<Object> queryParams = new ArrayList<>();
            queryParams.add(today);
            queryParams.addAll(whereParams);
            List<ApartmentDto> candidates = jdbcTemplate.query(
                    OwnerPropertyService.BASE_APARTMENT_SELECT + whereClause + orderBy,
                    OwnerPropertyService.apartmentRowMapper(),
                    queryParams.toArray()
            );
            List<ApartmentDto> matched = candidates.stream()
                    .filter(unit -> {
                        String haystack = normalizeVietnamese(
                                unit.title() + " " + unit.propertyName() + " " + unit.address() + " " + unit.district()
                        );
                        return haystack.contains(normalizedQuery);
                    })
                    .toList();
            totalElements = matched.size();
            int fromIndex = (int) Math.min((long) safePage * safeSize, totalElements);
            int toIndex = (int) Math.min((long) fromIndex + safeSize, totalElements);
            pageItems = matched.subList(fromIndex, toIndex);
        }

        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);

        return new PublicApartmentSearchResponse(
                pageItems,
                safePage,
                safeSize,
                totalElements,
                totalPages,
                allDistricts,
                districtCounts,
                budgetCeiling
        );
    }

    public ApartmentDto getPublicApartmentDetail(String id) {
        Date today = Date.valueOf(serverClock.today());
        List<ApartmentDto> list = jdbcTemplate.query(
                OwnerPropertyService.BASE_APARTMENT_SELECT + """
                WHERE a.id = ?
                  AND a.published = 1
                  AND a.condition_status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM contracts c
                      WHERE c.apartment_id = a.id
                        AND c.status = 'SIGNED'
                        AND c.end_date >= ?
                  )
                """,
                OwnerPropertyService.apartmentRowMapper(),
                today, id, today
        );
        if (list.isEmpty()) {
            throw AppException.notFound("Căn hộ này hiện không mở cho thuê.");
        }
        return list.get(0);
    }

    public static String normalizeVietnamese(String text) {
        if (text == null) {
            return "";
        }
        String nfd = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'd');
    }
}
