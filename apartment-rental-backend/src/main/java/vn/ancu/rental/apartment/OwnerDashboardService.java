package vn.ancu.rental.apartment;

import org.springframework.stereotype.Service;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.common.ServerClock;
import vn.ancu.rental.contract.ContractDto;
import vn.ancu.rental.contract.ContractLifecycleService;
import vn.ancu.rental.contract.InvoiceDto;

import java.time.LocalDate;
import java.util.List;

@Service
public class OwnerDashboardService {

    private final OwnerPropertyService ownerPropertyService;
    private final ContractLifecycleService contractLifecycleService;
    private final ServerClock serverClock;

    public OwnerDashboardService(
            OwnerPropertyService ownerPropertyService,
            ContractLifecycleService contractLifecycleService,
            ServerClock serverClock
    ) {
        this.ownerPropertyService = ownerPropertyService;
        this.contractLifecycleService = contractLifecycleService;
        this.serverClock = serverClock;
    }

    public OwnerDashboardDto getDashboard(AuthUser owner) {
        LocalDate today = serverClock.today();
        String currentMonth = today.toString().substring(0, 7);

        List<BuildingDto> buildings = ownerPropertyService.listOwnerBuildings(owner.id());
        List<ApartmentDto> units = ownerPropertyService.listOwnerApartments(owner.id(), null);
        List<ContractDto> contracts = contractLifecycleService.listContractsForActor(owner);
        List<InvoiceDto> invoices = contractLifecycleService.listInvoicesForActor(owner);

        int emptyUnits = (int) units.stream().filter(u -> "ready".equals(u.unitStatus())).count();
        int occupiedUnits = (int) units.stream().filter(u -> "occupied".equals(u.unitStatus())).count();
        int maintenanceUnits = (int) units.stream().filter(u -> "maintenance".equals(u.unitStatus())).count();
        int totalUnits = units.size();
        int occupancyRatePercent = totalUnits > 0 ? (int) Math.round(100.0 * occupiedUnits / totalUnits) : 0;

        long collectedThisMonth = invoices.stream()
                .filter(inv -> "paid".equalsIgnoreCase(inv.status())
                        && inv.paidAt() != null
                        && inv.paidAt().toString().startsWith(currentMonth))
                .mapToLong(InvoiceDto::totalAmount)
                .sum();

        long unpaidTotal = invoices.stream()
                .filter(inv -> "unpaid".equalsIgnoreCase(inv.status()))
                .mapToLong(InvoiceDto::totalAmount)
                .sum();

        List<ContractDto> expiringContracts = contracts.stream()
                .filter(c -> "signed".equalsIgnoreCase(c.status()) && c.renewalOpen() && !c.expired())
                .toList();

        int notificationCount = contractLifecycleService.listRenewalNotices(owner).size();

        return new OwnerDashboardDto(
                buildings.size(),
                totalUnits,
                emptyUnits,
                occupiedUnits,
                maintenanceUnits,
                occupancyRatePercent,
                collectedThisMonth,
                unpaidTotal,
                notificationCount,
                expiringContracts
        );
    }
}
