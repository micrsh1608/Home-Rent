package vn.ancu.rental.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Cơ chế khóa bản ghi ở tầng cơ sở dữ liệu, hoạt động nhất quán trên cả đa tiến trình / nhiều instance.
 *
 * Quy ước thứ tự khóa bắt buộc để tránh deadlock:
 * 1. Khóa căn hộ trước (lockApartment) khi thao tác ảnh hưởng đến trạng thái chiếm dụng/trùng lịch/bảo trì.
 * 2. Khóa hợp đồng sau (lockContract). Nếu cần khóa nhiều hợp đồng, luôn khóa theo id tăng dần.
 *
 * Lưu ý: Luôn gọi trong phạm vi @Transactional để giữ khóa cho tới khi commit hoặc rollback.
 */
@Component
public class DatabaseLockService {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;

    public DatabaseLockService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = DatabaseDialect.detect(dataSource);
    }

    public DatabaseDialect dialect() {
        return dialect;
    }

    /**
     * Khóa độc quyền mức ghi trên hàng căn hộ cho đến hết transaction hiện tại.
     * SQL Server: WITH (UPDLOCK, HOLDLOCK) giữ khóa cập nhật + khóa phạm vi cho tới commit/rollback.
     * H2: SELECT ... FOR UPDATE (chỉ dùng cho profile local/test, không thay thế chứng cứ khóa SQL Server).
     */
    public boolean lockApartment(String apartmentId) {
        String sql = dialect == DatabaseDialect.SQL_SERVER
                ? "SELECT id FROM apartments WITH (UPDLOCK, HOLDLOCK) WHERE id = ?"
                : "SELECT id FROM apartments WHERE id = ? FOR UPDATE";
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, apartmentId);
        return !rows.isEmpty();
    }

    /**
     * Khóa độc quyền mức ghi trên hàng hợp đồng cho đến hết transaction hiện tại.
     */
    public boolean lockContract(String contractId) {
        String sql = dialect == DatabaseDialect.SQL_SERVER
                ? "SELECT id FROM contracts WITH (UPDLOCK, HOLDLOCK) WHERE id = ?"
                : "SELECT id FROM contracts WHERE id = ? FOR UPDATE";
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, contractId);
        return !rows.isEmpty();
    }

    /**
     * Khóa căn hộ theo contractId (Level 1), rồi khóa hợp đồng (Level 2) theo đúng thứ tự chuẩn.
     * Trả về apartmentId nếu hợp đồng tồn tại, hoặc null nếu không tìm thấy.
     */
    public String lockApartmentAndContract(String contractId) {
        List<String> aptIds = jdbcTemplate.queryForList(
                "SELECT apartment_id FROM contracts WHERE id = ?",
                String.class,
                contractId
        );
        if (aptIds.isEmpty()) {
            return null;
        }
        String apartmentId = aptIds.get(0);
        lockApartment(apartmentId);
        boolean contractStillExists = lockContract(contractId);
        return contractStillExists ? apartmentId : null;
    }
}
