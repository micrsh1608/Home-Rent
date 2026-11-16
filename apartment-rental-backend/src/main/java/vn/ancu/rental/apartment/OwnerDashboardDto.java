package vn.ancu.rental.apartment;

import vn.ancu.rental.contract.ContractDto;

import java.util.List;

public record OwnerDashboardDto(
        int buildingCount,
        int totalUnits,
        int emptyUnits,
        int occupiedUnits,
        int maintenanceUnits,
        int occupancyRatePercent,
        long collectedThisMonth,
        long unpaidTotal,
        int notificationCount,
        List<ContractDto> expiringContracts
) {
}
