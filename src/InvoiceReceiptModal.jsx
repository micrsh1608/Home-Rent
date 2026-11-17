import { useRef } from 'react'
import { Printer, X, Receipt, CheckCircle, ShieldCheck } from 'lucide-react'
import { money, shortDate, contractCode, invoiceTotal } from './demo'

export function InvoiceReceiptModal({ invoice, contract, owner, tenant, close }) {
  const isPaid = invoice.status === 'paid'
  const total = invoiceTotal(invoice)

  function handlePrint() {
    window.print()
  }

  return (
    <div className="printable-modal-overlay">
      <div className="printable-modal-container invoice-receipt-container">
        <div className="printable-toolbar no-print">
          <div className="toolbar-left">
            <span className="toolbar-title"><Receipt size={18} /> Phiếu thu tiền nhà & Dịch vụ (Mẫu kế toán)</span>
            <span className="toolbar-code">HĐ-{invoice.month}</span>
          </div>
          <div className="toolbar-actions">
            <button type="button" className="button primary small" onClick={handlePrint}>
              <Printer size={16} /> In Phiếu thu / Lưu PDF
            </button>
            <button type="button" className="icon-btn" onClick={close} title="Đóng">
              <X size={20} />
            </button>
          </div>
        </div>

        <div className="printable-paper-wrap">
          <article className="printable-paper receipt-paper">
            <header className="receipt-header">
              <div className="receipt-unit-info">
                <h3>{contract.propertyName}</h3>
                <p>Địa chỉ: {contract.location}</p>
                <p>Hotline/Zalo hỗ trợ: {owner?.phone || '0988665544'}</p>
              </div>
              <div className="receipt-meta">
                <h2>PHIẾU THU TIỀN</h2>
                <p className="receipt-number">Số phiếu: <b>PT-{invoice.id.slice(0, 8).toUpperCase()}</b></p>
                <p>Kỳ hóa đơn: <b>{invoice.month.split('-').reverse().join('/')}</b></p>
              </div>
            </header>

            <div className="receipt-client-info">
              <div><span>Khách thuê:</span> <b>{tenant?.name || 'Khách thuê'}</b></div>
              <div><span>Căn hộ:</span> <b>{contract.room}</b> ({contract.apartmentTitle})</div>
              <div><span>Số điện thoại:</span> {tenant?.phone || '0903124567'}</div>
              <div><span>Hợp đồng số:</span> {contractCode(contract)}</div>
              {invoice.periodStart && invoice.periodEnd && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <span>Chu kỳ tính tiền:</span> <b>{shortDate(invoice.periodStart)} – {shortDate(invoice.periodEnd)}</b> ({invoice.daysInPeriod}/{invoice.monthBasisDays} ngày)
                </div>
              )}
            </div>

            <table className="receipt-table">
              <thead>
                <tr>
                  <th style={{ width: '8%' }}>STT</th>
                  <th>Khoản mục thanh toán</th>
                  <th style={{ width: '22%' }}>Chỉ số / Chi tiết</th>
                  <th style={{ width: '18%' }}>Đơn giá</th>
                  <th style={{ width: '22%', textAlign: 'right' }}>Thành tiền</th>
                </tr>
              </thead>
              <tbody>
                {invoice.lines.map((line, idx) => {
                  const isElectric = line.title.includes('điện')
                  const isWater = line.title.includes('nước')
                  const detail = isElectric
                    ? `${invoice.oldElectric} → ${invoice.newElectric} (${invoice.newElectric - invoice.oldElectric} kWh)`
                    : isWater
                      ? `${invoice.oldWater} → ${invoice.newWater} (${invoice.newWater - invoice.oldWater} m³)`
                      : 'Theo chu kỳ hợp đồng'
                  const rate = isElectric ? money(contract.electricRate) : isWater ? money(contract.waterRate) : '—'

                  return (
                    <tr key={line.title}>
                      <td style={{ textAlign: 'center' }}>{idx + 1}</td>
                      <td><b>{line.title}</b></td>
                      <td>{detail}</td>
                      <td>{rate}</td>
                      <td style={{ textAlign: 'right' }}><b>{money(line.amount)}</b></td>
                    </tr>
                  )
                })}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan="4" style={{ textAlign: 'right', fontWeight: 'bold' }}>TỔNG CỘNG THANH TOÁN:</td>
                  <td style={{ textAlign: 'right', fontWeight: 'bold', fontSize: '16px', color: 'var(--green)' }}>{money(total)}</td>
                </tr>
              </tfoot>
            </table>

            <div className="receipt-status-callout">
              <div className="status-indicator">
                {isPaid ? (
                  <div className="paid-seal">
                    <CheckCircle size={22} />
                    <span>ĐÃ THANH TOÁN ĐẦY ĐỦ</span>
                    <small>Ghi nhận qua hệ thống An Cư</small>
                  </div>
                ) : (
                  <div className="unpaid-badge">
                    <span>TRẠNG THÁI: CHƯA THANH TOÁN</span>
                    <small>Vui lòng chuyển khoản hoặc thanh toán trước ngày 05 hàng tháng</small>
                  </div>
                )}
              </div>
            </div>

            <div className="receipt-signatures">
              <div className="sig-column">
                <p className="sig-role">NGƯỜI NỘP TIỀN</p>
                <p className="sig-sub">(Ký, họ tên)</p>
                <div className="sig-placeholder"></div>
                <p><b>{tenant?.name}</b></p>
              </div>

              <div className="sig-column">
                <p className="sig-role">NGƯỜI THU TIỀN / QUẢN LÝ</p>
                <p className="sig-sub">(Ký, đóng dấu)</p>
                <div className="sig-placeholder">
                  {isPaid && (
                    <div className="cashier-stamp">
                      <ShieldCheck size={20} />
                      <b>XÁC NHẬN ĐÃ THU</b>
                      <span>{owner?.name}</span>
                    </div>
                  )}
                </div>
                <p><b>{owner?.name}</b></p>
              </div>
            </div>

            <div className="receipt-footer-notes">
              <p>• Phiếu thu điện tử có giá trị xác thực nghĩa vụ tài chính giữa hai bên theo hợp đồng thuê nhà.</p>
              <p>• Mọi thắc mắc về chỉ số điện nước xin vui lòng liên hệ ban quản lý trong vòng 48h kể từ khi nhận phiếu.</p>
            </div>
          </article>
        </div>
      </div>
    </div>
  )
}
