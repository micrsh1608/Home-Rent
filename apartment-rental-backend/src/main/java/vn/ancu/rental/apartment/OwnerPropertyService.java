package vn.ancu.rental.apartment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.DatabaseLockService;
import vn.ancu.rental.common.ServerClock;
import vn.ancu.rental.contract.LeaseRentCalculator;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OwnerPropertyService {

    private static final int MAX_IMAGE_BYTES = 300_000;
    private static final long MAX_UTILITY_RATE = 1_000_000L;
    private static final BigDecimal MAX_AREA = new BigDecimal("10000.00");
    private static final int MAX_ROOMS = 50;

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseLockService lockService;
    private final ServerClock serverClock;

    public OwnerPropertyService(
            JdbcTemplate jdbcTemplate,
            DatabaseLockService lockService,
            ServerClock serverClock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.lockService = lockService;
        this.serverClock = serverClock;
    }

    public record BuildingUpsertRequest(
            String name,
            String district,
            String address,
            String location,
            Long electricRate,
            Long waterRate
    ) {
    }

    public record ApartmentUpsertRequest(
            String buildingId,
            String room,
            String title,
            Long price,
            BigDecimal area,
            Integer beds,
            Integer baths,
            String condition,
            String description,
            String image,
            String customImage,
            Boolean published
    ) {
        public ApartmentUpsertRequest(
                String buildingId,
                String room,
                String title,
                Long price,
                BigDecimal area,
                Integer beds,
                Integer baths,
                String condition,
                String description,
                String image
        ) {
            this(buildingId, room, title, price, area, beds, baths, condition, description, image, null, null);
        }
    }

    public record ListingUpdateRequest(
            String title,
            String description,
            String image,
            String customImage,
            Boolean removePhoto
    ) {
    }

    public record PublishToggleRequest(Boolean published) {
    }

    public List<BuildingDto> listOwnerBuildings(String ownerId) {
        return jdbcTemplate.query(
                """
                SELECT id, owner_id, name, district, address, signing_location, electric_rate, water_rate
                FROM buildings
                WHERE owner_id = ?
                ORDER BY created_at ASC, id ASC
                """,
                buildingRowMapper(),
                ownerId
        );
    }

    public BuildingDto getOwnerBuilding(String ownerId, String buildingId) {
        List<BuildingDto> list = jdbcTemplate.query(
                """
                SELECT id, owner_id, name, district, address, signing_location, electric_rate, water_rate
                FROM buildings
                WHERE id = ?
                """,
                buildingRowMapper(),
                buildingId
        );
        if (list.isEmpty()) {
            throw AppException.notFound("Không tìm thấy tòa nhà.");
        }
        BuildingDto building = list.get(0);
        if (!building.ownerId().equals(ownerId)) {
            throw AppException.forbidden("Bạn không có quyền truy cập hoặc chỉnh sửa tòa nhà của chủ nhà khác.");
        }
        return building;
    }

    @Transactional
    public BuildingDto createBuilding(String ownerId, BuildingUpsertRequest req) {
        validateBuildingRequest(req);
        String id = UUID.randomUUID().toString();
        String name = req.name().trim();
        String district = req.district().trim();
        String address = req.address().trim();
        String location = (req.location() != null && !req.location().isBlank())
                ? req.location().trim()
                : "Sảnh lễ tân " + name + ", " + address;
        if (location.length() > 300) {
            throw AppException.badRequest("INVALID_BUILDING", "Địa điểm ký không được vượt quá 300 ký tự.");
        }
        long electricRate = req.electricRate() != null ? req.electricRate() : 3500L;
        long waterRate = req.waterRate() != null ? req.waterRate() : 20000L;

        jdbcTemplate.update(
                """
                INSERT INTO buildings (id, owner_id, name, district, address, signing_location, electric_rate, water_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, ownerId, name, district, address, location, electricRate, waterRate
        );
        return getOwnerBuilding(ownerId, id);
    }

    @Transactional
    public BuildingDto updateBuilding(String ownerId, String buildingId, BuildingUpsertRequest req) {
        getOwnerBuilding(ownerId, buildingId);
        validateBuildingRequest(req);
        String name = req.name().trim();
        String district = req.district().trim();
        String address = req.address().trim();
        String location = (req.location() != null && !req.location().isBlank())
                ? req.location().trim()
                : "Sảnh lễ tân " + name + ", " + address;
        if (location.length() > 300) {
            throw AppException.badRequest("INVALID_BUILDING", "Địa điểm ký không được vượt quá 300 ký tự.");
        }
        long electricRate = req.electricRate() != null ? req.electricRate() : 3500L;
        long waterRate = req.waterRate() != null ? req.waterRate() : 20000L;

        int updated = jdbcTemplate.update(
                """
                UPDATE buildings
                SET name = ?, district = ?, address = ?, signing_location = ?, electric_rate = ?, water_rate = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ?
                """,
                name, district, address, location, electricRate, waterRate, buildingId, ownerId
        );
        if (updated == 0) {
            throw AppException.forbidden("Bạn không có quyền chỉnh sửa tòa nhà này.");
        }
        return getOwnerBuilding(ownerId, buildingId);
    }

    public List<ApartmentDto> listOwnerApartments(String ownerId, String buildingIdFilter) {
        Date today = Date.valueOf(serverClock.today());
        if (buildingIdFilter != null && !buildingIdFilter.isBlank()) {
            getOwnerBuilding(ownerId, buildingIdFilter);
            return jdbcTemplate.query(
                    BASE_APARTMENT_SELECT + " WHERE b.owner_id = ? AND a.building_id = ? ORDER BY a.created_at ASC, a.id ASC",
                    apartmentRowMapper(),
                    today, ownerId, buildingIdFilter
            );
        }
        return jdbcTemplate.query(
                BASE_APARTMENT_SELECT + " WHERE b.owner_id = ? ORDER BY a.created_at ASC, a.id ASC",
                apartmentRowMapper(),
                today, ownerId
        );
    }

    public ApartmentDto getOwnerApartment(String ownerId, String apartmentId) {
        Date today = Date.valueOf(serverClock.today());
        List<ApartmentDto> list = jdbcTemplate.query(
                BASE_APARTMENT_SELECT + " WHERE a.id = ?",
                apartmentRowMapper(),
                today, apartmentId
        );
        if (list.isEmpty()) {
            throw AppException.notFound("Không tìm thấy căn hộ.");
        }
        ApartmentDto unit = list.get(0);
        if (!unit.ownerId().equals(ownerId)) {
            throw AppException.forbidden("Bạn không có quyền truy cập hoặc chỉnh sửa căn hộ của chủ nhà khác.");
        }
        return unit;
    }

    @Transactional
    public ApartmentDto createApartment(String ownerId, ApartmentUpsertRequest req) {
        validateApartmentRequest(req);
        BuildingDto building = getOwnerBuilding(ownerId, req.buildingId());
        String room = req.room().trim();
        ensureUniqueRoomInBuilding(building.id(), room, null);

        String conditionDb = normalizeConditionDb(req.condition());
        String id = UUID.randomUUID().toString();
        String image = (req.image() != null && !req.image().isBlank()) ? req.image().trim() : "greenhouse";
        if (image.length() > 100) {
            throw AppException.badRequest("INVALID_APARTMENT", "Mã ảnh minh họa không hợp lệ.");
        }
        String description = (req.description() != null && !req.description().isBlank())
                ? req.description().trim()
                : "Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.";
        if (description.length() > 2000) {
            throw AppException.badRequest("INVALID_APARTMENT", "Mô tả căn hộ không được vượt quá 2000 ký tự.");
        }
        String customImage = null;
        if (req.customImage() != null && !req.customImage().isBlank()) {
            validateCustomImageDataUrl(req.customImage());
            customImage = req.customImage();
        }
        boolean shouldPublish = "READY".equals(conditionDb) && Boolean.TRUE.equals(req.published());

        jdbcTemplate.update(
                """
                INSERT INTO apartments (
                    id, building_id, room_code, title, monthly_price, area_m2,
                    bedrooms, bathrooms, image_key, custom_image, published, condition_status, description, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                id, building.id(), room, req.title().trim(), req.price(), req.area(),
                req.beds(), req.baths(), image, customImage, shouldPublish ? 1 : 0, conditionDb, description
        );
        return getOwnerApartment(ownerId, id);
    }

    /**
     * P1-10: Khóa hàng căn hộ trong transaction trước khi kiểm tra hợp đồng hiệu lực và chuyển trạng thái bảo trì.
     */
    @Transactional
    public ApartmentDto updateApartment(String ownerId, String apartmentId, ApartmentUpsertRequest req) {
        if (!lockService.lockApartment(apartmentId)) {
            throw AppException.notFound("Không tìm thấy căn hộ.");
        }
        ApartmentDto existing = getOwnerApartment(ownerId, apartmentId);
        validateApartmentRequest(req);
        String targetBuildingId = (req.buildingId() != null && !req.buildingId().isBlank())
                ? req.buildingId()
                : existing.buildingId();
        getOwnerBuilding(ownerId, targetBuildingId);

        String room = req.room().trim();
        ensureUniqueRoomInBuilding(targetBuildingId, room, apartmentId);

        String conditionDb = normalizeConditionDb(req.condition());
        if ("MAINTENANCE".equals(conditionDb) && hasActiveSignedContract(apartmentId)) {
            throw AppException.conflict("ACTIVE_CONTRACT_EXISTS", "Căn hộ có hợp đồng còn hiệu lực, không thể chuyển sang bảo trì.");
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE apartments
                SET building_id = ?, room_code = ?, title = ?, monthly_price = ?, area_m2 = ?,
                    bedrooms = ?, bathrooms = ?, condition_status = ?,
                    published = CASE WHEN ? = 'MAINTENANCE' THEN 0 ELSE published END,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                targetBuildingId, room, req.title().trim(), req.price(), req.area(),
                req.beds(), req.baths(), conditionDb, conditionDb, apartmentId
        );
        if (updated == 0) {
            throw AppException.notFound("Không tìm thấy căn hộ.");
        }
        return getOwnerApartment(ownerId, apartmentId);
    }

    @Transactional
    public ApartmentDto updateListing(String ownerId, String apartmentId, ListingUpdateRequest req) {
        if (!lockService.lockApartment(apartmentId)) {
            throw AppException.notFound("Không tìm thấy căn hộ.");
        }
        ApartmentDto existing = getOwnerApartment(ownerId, apartmentId);
        if (req == null || req.title() == null || req.title().isBlank() || req.description() == null || req.description().isBlank()) {
            throw AppException.badRequest("INVALID_LISTING", "Điền tiêu đề và mô tả căn hộ.");
        }
        if (req.title().trim().length() > 250 || req.description().trim().length() > 2000) {
            throw AppException.badRequest("INVALID_LISTING", "Tiêu đề hoặc mô tả vượt quá độ dài cho phép.");
        }
        String image = (req.image() != null && !req.image().isBlank()) ? req.image().trim() : existing.image();
        if (image.length() > 100) {
            throw AppException.badRequest("INVALID_LISTING", "Mã ảnh minh họa không hợp lệ.");
        }
        String customImage = existing.customImage();
        if (Boolean.TRUE.equals(req.removePhoto())) {
            customImage = null;
        }
        if (req.customImage() != null && !req.customImage().isBlank()) {
            validateCustomImageDataUrl(req.customImage());
            customImage = req.customImage();
        }
        jdbcTemplate.update(
                """
                UPDATE apartments
                SET title = ?, description = ?, image_key = ?, custom_image = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                req.title().trim(), req.description().trim(), image, customImage, apartmentId
        );
        return getOwnerApartment(ownerId, apartmentId);
    }

    /**
     * P1-10: Khóa hàng căn hộ trước khi kiểm tra trạng thái sẵn sàng và bật/tắt tin đăng.
     */
    @Transactional
    public ApartmentDto updatePublishStatus(String ownerId, String apartmentId, PublishToggleRequest req) {
        if (!lockService.lockApartment(apartmentId)) {
            throw AppException.notFound("Không tìm thấy căn hộ.");
        }
        ApartmentDto existing = getOwnerApartment(ownerId, apartmentId);
        boolean targetPublished = (req != null && req.published() != null) ? req.published() : !existing.published();
        if (targetPublished && !"ready".equals(existing.unitStatus())) {
            throw AppException.conflict("UNIT_NOT_READY", "Chỉ có thể mở tin cho thuê khi căn hộ đang sẵn sàng và không có hợp đồng hiệu lực.");
        }
        jdbcTemplate.update(
                "UPDATE apartments SET published = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                targetPublished ? 1 : 0, apartmentId
        );
        return getOwnerApartment(ownerId, apartmentId);
    }

    private void validateBuildingRequest(BuildingUpsertRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()
                || req.district() == null || req.district().isBlank()
                || req.address() == null || req.address().isBlank()) {
            throw AppException.badRequest("INVALID_BUILDING", "Điền tên, khu vực và địa chỉ tòa nhà.");
        }
        if (req.name().trim().length() > 200 || req.district().trim().length() > 100 || req.address().trim().length() > 300) {
            throw AppException.badRequest("INVALID_BUILDING", "Thông tin tòa nhà vượt quá độ dài cho phép.");
        }
        if ((req.electricRate() != null && (req.electricRate() < 0 || req.electricRate() > MAX_UTILITY_RATE))
                || (req.waterRate() != null && (req.waterRate() < 0 || req.waterRate() > MAX_UTILITY_RATE))) {
            throw AppException.badRequest("INVALID_RATE", "Đơn giá điện nước không hợp lệ.");
        }
    }

    private void validateApartmentRequest(ApartmentUpsertRequest req) {
        if (req == null || req.room() == null || req.room().isBlank()
                || req.title() == null || req.title().isBlank()
                || req.buildingId() == null || req.buildingId().isBlank()) {
            throw AppException.badRequest("INVALID_APARTMENT", "Điền mã phòng, tên và tòa nhà.");
        }
        if (req.room().trim().length() > 50 || req.title().trim().length() > 250) {
            throw AppException.badRequest("INVALID_APARTMENT", "Mã phòng hoặc tiêu đề căn hộ vượt quá độ dài cho phép.");
        }
        if (req.price() == null || req.price() <= 0 || req.price() > LeaseRentCalculator.MAX_MONTHLY_RENT
                || req.area() == null || req.area().compareTo(BigDecimal.ZERO) <= 0 || req.area().compareTo(MAX_AREA) > 0) {
            throw AppException.badRequest("INVALID_APARTMENT", "Giá thuê và diện tích phải lớn hơn 0 và nằm trong giới hạn hợp lệ.");
        }
        if (req.beds() == null || req.beds() < 0 || req.beds() > MAX_ROOMS
                || req.baths() == null || req.baths() < 1 || req.baths() > MAX_ROOMS) {
            throw AppException.badRequest("INVALID_APARTMENT", "Kiểm tra lại số phòng ngủ và phòng tắm.");
        }
    }

    private void ensureUniqueRoomInBuilding(String buildingId, String room, String excludeApartmentId) {
        Integer count;
        if (excludeApartmentId == null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM apartments WHERE building_id = ? AND LOWER(room_code) = LOWER(?)",
                    Integer.class, buildingId, room
            );
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM apartments WHERE building_id = ? AND LOWER(room_code) = LOWER(?) AND id <> ?",
                    Integer.class, buildingId, room, excludeApartmentId
            );
        }
        if (count != null && count > 0) {
            throw AppException.conflict("DUPLICATE_ROOM_CODE", "Mã căn hộ đã tồn tại trong tòa nhà.");
        }
    }

    private boolean hasActiveSignedContract(String apartmentId) {
        Date today = Date.valueOf(serverClock.today());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contracts WHERE apartment_id = ? AND status = 'SIGNED' AND end_date >= ?",
                Integer.class, apartmentId, today
        );
        return count != null && count > 0;
    }

    private String normalizeConditionDb(String condition) {
        if (condition == null || condition.isBlank() || "ready".equalsIgnoreCase(condition)) {
            return "READY";
        }
        if ("maintenance".equalsIgnoreCase(condition)) {
            return "MAINTENANCE";
        }
        throw AppException.badRequest("INVALID_CONDITION", "Tình trạng căn hộ không hợp lệ.");
    }

    private void validateCustomImageDataUrl(String dataUrl) {
        if (!dataUrl.startsWith("data:image/jpeg;base64,")
                && !dataUrl.startsWith("data:image/png;base64,")
                && !dataUrl.startsWith("data:image/webp;base64,")) {
            throw AppException.badRequest("INVALID_IMAGE_TYPE", "Chọn ảnh JPG, PNG hoặc WebP có dung lượng tối đa 300 KB.");
        }
        int commaIndex = dataUrl.indexOf(',');
        String base64Part = dataUrl.substring(commaIndex + 1);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Part);
        } catch (IllegalArgumentException ex) {
            throw AppException.badRequest("INVALID_IMAGE_DATA", "Không đọc được ảnh đã chọn.");
        }
        if (decoded.length > MAX_IMAGE_BYTES) {
            throw AppException.badRequest("IMAGE_TOO_LARGE", "Chọn ảnh JPG, PNG hoặc WebP có dung lượng tối đa 300 KB.");
        }
    }

    public static final String BASE_APARTMENT_SELECT = """
            SELECT
                a.id,
                a.building_id,
                b.owner_id,
                u.full_name AS owner_name,
                b.name AS property_name,
                b.district,
                b.address,
                b.signing_location,
                b.electric_rate,
                b.water_rate,
                a.room_code,
                a.title,
                a.monthly_price,
                a.area_m2,
                a.bedrooms,
                a.bathrooms,
                a.image_key,
                a.custom_image,
                a.published,
                a.condition_status,
                a.description,
                CASE
                    WHEN a.condition_status = 'MAINTENANCE' THEN 'maintenance'
                    WHEN EXISTS (
                        SELECT 1 FROM contracts c
                        WHERE c.apartment_id = a.id
                          AND c.status = 'SIGNED'
                          AND c.end_date >= ?
                    ) THEN 'occupied'
                    ELSE 'ready'
                END AS unit_status
            FROM apartments a
            JOIN buildings b ON b.id = a.building_id
            JOIN users u ON u.id = b.owner_id
            """;

    public static RowMapper<BuildingDto> buildingRowMapper() {
        return (rs, rowNum) -> new BuildingDto(
                rs.getString("id"),
                rs.getString("owner_id"),
                rs.getString("name"),
                rs.getString("district"),
                rs.getString("address"),
                rs.getString("signing_location"),
                rs.getLong("electric_rate"),
                rs.getLong("water_rate")
        );
    }

    public static RowMapper<ApartmentDto> apartmentRowMapper() {
        return (rs, rowNum) -> new ApartmentDto(
                rs.getString("id"),
                rs.getString("building_id"),
                rs.getString("owner_id"),
                rs.getString("owner_name"),
                rs.getString("property_name"),
                rs.getString("district"),
                rs.getString("address"),
                rs.getString("signing_location"),
                rs.getLong("electric_rate"),
                rs.getLong("water_rate"),
                rs.getString("room_code"),
                rs.getString("title"),
                rs.getLong("monthly_price"),
                rs.getBigDecimal("area_m2"),
                rs.getInt("bedrooms"),
                rs.getInt("bathrooms"),
                rs.getString("image_key"),
                rs.getString("custom_image"),
                rs.getBoolean("published"),
                rs.getString("condition_status").toLowerCase(Locale.ROOT),
                rs.getString("unit_status"),
                rs.getString("description")
        );
    }
}
