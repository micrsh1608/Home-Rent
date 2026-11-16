package vn.ancu.rental.apartment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.auth.SessionAuthHelper;

import java.util.List;

@RestController
@RequestMapping("/api/owner")
public class OwnerPropertyController {

    private final OwnerPropertyService ownerPropertyService;
    private final OwnerDashboardService ownerDashboardService;
    private final SessionAuthHelper sessionAuthHelper;

    public OwnerPropertyController(
            OwnerPropertyService ownerPropertyService,
            OwnerDashboardService ownerDashboardService,
            SessionAuthHelper sessionAuthHelper
    ) {
        this.ownerPropertyService = ownerPropertyService;
        this.ownerDashboardService = ownerDashboardService;
        this.sessionAuthHelper = sessionAuthHelper;
    }

    @GetMapping("/dashboard")
    public OwnerDashboardDto getDashboard(HttpServletRequest request) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerDashboardService.getDashboard(owner, null);
    }

    @GetMapping("/buildings")
    public List<BuildingDto> listBuildings(HttpServletRequest request) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.listOwnerBuildings(owner.id());
    }

    @GetMapping("/buildings/{id}")
    public BuildingDto getBuilding(@PathVariable String id, HttpServletRequest request) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.getOwnerBuilding(owner.id(), id);
    }

    @PostMapping("/buildings")
    @ResponseStatus(HttpStatus.CREATED)
    public BuildingDto createBuilding(
            @RequestBody OwnerPropertyService.BuildingUpsertRequest body,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.createBuilding(owner.id(), body);
    }

    @PutMapping("/buildings/{id}")
    public BuildingDto updateBuilding(
            @PathVariable String id,
            @RequestBody OwnerPropertyService.BuildingUpsertRequest body,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.updateBuilding(owner.id(), id, body);
    }

    @GetMapping("/apartments")
    public List<ApartmentDto> listApartments(
            @RequestParam(required = false) String buildingId,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.listOwnerApartments(owner.id(), buildingId);
    }

    @GetMapping("/apartments/{id}")
    public ApartmentDto getApartment(@PathVariable String id, HttpServletRequest request) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.getOwnerApartment(owner.id(), id);
    }

    @PostMapping("/apartments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApartmentDto createApartment(
            @RequestBody OwnerPropertyService.ApartmentUpsertRequest body,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.createApartment(owner.id(), body);
    }

    @PutMapping("/apartments/{id}")
    public ApartmentDto updateApartment(
            @PathVariable String id,
            @RequestBody OwnerPropertyService.ApartmentUpsertRequest body,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.updateApartment(owner.id(), id, body);
    }

    @PatchMapping("/apartments/{id}/listing")
    public ApartmentDto updateListing(
            @PathVariable String id,
            @RequestBody OwnerPropertyService.ListingUpdateRequest body,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.updateListing(owner.id(), id, body);
    }

    @PatchMapping("/apartments/{id}/publish")
    public ApartmentDto togglePublish(
            @PathVariable String id,
            @RequestBody(required = false) OwnerPropertyService.PublishToggleRequest body,
            HttpServletRequest request
    ) {
        AuthUser owner = sessionAuthHelper.requireRole(request, "OWNER");
        return ownerPropertyService.updatePublishStatus(owner.id(), id, body);
    }
}
