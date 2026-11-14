import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, ArrowRight, Bell, CalendarDays, FileText, MapPin, Receipt, ShieldCheck } from 'lucide-react'
import { appointmentText, contractCode, dateAt, expired, identity, invoiceTotal, money, nowInVietnam, renewalOpen, shortDate, updateContract } from './demo'
import { Empty, Field, Modal, Notice, Status, useDemo } from './shared'

export function ContractStatus({ contract }) {
  return expired(contract) ? <Status>Đã hết hạn</Status> : contract.status === 'signed' ? <Status tone="green">Đã ký</Status> : <Status tone="amber">Chờ ký</Status>
}

export function PaymentStatus({ paid }) {
  return <Status tone={paid ? 'green' : 'amber'}>{paid ? 'Đã thanh toán' : 'Chưa thanh toán'}</Status>
}

export function RenewalStatus({ contract, db }) {
  const child = db.contracts.find((item) => item.renewalOf === contract.id)
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
    <div className="page-head"><div><h1>Hợp đồng của tôi</h1><p>Xem từng hợp đồng, lịch ký và thông tin gia hạn.</p></div><Link className="button outline" to="/">Tìm căn hộ <ArrowRight size={17} /></Link></div>
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
  const { db, actor, save } = useDemo()
  const { id } = useParams()
  const [modal, setModal] = useState('')
  const [error, setError] = useState('')
  const [accepted, setAccepted] = useState(false)
  const contract = db.contracts.find((item) => item.id === id && (role === 'tenant' ? item.tenantId : item.ownerId) === actor.id)
  const back = role === 'tenant' ? '/tenant' : '/owner/hop-dong'
  if (!contract) return <Empty title="Không tìm thấy hợp đồng"><Link className="button outline" to={back}>Về danh sách</Link></Empty>
  const waiting = contract.status === 'awaiting'
  const tenant = identity(contract.tenantId)
  const owner = identity(contract.ownerId)
  function act(action, values, message) {
    try {
      save(updateContract(db, actor, id, action, values), message)
      setModal('')
      setError('')
    } catch (problem) { setError(problem.message) }
  }
  function open(name) { setError(''); setAccepted(false); setModal(name) }
  return <>
    <Link className="back" to={back}><ArrowLeft size={17} /> Danh sách hợp đồng</Link>
    <div className="page-head"><div><h1>{contract.apartmentTitle}</h1><p>{contractCode(contract)} · Căn {contract.room}</p></div><ContractStatus contract={contract} /></div>
    {role === 'tenant' && renewalOpen(contract) && !contract.renewal && <div className="renewal-banner"><Bell size={22} /><div><b>{expired(contract) ? 'Hợp đồng đã hết hạn' : 'Hợp đồng sắp hết hạn'}</b><p>Bạn có muốn tiếp tục thuê sau ngày {shortDate(contract.endDate)}?</p></div><div className="actions"><button className="button outline" onClick={() => open('declined')}>Không gia hạn</button><button className="button primary" onClick={() => open('requested')}>Muốn gia hạn</button></div></div>}
    <div className="contract-detail-grid">
      <article className="panel terms"><h2>Thông tin hợp đồng</h2>
        <dl className="facts">
          <div><dt>Khách thuê</dt><dd>{tenant.name}</dd></div><div><dt>Chủ nhà</dt><dd>{owner.name}</dd></div>
          <div><dt>Thời hạn</dt><dd>{shortDate(contract.startDate)} – {shortDate(contract.endDate)}</dd></div>
          <div><dt>Tiền thuê mỗi tháng</dt><dd>{money(contract.rent)}</dd></div><div><dt>Tiền đặt cọc</dt><dd>{money(contract.deposit)}</dd></div>
          <div><dt>Đơn giá điện / nước</dt><dd>{money(contract.electricRate)}/kWh · {money(contract.waterRate)}/m³</dd></div>
          <div><dt>Gia hạn</dt><dd><RenewalStatus contract={contract} db={db} /></dd></div>
        </dl>
        <h2>Điều khoản thuê</h2>
        <ol><li>Tiền thuê hàng tháng là <b>{money(contract.rent)}</b> theo thời hạn trên. Tiền điện và nước tính theo lượng tiêu thụ và đơn giá ghi trong hợp đồng.</li>
          <li>Nếu khách thuê trả phòng trước ngày kết thúc đã thỏa thuận, tiền thuê vẫn tính đủ theo hợp đồng, không giảm cho số ngày còn lại.</li>
          <li>Nếu khách thuê dọn vào trước ngày bắt đầu đã thỏa thuận, khoản phạt bằng một tháng tiền thuê, tương đương <b>{money(contract.rent)}</b>.</li>
          <li>Địa điểm ký là <b>{contract.location}</b>. Khách thuê được chọn ngày và giờ ký.</li>
        </ol>
      </article>
      <div className="stack detail-side">
        <article className="panel"><h2><CalendarDays size={20} /> Lịch ký hợp đồng</h2><p className="appointment-value">{appointmentText(contract.appointment)}</p><p className="place"><MapPin size={17} />{contract.location}</p>
          {role === 'tenant' && waiting && <button className="button outline wide" onClick={() => open('schedule')}>{contract.appointment ? 'Đổi ngày giờ ký' : 'Đặt lịch ký'}</button>}
        </article>
        <article className="panel"><h2><ShieldCheck size={20} /> Xác nhận hợp đồng</h2><dl className="facts"><div><dt>Ký kết</dt><dd><ContractStatus contract={contract} /></dd></div><div><dt>Thanh toán</dt><dd><PaymentStatus paid={contract.payment === 'paid'} /></dd></div>{contract.signedAt && <div><dt>Ngày ký</dt><dd>{shortDate(contract.signedAt)}</dd></div>}</dl>
          {role === 'tenant' && waiting && <><button className="button primary wide" disabled={!contract.appointment} onClick={() => open('sign')}>Ký hợp đồng (demo)</button>{!contract.appointment && <p className="subtle">Đặt lịch trước khi ký.</p>}</>}
        </article>
        {!waiting && <article className="panel"><h2>Bàn giao căn hộ</h2><dl className="facts"><div><dt>Nhận phòng thực tế</dt><dd>{shortDate(contract.actualMoveIn)}</dd></div><div><dt>Trả phòng thực tế</dt><dd>{shortDate(contract.actualMoveOut)}</dd></div></dl>
          {role === 'owner' && <button className="button outline wide" onClick={() => open('handover')}>Ghi nhận bàn giao</button>}</article>}
      </div>
    </div>
    {modal === 'schedule' && <Modal title={contract.appointment ? 'Đổi ngày giờ ký' : 'Đặt lịch ký hợp đồng'} close={() => setModal('')}>
      <form className="stack" onSubmit={(event) => { event.preventDefault(); act('schedule', { appointment: new FormData(event.currentTarget).get('appointment') }, 'Đã lưu ngày giờ ký.') }}>
        <Field label="Ngày và giờ ký (giờ Việt Nam)"><input name="appointment" type="datetime-local" min={nowInVietnam()} defaultValue={contract.appointment} required /></Field>
        <Notice><MapPin size={17} /> {contract.location}</Notice>{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu lịch ký</button>
      </form></Modal>}
    {modal === 'sign' && <Modal title="Xác nhận ký hợp đồng" close={() => setModal('')}>
      <form className="stack" onSubmit={(event) => { event.preventDefault(); act('sign', { accepted }, 'Đã ký hợp đồng và cập nhật trạng thái thanh toán.') }}>
        <Notice>Đây là thao tác ký và ghi nhận thanh toán mô phỏng.</Notice>
        <label className="check-row"><input type="checkbox" checked={accepted} required onChange={(event) => setAccepted(event.target.checked)} /><span>Tôi đã đọc và đồng ý với thời hạn, tiền thuê, điều khoản trả phòng sớm và khoản phạt khi dọn vào sớm.</span></label>
        {error && <Notice error>{error}</Notice>}<button className="button primary" disabled={!accepted}>Xác nhận ký hợp đồng</button>
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
    {error && <Notice error>{error}</Notice>}<button className="button primary">Lưu bàn giao</button></form></Modal>}
  </>
}

export function InvoiceDetail({ invoice, contract, close }) {
  const penalty = invoice.kind === 'early-move-in'
  return <Modal title={penalty ? 'Hóa đơn phạt nhận phòng sớm' : `Hóa đơn tháng ${invoice.month.split('-').reverse().join('/')}`} close={close}>
    <div className="stack"><p>{contract.apartmentTitle} · {contractCode(contract)}</p>
      <dl className="facts">{!penalty && <><div><dt>Chỉ số điện (kWh)</dt><dd>{invoice.oldElectric} → {invoice.newElectric}</dd></div><div><dt>Chỉ số nước (m³)</dt><dd>{invoice.oldWater} → {invoice.newWater}</dd></div></>}{invoice.lines.map((line) => <div key={line.title}><dt>{line.title}</dt><dd>{money(line.amount)}</dd></div>)}<div><dt>Tổng cộng</dt><dd><strong>{money(invoiceTotal(invoice))}</strong></dd></div><div><dt>Thanh toán</dt><dd><PaymentStatus paid={invoice.status === 'paid'} /></dd></div></dl>
    </div>
  </Modal>
}

export function Invoices({ role }) {
  const { db, actor, save } = useDemo()
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
    {pending && <Modal title="Xác nhận đã thu tiền" close={() => setConfirm(null)}><div className="stack"><p>Xác nhận đã thu {money(invoiceTotal(pending))} cho hóa đơn tháng {pending.month.split('-').reverse().join('/')}.</p><button className="button primary" onClick={() => { save({ ...db, invoices: db.invoices.map((item) => item.id === confirm ? { ...item, status: 'paid', paidAt: dateAt() } : item) }, 'Đã xác nhận thu tiền hóa đơn.'); setConfirm(null) }}>Xác nhận</button></div></Modal>}
  </>
}

export function TenantInvoices() {
  return <><div className="page-head"><div><h1><Receipt size={31} /> Hóa đơn của tôi</h1><p>Xem tiền thuê, chỉ số điện nước và trạng thái thanh toán theo tháng.</p></div></div><Invoices role="tenant" /></>
}

