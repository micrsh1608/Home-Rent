package vn.ancu.rental.apartment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.auth.SessionAuthHelper;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final SessionAuthHelper sessionAuthHelper;

    public FavoriteController(FavoriteService favoriteService, SessionAuthHelper sessionAuthHelper) {
        this.favoriteService = favoriteService;
        this.sessionAuthHelper = sessionAuthHelper;
    }

    @GetMapping
    public FavoriteService.FavoritesResponse listFavorites(HttpServletRequest request) {
        AuthUser user = sessionAuthHelper.requireAuthenticated(request);
        return favoriteService.getFavorites(user.id());
    }

    @PostMapping("/{apartmentId}/toggle")
    public FavoriteService.FavoritesResponse toggleFavorite(
            @PathVariable String apartmentId,
            HttpServletRequest request
    ) {
        AuthUser user = sessionAuthHelper.requireAuthenticated(request);
        return favoriteService.toggleFavorite(user.id(), apartmentId);
    }
}
