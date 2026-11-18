import { useState, useMemo } from 'react'
import {
  ShieldCheck, FileSpreadsheet, Search, Filter, History, KeyRound,
  CheckCircle2, XCircle, AlertCircle, Info, Download, Eye, Clock, UserCheck, Shield
} from 'lucide-react'
import { shortDate, identity, contractCode, money } from './demo'
import { useDemo, Modal, Status } from './shared'

export function AuditRbac() {
  const { db, actor } = useDemo()
  const [tab, setTab] = useState('audit') // 'audit' | 'rbac'
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState('all')
  const [actionFilter, setActionFilter] = useState('all')
  const [selectedLog, setSelectedLog] = useState(null)

  const logs = db.auditLogs || []

  const filteredLogs = useMemo(() => {
    return logs.filter((log) => {
      const matchSearch = !search ||
        log.actorName?.toLowerCase().includes(search.toLowerCase()) ||
        log.entityId?.toLowerCase().includes(search.toLowerCase()) ||
        log.summary?.toLowerCase().includes(search.toLowerCase()) ||
        log.action?.toLowerCase().includes(search.toLowerCase())

      const matchRole = roleFilter === 'all' || log.role === roleFilter
      const matchAction = actionFilter === 'all' || log.action === actionFilter

      return matchSearch && matchRole && matchAction
    })
  }, [logs, search, roleFilter, actionFilter])

  function exportAuditCsv() {
    const headers = [
      'Mã log',
      'Thời gian',
      'Người thực hiện',
      'Vai trò',
      'Hành động',
      'Đối tượng',
      'Mã đối tượng',
      'Tóm tắt thao tác',
      'Địa chỉ IP',
      'Thiết bị'
    ]

    const rows = filteredLogs.map((log) => [
      log.id,
      log.timestamp,
      `"${log.actorName || ''}"`,
      log.role === 'owner' ? 'Chủ nhà/Môi giới' : log.role === 'tenant' ? 'Khách thuê' : 'Hệ thống',
      log.action,
      log.entityType,
      log.entityId,
      `"${(log.summary || '').replace(/"/g, '""')}"`,
      log.ipAddress || '',
      `"${(log.device || '').replace(/"/g, '""')}"`
    ])

    const csvContent = '\uFEFF' + [headers.join(','), ...rows.map(r => r.join(','))].join('\r\n')
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `Nhat_ky_kiem_toan_AnCu_${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="audit-rbac-page">
      <div className="page-head">
        <div>
          <h1><ShieldCheck size={32} /> Kiểm toán & Ma trận phân quyền (RBAC)</h1>
          <p>Truy vết nhật ký thao tác (Audit Trail) và quản lý phân quyền theo vai trò trong hệ thống.</p>
        </div>
        <div className="actions">
          <button
            type="button"
            className="button outline"
            onClick={exportAuditCsv}
            title="Tải file CSV mở trực tiếp trên Excel (UTF-8 BOM)"
          >
            <Download size={17} /> Xuất nhật ký (Excel/CSV)
          </button>
        </div>
      </div>

      <div className="tabs" style={{ marginBottom: 22 }}>
        <button
          type="button"
          className={tab === 'audit' ? 'current' : ''}
          onClick={() => setTab('audit')}
        >
          <History size={18} /> Nhật ký thao tác ({logs.length})
        </button>
        <button
          type="button"
          className={tab === 'rbac' ? 'current' : ''}
          onClick={() => setTab('rbac')}
        >
          <KeyRound size={18} /> Ma trận phân quyền (RBAC Matrix)
        </button>
      </div>

      {tab === 'audit' && (
        <section className="audit-section stack">
          <div className="panel audit-filters">
            <div className="audit-filter-row">
              <div className="search-field" style={{ flex: 2, minWidth: 260 }}>
                <Search size={18} />
                <input
                  type="text"
                  placeholder="Tìm theo người thực hiện, mã HĐ, tóm tắt..."
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>

              <div className="field" style={{ flex: 1, minWidth: 160 }}>
                <select value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)}>
                  <option value="all">Tất cả vai trò</option>
                  <option value="owner">Chủ nhà / Môi giới</option>
                  <option value="tenant">Khách thuê</option>
                  <option value="system">Hệ thống tự động</option>
                </select>
              </div>

              <div className="field" style={{ flex: 1, minWidth: 180 }}>
                <select value={actionFilter} onChange={(e) => setActionFilter(e.target.value)}>
                  <option value="all">Tất cả hành động</option>
                  <option value="SIGN_CONTRACT">Xác nhận ký hợp đồng</option>
                  <option value="CREATE_CONTRACT">Tạo hợp đồng mới</option>
                  <option value="SCHEDULE_APPOINTMENT">Đặt/Đổi lịch hẹn</option>
                  <option value="CREATE_INVOICE">Lập hóa đơn điện nước</option>
                  <option value="RECORD_HANDOVER">Ghi nhận bàn giao</option>
                  <option value="CANCEL_CONTRACT">Hủy hợp đồng & giữ cọc</option>
                  <option value="RENEWAL_RESPONSE">Phản hồi gia hạn</option>
                </select>
              </div>

              {(search || roleFilter !== 'all' || actionFilter !== 'all') && (
                <button
                  type="button"
                  className="button ghost small"
                  onClick={() => { setSearch(''); setRoleFilter('all'); setActionFilter('all') }}
                >
                  Xóa lọc
                </button>
              )}
            </div>

            <div className="audit-stats-strip">
              <span>Đang hiển thị <b>{filteredLogs.length}</b> / {logs.length} bản ghi</span>
              <span className="audit-badge info"><Clock size={14} /> Cơ chế bất biến: Append-only</span>
              <span className="audit-badge security"><Shield size={14} /> Chống chối bỏ (Non-repudiation)</span>
            </div>
          </div>

          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Thời gian</th>
                  <th>Người thực hiện</th>
                  <th>Hành động</th>
                  <th>Đối tượng</th>
                  <th>Tóm tắt thao tác</th>
                  <th>Thiết bị & IP</th>
                  <th>Chi tiết</th>
                </tr>
              </thead>
              <tbody>
                {filteredLogs.map((log) => (
                  <tr key={log.id}>
                    <td>
                      <span className="timestamp">{log.timestamp}</span>
                    </td>
                    <td>
                      <div className="actor-cell">
                        <b>{log.actorName}</b>
                        <span className={`role-pill ${log.role}`}>
                          {log.role === 'owner' ? 'Chủ nhà' : log.role === 'tenant' ? 'Khách thuê' : 'Hệ thống'}
                        </span>
                      </div>
                    </td>
                    <td>
                      <ActionBadge action={log.action} />
                    </td>
                    <td>
                      <span className="entity-tag">{log.entityId}</span>
                    </td>
                    <td>
                      <div className="summary-cell" title={log.summary}>{log.summary}</div>
                    </td>
                    <td>
                      <small>{log.ipAddress}</small>
                      <small className="device-subtle">{log.device}</small>
                    </td>
                    <td>
                      <button
                        type="button"
                        className="button small outline"
                        onClick={() => setSelectedLog(log)}
                        title="Xem chi tiết kỹ thuật"
                      >
                        <Eye size={14} /> Chi tiết
                      </button>
                    </td>
                  </tr>
                ))}
                {filteredLogs.length === 0 && (
                  <tr>
                    <td colSpan="7" style={{ textAlign: 'center', padding: '30px' }}>
                      Không tìm thấy nhật ký kiểm toán phù hợp.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {selectedLog && (
            <Modal title="Chi tiết bản ghi kiểm toán" close={() => setSelectedLog(null)}>
              <div className="stack">
                <dl className="facts">
                  <div><dt>Mã Log (Trace ID)</dt><dd><code>{selectedLog.id}</code></dd></div>
                  <div><dt>Thời gian thực hiện</dt><dd><b>{selectedLog.timestamp}</b></dd></div>
                  <div><dt>Người thực hiện</dt><dd>{selectedLog.actorName} ({selectedLog.actorId})</dd></div>
                  <div><dt>Vai trò phân quyền</dt><dd><span className={`role-pill ${selectedLog.role}`}>{selectedLog.role}</span></dd></div>
                  <div><dt>Mã hành động</dt><dd><code>{selectedLog.action}</code></dd></div>
                  <div><dt>Phân hệ đối tượng</dt><dd>{selectedLog.entityType} · {selectedLog.entityId}</dd></div>
                  <div><dt>Địa chỉ IP mạng</dt><dd>{selectedLog.ipAddress}</dd></div>
                  <div><dt>Thiết bị / Ứng dụng</dt><dd>{selectedLog.device}</dd></div>
                  <div><dt>Mô tả chi tiết</dt><dd>{selectedLog.summary}</dd></div>
                </dl>
                <div className="notice">
                  <ShieldCheck size={18} /> Bản ghi được lưu trữ trên hệ thống nhật ký kiểm toán tự động, đảm bảo tính vẹn toàn phục vụ công tác thanh tra & hậu kiểm theo mô hình PTTK.
                </div>
              </div>
            </Modal>
          )}
        </section>
      )}

      {tab === 'rbac' && (
        <section className="rbac-section stack">
          <div className="panel rbac-header-card">
            <h2>Mô hình Kiểm soát Truy cập Dựa trên Vai trò (RBAC Model)</h2>
            <p>
              Hệ thống áp dụng chuẩn phân quyền phân cấp <b>RBAC (Role-Based Access Control)</b> kết hợp nguyên tắc 
              <b> Đặc quyền tối thiểu (Principle of Least Privilege)</b> nhằm đảm bảo an toàn dữ liệu và phân định rõ trách nhiệm giữa Bên Thuê và Bên Cho Thuê.
            </p>
          </div>

          <div className="table-wrap">
            <table className="rbac-table">
              <thead>
                <tr>
                  <th style={{ width: '28%' }}>Chức năng / Nghiệp vụ hệ thống</th>
                  <th style={{ width: '18%' }}>Khách vãng lai (Guest)</th>
                  <th style={{ width: '18%' }}>Khách thuê (Tenant)</th>
                  <th style={{ width: '18%' }}>Chủ nhà / Môi giới (Owner)</th>
                  <th style={{ width: '18%' }}>Quản trị viên (Admin)</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td><b>1. Khám phá & Lọc căn hộ</b><small>Xem danh sách, bản đồ, lọc ngân sách, tiện ích</small></td>
                  <td><PermAllowed label="Toàn quyền xem" /></td>
                  <td><PermAllowed label="Toàn quyền xem" /></td>
                  <td><PermAllowed label="Toàn quyền xem" /></td>
                  <td><PermAllowed label="Toàn quyền xem" /></td>
                </tr>
                <tr>
                  <td><b>2. Lưu căn hộ yêu thích</b><small>Ghi nhớ căn hộ quan tâm vào danh mục</small></td>
                  <td><PermScope label="Tạm thời (Session)" /></td>
                  <td><PermAllowed label="Đồng bộ tài khoản" /></td>
                  <td><PermAllowed label="Đồng bộ tài khoản" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>3. Đặt & Đổi lịch hẹn ký</b><small>Lựa chọn khung giờ hẹn ký trực tiếp</small></td>
                  <td><PermDenied label="Cần đăng nhập" /></td>
                  <td><PermAllowed label="Chính chủ hợp đồng" /></td>
                  <td><PermScope label="Chỉ xem lịch" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>4. Xác nhận ký & Thu tiền cọc</b><small>Kiểm tra khách ký tại điểm hẹn và thu cọc</small></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermDenied label="Chỉ bên cho thuê" /></td>
                  <td><PermAllowed label="Xác nhận phòng mình sở hữu" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>5. Ghi nhận bàn giao nhận/trả phòng</b><small>Ghi nhận ngày thực tế vào/ra theo quy tắc 3 ngày</small></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermScope label="Chỉ xem trạng thái" /></td>
                  <td><PermAllowed label="Ghi nhận & xác nhận" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>6. Hủy hợp đồng quá hạn dọn vào</b><small>Hủy và giữ cọc nếu khách không đến sau 3 ngày</small></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermDenied label="Không được hủy đơn phương" /></td>
                  <td><PermAllowed label="Được hủy khi quá 3 ngày" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>7. Lập hóa đơn & Chốt điện nước</b><small>Nhập chỉ số công tơ, tính tiền lũy tiến/chu kỳ</small></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermDenied label="Không có quyền lập" /></td>
                  <td><PermAllowed label="Lập hóa đơn phòng mình" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>8. Xác nhận thanh toán hóa đơn</b><small>Ghi nhận khách đã chuyển khoản/tiền mặt</small></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermDenied label="Chỉ xem hóa đơn" /></td>
                  <td><PermAllowed label="Xác nhận thu tiền" /></td>
                  <td><PermAllowed label="Toàn quyền" /></td>
                </tr>
                <tr>
                  <td><b>9. Xuất danh sách khai báo tạm trú</b><small>Xuất file Excel CCCD/Thông tin cư dân gửi Công an</small></td>
                  <td><PermDenied label="Bảo mật thông tin" /></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermAllowed label="Xuất cư dân tòa nhà mình" /></td>
                  <td><PermAllowed label="Toàn hệ thống" /></td>
                </tr>
                <tr>
                  <td><b>10. Truy vết nhật ký kiểm toán (Audit Log)</b><small>Xem lịch sử thao tác, IP, thời gian, chống chối bỏ</small></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermDenied label="Không có quyền" /></td>
                  <td><PermAllowed label="Xem & Xuất báo cáo" /></td>
                  <td><PermAllowed label="Quản trị tối cao" /></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div className="rbac-pillars-grid">
            <article className="panel">
              <div className="step-icon"><Shield size={20} /></div>
              <h3>1. Đặc quyền tối thiểu (Least Privilege)</h3>
              <p>Mỗi người dùng chỉ được cấp đúng và đủ quyền để thực hiện nghĩa vụ của mình, ngăn chặn hành vi vượt quyền (Privilege Escalation).</p>
            </article>

            <article className="panel">
              <div className="step-icon"><UserCheck size={20} /></div>
              <h3>2. Tách biệt trách nhiệm (Separation of Duties)</h3>
              <p>Khách thuê chỉ chọn lịch hẹn; Chủ nhà/Môi giới mới có thẩm quyền xác nhận thu tiền và ký kết để tránh xung đột lợi ích.</p>
            </article>

            <article className="panel">
              <div className="step-icon"><History size={20} /></div>
              <h3>3. Tính không thể chối bỏ (Non-Repudiation)</h3>
              <p>Mọi hành động từ đổi lịch hẹn, xác nhận ký đến chốt chỉ số điện nước đều lưu vết danh tính, mốc thời gian và địa chỉ IP cụ thể.</p>
            </article>
          </div>
        </section>
      )}
    </div>
  )
}

function ActionBadge({ action }) {
  const map = {
    SIGN_CONTRACT: ['Xác nhận ký HĐ', 'green'],
    CREATE_CONTRACT: ['Tạo HĐ mới', 'blue'],
    SCHEDULE_APPOINTMENT: ['Đổi lịch hẹn', 'amber'],
    CREATE_INVOICE: ['Lập hóa đơn', 'blue'],
    RECORD_HANDOVER: ['Ghi bàn giao', 'green'],
    CANCEL_CONTRACT: ['Hủy hợp đồng', 'amber'],
    RENEWAL_RESPONSE: ['Phản hồi gia hạn', 'blue'],
  }
  const [label, tone] = map[action] || [action, 'neutral']
  return <Status tone={tone}>{label}</Status>
}

function PermAllowed({ label }) {
  return <span className="perm-tag allowed"><CheckCircle2 size={14} /> {label}</span>
}

function PermDenied({ label }) {
  return <span className="perm-tag denied"><XCircle size={14} /> {label}</span>
}

function PermScope({ label }) {
  return <span className="perm-tag scope"><AlertCircle size={14} /> {label}</span>
}
