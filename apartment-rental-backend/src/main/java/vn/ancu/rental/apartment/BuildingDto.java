package vn.ancu.rental.apartment;

public record BuildingDto(
        String id,
        String ownerId,
        String name,
        String district,
        String address,
        String location,
        long electricRate,
        long waterRate
) {
}
