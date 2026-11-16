import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, ArrowRight, Bell, CalendarDays, FileText, MapPin, Receipt, ShieldCheck } from 'lucide-react'
import {
  appointmentText, backendApi, calculateLeaseBreakdown, canCancelNoShow,
  contractCode, dateAt, expired, identity, invoiceTotal, money,
  moveInDeadline, nowInVietnam, plusDays, renewalOpen, shortDate, updateContract,
} from './demo'
import { Empty, Field, Modal, Notice, Status, useDemo } from './shared'
import { AppointmentMiniMap } from './MapView'

export function ContractStatus({ contract }) {
  if (contract.status === 'cancelled') return <Status tone="amber">Đã hủy (Mất cọc)</Status>
  if (expired(contract)) return <Status>Đã hết hạn</Status>
  if (contract.status === 'signed') return <Status tone="green">Đã ký</Status>
  return <Status tone="amber">Chờ ký</Status>
}

export function PaymentStatus({ paid, forfeited = false }) {
  if (forfeited) return <Status tone="amber">Đã thu cọc (Mất cọc)</Status>
  return <Status tone={paid ? 'green' : 'amber'}>{paid ? 'Đã thanh toán' : 'Chưa thanh toán'}</Status>
}

export function RenewalStatus({ contract, db }) {
  if (contract.status === 'cancelled') return <span>Đã hủy</span>
  const child = db.contracts.find((item) => item.renewalOf === contract.id && item.status !== 'cancelled')
  if (child) return <Status tone={child.status === 'signed' ? 'green' : 'blue'}>{child.status === 'signed' ? 'Đã ký hợp đồng mới' : 'Hợp đồng mới chờ ký'}</Status>
  if (contract.renewal === 'requested') return <Status tone="blue">Muốn gia hạn</Status>
  if (contract.renewal === 'declined') return <Status>Không gia hạn</Status>
  if (renewalOpen(contract)) return <Status tone="amber">Chờ phản hồi</Status>
  return <span>—</span>
}

export function TenantContracts() {
  const { db, actor } = useDemo()
  const contracts = db.contracts.filter((item) => item.tenantId === actor.id).toReversed()
  const notice = contracts.filter((item) => renewalOpen(item) && !item.renewal)
  return <>
    <div className="page-head"><div><h1>Hợp đồng của tôi</h1><p>Xem từng hợp đồng, lịch ký, chu kỳ tiền thuê và thông tin gia hạn.</p></div><Link className="button outline" to="/">Tìm căn hộ <ArrowRight size={17} /></Link></div>
    {notice.map((contract) => <div className="renewal-banner" key={contract.id}><Bell size={22} /><div><b>{contract.apartmentTitle} {expired(contract) ? 'đã hết hạn' : 'sắp hết hạn'}</b><p>Ngày hết hạn {shortDate(contract.endDate)}. Vui lòng chọn có gia hạn hay không.</p></div><Link className="button primary" to={`/tenant/hop-dong/${contract.id}`}>Phản hồi</Link></div>)}
    {contracts.length ? <div className="contract-list">{contracts.map((contract) =>
      <article className="panel contract-card" key={contract.id}>
        <div className="contract-card-top"><div className="step-icon"><FileText size={22} /></div><ContractStatus contract={contract} /></div>
        <p className="subtle">{contractCode(contract)} · {contract.room}</p><h2>{contract.apartmentTitle}</h2>
        <p>{shortDate(contract.startDate)} – {shortDate(contract.endDate)}</p>
        <div className="contract-card-bottom"><strong>{money(contract.rent)}/tháng</strong><Link className="text-link" to={`/tenant/hop-dong/${contract.id}`}>Xem hợp đồng <ArrowRight size={17} /></Link></div>
      </article>)}</div> : <Empty title="Bạn chưa có hợp đồng nào"><Link className="button primary" to="/">Tìm căn hộ</Link></Empty>}
  </>
}

