import { useRef } from 'react'
import { Printer, Download, X, ShieldCheck, CheckCircle, FileText, QrCode } from 'lucide-react'
import { money, shortDate, contractCode } from './demo'

export function LegalContractModal({ contract, building, unit, tenant, owner, breakdown, deadline, close }) {
  const printAreaRef = useRef(null)

  function handlePrint() {
    window.print()
  }

  function downloadDoc() {
    const text = printAreaRef.current?.innerText || ''
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `Hop_dong_thue_nha_${contractCode(contract)}.txt`
    link.click()
    URL.revokeObjectURL(url)
  }

  const isSigned = contract.status === 'signed'
  const contractNum = contractCode(contract)
  const fullAddress = building?.address || contract.location || 'TP. Hồ Chí Minh'

  return (
    <div className="printable-modal-overlay">
      <div className="printable-modal-container">
        <div className="printable-toolbar no-print">
          <div className="toolbar-left">
            <span className="toolbar-title"><FileText size={18} /> Văn bản Hợp đồng Pháp lý (Mẫu chuẩn Bộ Xây Dựng)</span>
            <span className="toolbar-code">{contractNum}</span>
          </div>
          <div className="toolbar-actions">
            <button type="button" className="button primary small" onClick={handlePrint}>
              <Printer size={16} /> In Hợp đồng / Lưu PDF (A4)
            </button>
            <button type="button" className="button outline small" onClick={downloadDoc}>
              <Download size={16} /> Tải văn bản (.txt)
            </button>
            <button type="button" className="icon-btn" onClick={close} title="Đóng">
              <X size={20} />
            </button>
          </div>
        </div>

        <div className="printable-paper-wrap">
          <article className="printable-paper" ref={printAreaRef}>
            {/* Header Quốc hiệu */}
            <header className="legal-header">
              <p className="legal-republic">CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</p>
              <p className="legal-motto">Độc lập – Tự do – Hạnh phúc</p>
              <div className="legal-divider">---o0o---</div>
              <h1 className="legal-title">HỢP ĐỒNG THUÊ CĂN HỘ NHÀ Ở</h1>
              <p className="legal-code">Số: {contractNum}/HĐTN-ANCU</p>
            </header>

            {/* Căn cứ pháp lý */}
            <section className="legal-bases">
              <p>• Căn cứ Bộ luật Dân sự số 91/2015/QH13 ngày 24/11/2015;</p>
              <p>• Căn cứ Luật Nhà ở số 27/2023/QH15 được Quốc hội nước CHXHCN Việt Nam thông qua;</p>
              <p>• Căn cứ vào nhu cầu và sự thỏa thuận tự nguyện, bình đẳng của hai Bên.</p>
            </section>

            <p className="legal-preamble">
              Hôm nay, tại địa điểm giao kết hợp đồng <b>{contract.location}</b>, chúng tôi gồm có:
            </p>

            {/* Thông tin Bên A & Bên B */}
            <div className="legal-parties">
              <div className="party-block">
                <h3>BÊN CHO THUÊ (BÊN A):</h3>
                <dl className="party-details">
                  <div><dt>Họ và tên:</dt><dd><b>{owner.name}</b></dd></div>
                  <div><dt>Số CCCD/Định danh:</dt><dd>{owner.cccd || '079185001234'}</dd></div>
                  <div><dt>Số điện thoại:</dt><dd>{owner.phone || '0988665544'}</dd></div>
                  <div><dt>Email:</dt><dd>{owner.email || 'chu_nha@ancu.vn'}</dd></div>
                  <div><dt>Địa chỉ liên hệ:</dt><dd>{fullAddress}</dd></div>
                </dl>
              </div>

              <div className="party-block">
                <h3>BÊN THUÊ (BÊN B):</h3>
                <dl className="party-details">
                  <div><dt>Họ và tên:</dt><dd><b>{tenant.name}</b></dd></div>
                  <div><dt>Số CCCD/Định danh:</dt><dd>{tenant.cccd || '079198002468'}</dd></div>
                  <div><dt>Số điện thoại:</dt><dd>{tenant.phone || '0903124567'}</dd></div>
                  <div><dt>Email:</dt><dd>{tenant.email || 'khach_thue@ancu.vn'}</dd></div>
                  <div><dt>Nơi thường trú:</dt><dd>{tenant.hometown || 'TP. Hồ Chí Minh'}</dd></div>
                </dl>
              </div>
            </div>

            <p className="legal-intro">
              Hai Bên cùng thống nhất ký kết Hợp đồng thuê căn hộ nhà ở với các điều khoản chi tiết như sau:
            </p>

            {/* Các điều khoản */}
            <div className="legal-articles">
              <article>
                <h4>ĐIỀU 1. ĐỐI TƯỢNG HỢP ĐỒNG VÀ DIỆN TÍCH THUÊ</h4>
                <p>1.1. Bên A đồng ý cho Bên B thuê, và Bên B đồng ý thuê căn hộ số <b>{contract.room}</b> thuộc dự án/tòa nhà <b>{contract.propertyName}</b> tại địa chỉ: <b>{fullAddress}</b>.</p>
                <p>1.2. Diện tích sử dụng căn hộ là <b>{unit?.area || 55} m²</b>, bao gồm phòng khách, phòng ngủ, khu vệ sinh, bếp và các tiện nghi nội thất kèm theo.</p>
                <p>1.3. Mục đích thuê: Căn hộ chỉ được sử dụng cho mục đích để ở sinh hoạt gia đình/cá nhân; không sử dụng vào mục đích kinh doanh hàng hóa cấm, chất gây cháy nổ hoặc các hoạt động trái pháp luật.</p>
              </article>

              <article>
                <h4>ĐIỀU 2. THỜI HẠN THUÊ VÀ BÀN GIAO</h4>
                <p>2.1. Thời hạn thuê là <b>{breakdown.totalPeriods || 6} tháng</b>, bắt đầu từ ngày <b>{shortDate(contract.startDate)}</b> và kết thúc vào ngày <b>{shortDate(contract.endDate)}</b>.</p>
                <p>2.2. <b>Quy tắc nhận phòng (03 ngày):</b> Bên B có trách nhiệm đến nhận phòng thực tế trong vòng 03 ngày kể từ ngày dọn vào trên hợp đồng, chậm nhất là hết ngày <b>{shortDate(deadline)}</b>. Nếu quá thời hạn trên mà Bên B không đến nhận phòng mà không có thông báo bằng văn bản được Bên A chấp thuận, hợp đồng sẽ bị <b>hủy bỏ</b> và Bên B <b>bị mất toàn bộ khoản tiền đặt cọc</b>.</p>
                <p>2.3. Gia hạn hợp đồng: Trước khi hết hạn 30 ngày, Bên B có quyền thông báo nguyện vọng gia hạn thông qua hệ thống để hai bên thỏa thuận lập hợp đồng chu kỳ tiếp theo.</p>
              </article>

              <article>
                <h4>ĐIỀU 3. GIÁ THUÊ, TIỀN ĐẶT CỌC VÀ PHƯƠNG THỨC THANH TOÁN</h4>
                <p>3.1. <b>Giá thuê căn hộ:</b> là <b>{money(contract.rent)}/tháng</b> (Bằng chữ: theo biểu giá thỏa thuận).</p>
                <p>3.2. <b>Chu kỳ thanh toán:</b> Tính bắt đầu từ ngày dọn vào trên hợp đồng. Tháng tính theo số ngày thực tế (30 hoặc 31 ngày, riêng tháng 2 tính tròn 30 ngày). Theo đó:</p>
                <ul>
                  <li>Tiền thuê kỳ đầu tiên: <b>{money(breakdown.firstPeriodRent)}</b>.</li>
                  <li>Tiền thuê kỳ cuối cùng: <b>{money(breakdown.lastPeriodRent)}</b>.</li>
                </ul>
                <p>3.3. <b>Tiền đặt cọc bảo đảm:</b> Bên B đặt cọc cho Bên A số tiền là <b>{money(contract.deposit)}</b> ngay khi ký hợp đồng. Khoản tiền cọc này dùng để đảm bảo thực hiện nghĩa vụ hợp đồng và sẽ được hoàn trả cho Bên B sau khi trừ các chi phí dịch vụ/hư hại (nếu có) khi thanh lý hợp đồng.</p>
                <p>3.4. <b>Chi phí điện nước và tiện ích:</b> Đơn giá điện sinh hoạt: <b>{money(contract.electricRate)}/kWh</b>; Đơn giá nước: <b>{money(contract.waterRate)}/m³</b>. Thanh toán hàng tháng theo số đo chỉ số công tơ thực tế.</p>
              </article>

              <article>
                <h4>ĐIỀU 4. QUYỀN VÀ NGHĨA VỤ CỦA BÊN CHO THUÊ (BÊN A)</h4>
                <p>4.1. Bàn giao căn hộ và trang thiết bị cho Bên B theo đúng thỏa thuận tại Điều 1.</p>
                <p>4.2. Bảo đảm quyền sử dụng căn hộ ổn định, riêng tư và trọn vẹn cho Bên B trong suốt thời hạn hợp đồng.</p>
                <p>4.3. Có trách nhiệm sửa chữa kịp thời các hư hỏng lớn liên quan đến kết cấu căn hộ không do lỗi của Bên B gây ra.</p>
                <p>4.4. Thu tiền thuê và các khoản phí dịch vụ đúng kỳ hạn và xuất phiếu thu/hóa đơn theo quy định.</p>
              </article>

              <article>
                <h4>ĐIỀU 5. QUYỀN VÀ NGHĨA VỤ CỦA BÊN THUÊ (BÊN B)</h4>
                <p>5.1. Sử dụng căn hộ đúng mục đích để ở, chấp hành nghiêm túc quy chế quản lý tòa nhà và quy định về phòng cháy chữa cháy.</p>
                <p>5.2. Cung cấp đầy đủ giấy tờ tùy thân (CCCD) để Bên A làm thủ tục khai báo tạm trú theo quy định của cơ quan Công an.</p>
                <p>5.3. Thanh toán tiền thuê nhà và tiền điện, nước đầy đủ, đúng hạn theo thông báo của Bên A.</p>
                <p>5.4. Không được tự ý sửa chữa, thay đổi kết cấu căn hộ hoặc cho bên thứ ba thuê lại khi chưa có sự đồng ý bằng văn bản của Bên A.</p>
                <p>5.5. Nếu dọn vào trước ngày thỏa thuận, Bên B chịu khoản phạt bằng 01 tháng tiền thuê (<b>{money(contract.rent)}</b>). Nếu trả phòng trước hạn, tiền thuê vẫn tính đủ theo hợp đồng.</p>
              </article>

              <article>
                <h4>ĐIỀU 6. ĐIỀU KHOẢN THI HÀNH & CHỮ KÝ XÁC THỰC SỐ</h4>
                <p>6.1. Hợp đồng này có hiệu lực kể từ ngày Bên A xác nhận Bên B đã ký tại địa điểm hẹn và nhận đủ tiền đặt cọc.</p>
                <p>6.2. Hợp đồng được lưu trữ dạng bản điện tử trên hệ thống quản lý bất động sản An Cư, có giá trị pháp lý ràng buộc quyền và nghĩa vụ của hai bên.</p>
              </article>
            </div>

            {/* Chữ ký hai bên */}
            <div className="legal-signatures">
              <div className="signature-box">
                <p className="sig-role">ĐẠI DIỆN BÊN CHO THUÊ (BÊN A)</p>
                <p className="sig-sub">(Ký, ghi rõ họ tên)</p>
                <div className="sig-space">
                  {isSigned && (
                    <div className="digital-sig-stamp owner-stamp">
                      <ShieldCheck size={28} />
                      <div>
                        <strong>ĐÃ KÝ ĐIỆN TỬ</strong>
                        <span>{owner.name}</span>
                        <small>{shortDate(contract.signedAt || contract.startDate)}</small>
                      </div>
                    </div>
                  )}
                </div>
                <p className="sig-name"><b>{owner.name}</b></p>
              </div>

              <div className="signature-box">
                <p className="sig-role">ĐẠI DIỆN BÊN THUÊ (BÊN B)</p>
                <p className="sig-sub">(Ký, ghi rõ họ tên)</p>
                <div className="sig-space">
                  {isSigned && (
                    <div className="digital-sig-stamp tenant-stamp">
                      <CheckCircle size={28} />
                      <div>
                        <strong>ĐÃ KÝ ĐIỆN TỬ</strong>
                        <span>{tenant.name}</span>
                        <small>{shortDate(contract.signedAt || contract.startDate)}</small>
                      </div>
                    </div>
                  )}
                </div>
                <p className="sig-name"><b>{tenant.name}</b></p>
              </div>
            </div>

            {/* Dấu xác thực số hệ thống An Cư */}
            <footer className="legal-footer-verification">
              <div className="verif-seal">
                <div className="seal-badge">
                  <ShieldCheck size={24} />
                  <div>
                    <strong>CHỨNG THỰC BẢN QUYỀN SỐ AN CƯ</strong>
                    <span>Xác thực bởi: An Cư PropTech System · ISO/IEC 27001 Security Standard</span>
                    <span>Mã tra cứu xác thực: <code>SHA256-{contract.id.slice(0, 16).toUpperCase()}</code></span>
                  </div>
                </div>
              </div>
              <div className="verif-qr">
                <div className="qr-box">
                  <QrCode size={56} />
                  <span>Quét tra cứu</span>
                </div>
              </div>
            </footer>
          </article>
        </div>
      </div>
    </div>
  )
}
