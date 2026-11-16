package vn.ancu.rental.apartment;

import java.math.BigDecimal;

public record ApartmentDto(
        String id,
        String buildingId,
        String ownerId,
        String ownerName,
        String propertyName,
        String district,
        String address,
        String location,
        long electricRate,
        long waterRate,
        String room,
        String title,
        long price,
        BigDecimal area,
        int beds,
        int baths,
        String image,
        String customImage,
        boolean published,
        String condition,
        String unitStatus,
        String description
) {
}