export function ContractDetail({ role }) {
  const { db, actor, save, apiConnected, refreshFromBackend } = useDemo()
  const { id } = useParams()
  const [modal, setModal] = useState('')
  const [error, setError] = useState('')
  const [signedAtLocation, setSignedAtLocation] = useState(false)
  const [depositReceived, setDepositReceived] = useState(false)
  const contract = db.contracts.find((item) => item.id === id && (role === 'tenant' ? item.tenantId : item.ownerId) === actor.id)
  const back = role === 'tenant' ? '/tenant' : '/owner/hop-dong'
  if (!contract) return <Empty title="Không tìm thấy hợp đồng"><Link className="button outline" to={back}>Về danh sách</Link></Empty>

  const waiting = contract.status === 'awaiting'
  const isSigned = contract.status === 'signed'
  const isCancelled = contract.status === 'cancelled'
  const tenant = identity(contract.tenantId) ?? { name: contract.tenantName ?? 'Khách thuê' }
  const owner = identity(contract.ownerId) ?? { name: contract.ownerName ?? 'Chủ nhà' }
  const deadline = moveInDeadline(contract)
  const breakdown = calculateLeaseBreakdown(contract.startDate, contract.endDate, contract.rent)
  const overdueNoShow = canCancelNoShow(contract)
  const contractUnit = db.apartments.find((item) => item.id === contract.apartmentId)
  const contractBuilding = db.buildings.find((item) => item.id === contractUnit?.buildingId || item.name === contract.propertyName)

  async function act(action, values, message) {
    try {
      if (apiConnected) {
        if (action === 'schedule') await backendApi.scheduleContract(id, values.appointment)
        else if (action === 'confirm-sign-and-deposit' || action === 'sign') {
          await backendApi.confirmSignAndDeposit(id, {
            signedAtLocation: true,
            depositReceived: true,
            receivedAmount: contract.deposit,
          })
        } else if (action === 'cancel-no-show') {
          await backendApi.cancelNoShow(id, values.today)
        } else if (action === 'handover') {
          await backendApi.recordHandover(id, {
            actualMoveIn: values.actualMoveIn || null,
            actualMoveOut: values.actualMoveOut || null,
          })
        } else if (action === 'renewal') {
          await backendApi.respondRenewal(id, values.choice)
        }
        await refreshFromBackend(actor.id)
      }
      save(updateContract(db, actor, id, action, values), message)
      setModal('')
      setError('')
    } catch (problem) {
      setError(problem.message)
    }
  }

  function open(name) {
    setError('')
    setSignedAtLocation(false)
    setDepositReceived(false)
    setModal(name)
  }

  return <>
    <Link className="back" to={back}><ArrowLeft size={17} /> Danh sách hợp đồng</Link>
    <div className="page-head"><div><h1>{contract.apartmentTitle}</h1><p>{contractCode(contract)} · Căn {contract.room}</p></div><ContractStatus contract={contract} /></div>
    {isCancelled && <Notice error><AlertTriangle size={17} /> {contract.cancellationReason || `Hợp đồng đã bị hủy do khách không dọn vào trong 3 ngày kể từ ngày quy định (${shortDate(contract.startDate)}). Khách hàng mất khoản tiền đặt cọc ${money(contract.deposit)}.`}</Notice>}
    {role === 'tenant' && renewalOpen(contract) && !contract.renewal && <div className="renewal-banner"><Bell size={22} /><div><b>{expired(contract) ? 'Hợp đồng đã hết hạn' : 'Hợp đồng sắp hết hạn'}</b><p>Bạn có muốn tiếp tục thuê sau ngày {shortDate(contract.endDate)}?</p></div><div className="actions"><button className="button outline" onClick={() => open('declined')}>Không gia hạn</button><button className="button primary" onClick={() => open('requested')}>Muốn gia hạn</button></div></div>}
    <div className="contract-detail-grid">
      <article className="panel terms"><h2>Thông tin hợp đồng</h2>
        <dl className="facts">
          <div><dt>Khách thuê</dt><dd>{tenant.name}</dd></div>
          <div><dt>Chủ nhà / Người môi giới</dt><dd>{owner.name}</dd></div>
          <div><dt>Thời hạn hợp đồng</dt><dd>{shortDate(contract.startDate)} – {shortDate(contract.endDate)}</dd></div>
          <div><dt>Hạn chót dọn vào (3 ngày)</dt><dd>{shortDate(deadline)}</dd></div>
          <div><dt>Tiền thuê mỗi tháng</dt><dd>{money(contract.rent)}</dd></div>
          <div><dt>Tiền đặt cọc ban đầu</dt><dd>{money(contract.deposit)}</dd></div>
          <div><dt>Tiền thuê kỳ đầu</dt><dd><strong>{money(breakdown.firstPeriodRent)}</strong></dd></div>
          <div><dt>Tiền thuê kỳ cuối</dt><dd><strong>{money(breakdown.lastPeriodRent)}</strong></dd></div>
          <div><dt>Đơn giá điện / nước</dt><dd>{money(contract.electricRate)}/kWh · {money(contract.waterRate)}/m³</dd></div>
          <div><dt>Gia hạn</dt><dd><RenewalStatus contract={contract} db={db} /></dd></div>
        </dl>

        <h2>Chu kỳ tính tiền thuê (từ ngày dọn vào {shortDate(contract.startDate)})</h2>
        <p className="subtle" style={{ marginBottom: 10 }}>
          Quy tắc: Tính từ ngày dọn vào trên hợp đồng theo thời hạn khách đăng ký thuê. 1 tháng tính theo tháng 30 hoặc 31 ngày; riêng tháng 2 vẫn tính theo 30 ngày kể từ ngày bắt đầu.
        </p>
        {breakdown.periods.length > 0 && <div className="table-wrap" style={{ marginBottom: 18 }}>
          <table>
            <thead>
              <tr><th>Kỳ</th><th>Từ ngày – Đến ngày</th><th>Số ngày / Chuẩn tháng</th><th>Tiền thuê kỳ</th></tr>
            </thead>
            <tbody>
              {breakdown.periods.map((p, idx) => (
                <tr key={p.periodIndex}>
                  <td>
                    <b>Kỳ {p.periodIndex}</b>
                    {idx === 0 && <small>Kỳ đầu</small>}
                    {idx > 0 && idx === breakdown.periods.length - 1 && <small>Kỳ cuối</small>}
                  </td>
                  <td>{shortDate(p.periodStart)} – {shortDate(p.periodEnd)}</td>
                  <td>{p.daysInPeriod}/{p.monthBasisDays} ngày</td>
                  <td><strong>{money(p.rentAmount)}</strong></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>}

        <h2>Điều khoản thuê</h2>
        <ol>
          <li>Tiền thuê cơ sở hàng tháng là <b>{money(contract.rent)}</b>. Tiền thuê kỳ đầu (<b>{money(breakdown.firstPeriodRent)}</b>) và kỳ cuối (<b>{money(breakdown.lastPeriodRent)}</b>) tính bắt đầu từ ngày dọn vào trên hợp đồng (<b>{shortDate(contract.startDate)}</b>); 1 tháng tùy theo tháng có 30 hay 31 ngày, đối với tháng 2 vẫn tính theo 30 ngày kể từ ngày bắt đầu.</li>
          <li>Người môi giới / Chủ nhà (<b>{owner.name}</b>) xác nhận khách đã ký tại địa điểm hẹn (<b>{contract.location}</b>) và ghi nhận khoản tiền đặt cọc <b>{money(contract.deposit)}</b> đã thanh toán thì hợp đồng được cập nhật trạng thái có hiệu lực.</li>
          <li>Nếu đến hết ngày <b>{shortDate(deadline)}</b> (trong vòng 3 ngày kể từ ngày dọn vào quy định <b>{shortDate(contract.startDate)}</b>) mà khách hàng không dọn vào nhận phòng, hợp đồng sẽ bị <b>hủy</b> và khách hàng <b>mất tiền đặt cọc {money(contract.deposit)}</b> trên hợp đồng.</li>
          <li>Nếu khách thuê trả phòng trước ngày kết thúc đã thỏa thuận, tiền thuê vẫn tính đủ theo hợp đồng. Nếu dọn vào trước ngày bắt đầu đã thỏa thuận, khoản phạt bằng một tháng tiền thuê (<b>{money(contract.rent)}</b>).</li>
        </ol>
      </article>
      <div className="stack detail-side">
        <article className="panel"><h2><CalendarDays size={20} /> Lịch ký hợp đồng</h2><p className="appointment-value">{appointmentText(contract.appointment)}</p><p className="place"><MapPin size={17} />{contract.location}</p>
          <AppointmentMiniMap contract={contract} building={contractBuilding} />
          {role === 'tenant' && waiting && <button className="button outline wide" onClick={() => open('schedule')}>{contract.appointment ? 'Đổi ngày giờ ký' : 'Đặt lịch ký'}</button>}
        </article>
        <article className="panel"><h2><ShieldCheck size={20} /> Xác nhận ký & thu tiền ban đầu</h2>
          <dl className="facts">
            <div><dt>Ký kết</dt><dd><ContractStatus contract={contract} /></dd></div>
            <div><dt>Thanh toán cọc</dt><dd><PaymentStatus paid={contract.payment === 'paid'} forfeited={contract.depositForfeited} /></dd></div>
            {contract.signedAt && <div><dt>Ngày xác nhận ký</dt><dd>{shortDate(contract.signedAt)}</dd></div>}
            {contract.signedConfirmedBy && <div><dt>Người xác nhận</dt><dd>{identity(contract.signedConfirmedBy)?.name ?? owner.name}</dd></div>}
          </dl>
          {waiting && role === 'owner' && <>
            <button className="button primary wide" disabled={!contract.appointment} onClick={() => open('confirm-sign')}>Xác nhận khách đã ký & thu cọc</button>
            {!contract.appointment && <p className="subtle">Chờ khách thuê đặt lịch hẹn ký trước khi xác nhận.</p>}
          </>}
          {waiting && role === 'tenant' && <>
            <Notice>Sau khi bạn đặt lịch và ký tại <b>{contract.location}</b>, người môi giới / chủ nhà <b>{owner.name}</b> sẽ xác nhận bạn đã ký và ghi nhận khoản tiền cọc <b>{money(contract.deposit)}</b> để kích hoạt hợp đồng.</Notice>
          </>}
        </article>
        {!waiting && <article className="panel"><h2>Bàn giao & Kiểm tra dọn vào (3 ngày)</h2>
          <dl className="facts">
            <div><dt>Ngày dọn vào theo HĐ</dt><dd>{shortDate(contract.startDate)}</dd></div>
            <div><dt>Hạn chót dọn vào (3 ngày)</dt><dd>{shortDate(deadline)}</dd></div>
            <div><dt>Nhận phòng thực tế</dt><dd>{shortDate(contract.actualMoveIn)}</dd></div>
            <div><dt>Trả phòng thực tế</dt><dd>{shortDate(contract.actualMoveOut)}</dd></div>
            {contract.depositForfeited && <div><dt>Tiền cọc</dt><dd><Status tone="amber">Mất cọc ({money(contract.deposit)})</Status></dd></div>}
          </dl>
          {role === 'owner' && isSigned && <div className="stack">
            <button className="button outline wide" onClick={() => open('handover')}>Ghi nhận bàn giao</button>
            {!contract.actualMoveIn && <button className="button primary wide" onClick={() => open('cancel-no-show')}>
              {overdueNoShow ? 'Hủy hợp đồng & giữ cọc (Đã quá 3 ngày)' : 'Kiểm tra / Hủy do quá 3 ngày không dọn vào'}
            </button>}
          </div>}
        </article>}
      </div>
    </div>

    {modal === 'schedule' && <Modal title={contract.appointment ? 'Đổi ngày giờ ký' : 'Đặt lịch ký hợp đồng'} close={() => setModal('')}>
      <form className="stack" onSubmit={(event) => { event.preventDefault(); act('schedule', { appointment: new FormData(event.currentTarget).get('appointment') }, 'Đã lưu ngày giờ ký.') }}>
        <Field label="Ngày và giờ ký (giờ Việt Nam)"><input name="appointment" type="datetime-local" min={nowInVietnam()} defaultValue={contract.appointment} required /></Field>
        <Notice><MapPin size={17} /> {contract.location}</Notice>{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu lịch ký</button>
      </form></Modal>}

    {modal === 'confirm-sign' && <Modal title="Người môi giới xác nhận ký & thu tiền ban đầu" close={() => setModal('')}>
      <form className="stack" onSubmit={(event) => {
        event.preventDefault()
        act(
          'confirm-sign-and-deposit',
          { signedAtLocation, depositReceived, accepted: signedAtLocation && depositReceived },
          'Đã xác nhận khách ký tại điểm hẹn và ghi nhận Đã thanh toán tiền cọc.',
        )
      }}>
        <Notice>Theo quy trình đã chốt: Người môi giới / Chủ nhà xác nhận khách đã ký tại địa điểm hẹn và ghi nhận đã nhận khoản tiền đặt cọc ban đầu để hợp đồng chuyển sang <b>Đã ký</b> và <b>Đã thanh toán</b>.</Notice>
        <label className="check-row">
          <input type="checkbox" checked={signedAtLocation} required onChange={(event) => setSignedAtLocation(event.target.checked)} />
          <span>Xác nhận khách thuê <b>{tenant.name}</b> đã ký hợp đồng tại địa điểm hẹn: <b>{contract.location}</b>.</span>
        </label>
        <label className="check-row">
          <input type="checkbox" checked={depositReceived} required onChange={(event) => setDepositReceived(event.target.checked)} />
          <span>Xác nhận đã thu đủ tiền đặt cọc ban đầu <b>{money(contract.deposit)}</b> (hệ thống ghi nhận khoản <b>Đã thanh toán</b> trên hợp đồng).</span>
        </label>
        {error && <Notice error>{error}</Notice>}
        <button className="button primary" disabled={!signedAtLocation || !depositReceived}>Xác nhận ký & Đã thanh toán</button>
      </form></Modal>}

    {modal === 'cancel-no-show' && <Modal title="Hủy hợp đồng do quá 3 ngày không dọn vào" close={() => setModal('')}>
      <form className="stack" onSubmit={(event) => {
        event.preventDefault()
        const checkDate = new FormData(event.currentTarget).get('checkDate')
        act('cancel-no-show', { today: checkDate }, 'Đã hủy hợp đồng do quá 3 ngày không nhận phòng và ghi nhận mất tiền cọc.')
      }}>
        <Notice>
          Ngày dọn vào quy định trên hợp đồng là <b>{shortDate(contract.startDate)}</b> (hạn chót 3 ngày là hết ngày <b>{shortDate(deadline)}</b>). Nếu sau ngày này khách vẫn chưa nhận phòng, hợp đồng sẽ bị hủy và khách mất khoản tiền cọc <b>{money(contract.deposit)}</b>.
        </Notice>
        <Field label="Ngày kiểm tra hạn nhận phòng">
          <input type="date" name="checkDate" defaultValue={dateAt() > deadline ? dateAt() : plusDays(deadline, 1)} required />
        </Field>
        {error && <Notice error>{error}</Notice>}
        <button className="button primary">Xác nhận hủy hợp đồng & giữ tiền cọc</button>
      </form></Modal>}

    {['declined', 'requested'].includes(modal) && <Modal title={modal === 'requested' ? 'Gửi yêu cầu gia hạn' : 'Xác nhận không gia hạn'} close={() => setModal('')}>
      <div className="stack"><p>{modal === 'requested' ? 'Chủ nhà sẽ nhận yêu cầu để lập hợp đồng mới cho kỳ thuê tiếp theo.' : 'Chủ nhà sẽ thấy lựa chọn của bạn trong danh sách khách thuê.'}</p>{error && <Notice error>{error}</Notice>}<button className="button primary" onClick={() => act('renewal', { choice: modal }, 'Đã lưu phản hồi gia hạn.')}>Xác nhận</button></div>
    </Modal>}

    {modal === 'handover' && <Modal title="Ghi nhận bàn giao" close={() => setModal('')}><form className="stack" onSubmit={(event) => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      act('handover', { actualMoveIn: form.get('actualMoveIn'), actualMoveOut: form.get('actualMoveOut') }, 'Đã lưu ngày bàn giao.')
    }}><Field label="Ngày nhận phòng thực tế"><input type="date" name="actualMoveIn" max={dateAt()} defaultValue={contract.actualMoveIn} /></Field>
    <Field label="Ngày trả phòng thực tế"><input type="date" name="actualMoveOut" max={dateAt()} defaultValue={contract.actualMoveOut} /></Field>
    <Notice>Lưu ý: Khách hàng phải nhận phòng không muộn hơn 3 ngày kể từ ngày dọn vào trên hợp đồng ({shortDate(deadline)}).</Notice>
    {error && <Notice error>{error}</Notice>}<button className="button primary">Lưu bàn giao</button></form></Modal>}
  </>
}

export function InvoiceDetail({ invoice, contract, close }) {
  const penalty = invoice.kind === 'early-move-in'
  return <Modal title={penalty ? 'Hóa đơn phạt nhận phòng sớm' : `Hóa đơn tháng ${invoice.month.split('-').reverse().join('/')}`} close={close}>
    <div className="stack"><p>{contract.apartmentTitle} · {contractCode(contract)}</p>
      <dl className="facts">
        {!penalty && invoice.periodStart && invoice.periodEnd && <div><dt>Chu kỳ tính tiền</dt><dd>{shortDate(invoice.periodStart)} – {shortDate(invoice.periodEnd)} ({invoice.daysInPeriod}/{invoice.monthBasisDays} ngày)</dd></div>}
        {!penalty && <><div><dt>Chỉ số điện (kWh)</dt><dd>{invoice.oldElectric} → {invoice.newElectric}</dd></div><div><dt>Chỉ số nước (m³)</dt><dd>{invoice.oldWater} → {invoice.newWater}</dd></div></>}
        {invoice.lines.map((line) => <div key={line.title}><dt>{line.title}</dt><dd>{money(line.amount)}</dd></div>)}
        <div><dt>Tổng cộng</dt><dd><strong>{money(invoiceTotal(invoice))}</strong></dd></div>
        <div><dt>Thanh toán</dt><dd><PaymentStatus paid={invoice.status === 'paid'} /></dd></div>
      </dl>
    </div>
  </Modal>
}

export function Invoices({ role }) {
  const { db, actor, save, apiConnected, refreshFromBackend } = useDemo()
  const [selectedId, selectId] = useState(null)
  const [confirm, setConfirm] = useState(null)
  const contracts = db.contracts.filter((item) => (role === 'owner' ? item.ownerId : item.tenantId) === actor.id)
  const byId = new Map(contracts.map((item) => [item.id, item]))
  const invoices = db.invoices.filter((invoice) => byId.has(invoice.contractId)).toReversed()
  const selected = invoices.find((invoice) => invoice.id === selectedId)
  const pending = invoices.find((invoice) => invoice.id === confirm && invoice.status === 'unpaid')
  return <>
    {invoices.length ? <div className="table-wrap"><table><thead><tr><th>Căn hộ / Hợp đồng</th><th>Kỳ hóa đơn</th><th>Tổng tiền</th><th>Thanh toán</th><th>Thao tác</th></tr></thead><tbody>
      {invoices.map((invoice) => { const contract = byId.get(invoice.contractId); return <tr key={invoice.id}><td><b>{contract.apartmentTitle}</b><small>{contractCode(contract)}</small></td><td>{invoice.month.split('-').reverse().join('/')}{invoice.kind === 'early-move-in' && <small>Phạt nhận phòng sớm</small>}</td><td>{money(invoiceTotal(invoice))}</td><td><PaymentStatus paid={invoice.status === 'paid'} /></td><td><div className="actions"><button className="button small outline" onClick={() => selectId(invoice.id)}>Chi tiết</button>{role === 'owner' && invoice.status === 'unpaid' && <button className="button small primary" onClick={() => setConfirm(invoice.id)}>Xác nhận đã thu</button>}</div></td></tr> })}
    </tbody></table></div> : <Empty title="Chưa có hóa đơn" />}
    {selected && <InvoiceDetail invoice={selected} contract={byId.get(selected.contractId)} close={() => selectId(null)} />}
    {pending && <Modal title="Xác nhận đã thu tiền" close={() => setConfirm(null)}><div className="stack"><p>Xác nhận đã thu {money(invoiceTotal(pending))} cho hóa đơn tháng {pending.month.split('-').reverse().join('/')}.</p><button className="button primary" onClick={async () => {
      if (apiConnected) {
        try {
          await backendApi.confirmInvoicePaid(confirm)
          await refreshFromBackend(actor.id)
        } catch {
          // Fallback to local update
        }
      }
      save({ ...db, invoices: db.invoices.map((item) => item.id === confirm ? { ...item, status: 'paid', paidAt: dateAt() } : item) }, 'Đã xác nhận thu tiền hóa đơn.')
      setConfirm(null)
    }}>Xác nhận</button></div></Modal>}
  </>
}

export function TenantInvoices() {
  return <><div className="page-head"><div><h1><Receipt size={31} /> Hóa đơn của tôi</h1><p>Xem tiền thuê theo chu kỳ hợp đồng, chỉ số điện nước và trạng thái thanh toán.</p></div></div><Invoices role="tenant" /></>
}
