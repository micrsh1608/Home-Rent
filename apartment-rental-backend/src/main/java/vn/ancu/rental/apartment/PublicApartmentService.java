package vn.ancu.rental.apartment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.VietnamTime;

import java.math.BigDecimal;
import java.sql.Date;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PublicApartmentService {

    private final JdbcTemplate jdbcTemplate;

    public PublicApartmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        Date today = Date.valueOf(VietnamTime.today());
        List<String> allDistricts = jdbcTemplate.queryForList(
                "SELECT DISTINCT district FROM buildings ORDER BY district ASC",
                String.class
        );

        List<ApartmentDto> visible = jdbcTemplate.query(
                OwnerPropertyService.BASE_APARTMENT_SELECT + """
                WHERE a.published = 1
                  AND a.condition_status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM contracts c
                      WHERE c.apartment_id = a.id
                        AND c.status = 'SIGNED'
                        AND c.end_date >= ?
                  )
                ORDER BY a.created_at ASC, a.id ASC
                """,
                OwnerPropertyService.apartmentRowMapper(),
                today, today
        );

        Map<String, Integer> districtCounts = new LinkedHashMap<>();
        for (String d : allDistricts) {
            districtCounts.put(d, 0);
        }
        long budgetCeiling = 24_000_000L;
        for (ApartmentDto unit : visible) {
            districtCounts.put(unit.district(), districtCounts.getOrDefault(unit.district(), 0) + 1);
            long rounded = ((unit.price() + 999_999L) / 1_000_000L) * 1_000_000L;
            if (rounded > budgetCeiling) {
                budgetCeiling = rounded;
            }
        }

        List<String> selectedDistricts = districts == null
                ? List.of()
                : districts.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        long minP = (minPrice != null && minPrice > 0) ? minPrice : 0L;
        String normalizedQuery = (query != null && !query.isBlank()) ? normalizeVietnamese(query.trim()) : "";

        List<ApartmentDto> filtered = visible.stream()
                .filter(unit -> selectedDistricts.isEmpty() || selectedDistricts.contains(unit.district()))
                .filter(unit -> unit.price() >= minP)
                .filter(unit -> maxPrice == null || unit.price() <= maxPrice)
                .filter(unit -> beds == null || unit.beds() == beds)
                .filter(unit -> minArea == null || unit.area().compareTo(minArea) >= 0)
                .filter(unit -> {
                    if (normalizedQuery.isEmpty()) {
                        return true;
                    }
                    String haystack = normalizeVietnamese(
                            unit.title() + " " + unit.propertyName() + " " + unit.address() + " " + unit.district()
                    );
                    return haystack.contains(normalizedQuery);
                })
                .toList();

        Comparator<ApartmentDto> comparator = switch (sort == null ? "featured" : sort) {
            case "ascending" -> Comparator.comparingLong(ApartmentDto::price);
            case "descending" -> Comparator.comparingLong(ApartmentDto::price).reversed();
            case "area" -> Comparator.comparing(ApartmentDto::area).reversed();
            default -> null;
        };

        List<ApartmentDto> sorted = comparator == null
                ? filtered
                : filtered.stream().sorted(comparator).toList();

        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        int totalElements = sorted.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        int fromIndex = Math.min(safePage * safeSize, totalElements);
        int toIndex = Math.min(fromIndex + safeSize, totalElements);
        List<ApartmentDto> pageItems = sorted.subList(fromIndex, toIndex);

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
        Date today = Date.valueOf(VietnamTime.today());
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
