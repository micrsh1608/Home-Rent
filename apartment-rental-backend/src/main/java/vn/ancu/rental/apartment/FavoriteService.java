package vn.ancu.rental.apartment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.VietnamTime;

import java.sql.Date;
import java.util.List;

@Service
public class FavoriteService {

    private final JdbcTemplate jdbcTemplate;

    public FavoriteService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record FavoritesResponse(
            Boolean saved,
            List<String> favoriteIds,
            List<ApartmentDto> items
    ) {
    }

    public FavoritesResponse getFavorites(String userId) {
        return buildFavoritesResponse(userId, null);
    }

    @Transactional
    public FavoritesResponse toggleFavorite(String userId, String apartmentId) {
        Integer aptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apartments WHERE id = ?",
                Integer.class,
                apartmentId
        );
        if (aptCount == null || aptCount == 0) {
            throw AppException.notFound("Không tìm thấy căn hộ.");
        }

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saved_apartments WHERE user_id = ? AND apartment_id = ?",
                Integer.class,
                userId, apartmentId
        );
        boolean nowSaved;
        if (existing != null && existing > 0) {
            jdbcTemplate.update(
                    "DELETE FROM saved_apartments WHERE user_id = ? AND apartment_id = ?",
                    userId, apartmentId
            );
            nowSaved = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO saved_apartments (user_id, apartment_id) VALUES (?, ?)",
                    userId, apartmentId
            );
            nowSaved = true;
        }
        return buildFavoritesResponse(userId, nowSaved);
    }

    private FavoritesResponse buildFavoritesResponse(String userId, Boolean savedState) {
        Date today = Date.valueOf(VietnamTime.today());
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT apartment_id FROM saved_apartments WHERE user_id = ? ORDER BY saved_at DESC",
                String.class,
                userId
        );
        List<ApartmentDto> visibleSaved = jdbcTemplate.query(
                OwnerPropertyService.BASE_APARTMENT_SELECT + """
                JOIN saved_apartments sa ON sa.apartment_id = a.id
                WHERE sa.user_id = ?
                  AND a.published = 1
                  AND a.condition_status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM contracts c
                      WHERE c.apartment_id = a.id
                        AND c.status = 'SIGNED'
                        AND c.end_date >= ?
                  )
                ORDER BY sa.saved_at DESC
                """,
                OwnerPropertyService.apartmentRowMapper(),
                today, userId, today
        );
        return new FavoritesResponse(savedState, ids, visibleSaved);
    }
}
