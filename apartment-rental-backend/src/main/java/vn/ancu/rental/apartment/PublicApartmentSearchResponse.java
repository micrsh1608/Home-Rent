package vn.ancu.rental.apartment;

import java.util.List;
import java.util.Map;

public record PublicApartmentSearchResponse(
        List<ApartmentDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<String> districts,
        Map<String, Integer> districtCounts,
        long budgetCeiling
) {
}
