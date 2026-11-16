package vn.ancu.rental.apartment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/public/apartments")
public class PublicApartmentController {

    private final PublicApartmentService publicApartmentService;

    public PublicApartmentController(PublicApartmentService publicApartmentService) {
        this.publicApartmentService = publicApartmentService;
    }

    @GetMapping
    public PublicApartmentSearchResponse listPublicApartments(
            @RequestParam(required = false) List<String> districts,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Integer beds,
            @RequestParam(required = false) BigDecimal minArea,
            @RequestParam(required = false) BigDecimal area,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "featured") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        BigDecimal effectiveMinArea = minArea != null ? minArea : area;
        return publicApartmentService.searchPublicApartments(
                districts, minPrice, maxPrice, beds, effectiveMinArea, query, sort, page, size
        );
    }

    @GetMapping("/{id}")
    public ApartmentDto getPublicApartmentDetail(@PathVariable String id) {
        return publicApartmentService.getPublicApartmentDetail(id);
    }
}
