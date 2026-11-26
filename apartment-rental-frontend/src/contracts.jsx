import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, ArrowRight, Bell, CalendarDays, Edit3, FileText, History, MapPin, Plus, Receipt, ShieldCheck, Trash2 } from 'lucide-react'
import {
  appointmentText, backendApi, calculateEndDateForTerm, calculateLeaseBreakdown, canCancelNoShow,
  contractCode, dateAt, expired, identity, invoiceTotal, IS_PROTOTYPE_MODE, money,
  moveInDeadline, nowInVietnam, plusDays, renewalOpen, runAppMutation, shortDate, updateContract,
} from './demo'
import { Empty, Field, Modal, Notice, Select, Status, useDemo } from './shared'

const historyActionLabels = {
  CREATED: 'Tạo hợp đồng chờ ký',
  SCHEDULED: 'Đặt / Đổi lịch hẹn ký',
  UPDATED: 'Cập nhật điều khoản hợp đồng chờ ký',
  CANCELLED_AWAITING: 'Hủy hợp đồng chờ ký (Trước khi ký)',
  SIGN_AND_DEPOSIT_CONFIRMED: 'Xác nhận ký & thu tiền cọc',
  HANDOVER_RECORDED: 'Ghi nhận bàn giao thực tế',
  RENEWAL_RESPONDED: 'Phản hồi gia hạn hợp đồng',
  CANCELLED_NO_SHOW_3_DAYS: 'Hủy hợp đồng quá hạn 3 ngày (Mất cọc)',
}

const leaseTermOptions = [
  ['1M', '1 tháng (30/31 ngày; tháng 2 tính 30 ngày)', 'MONTH', 1],
  ['6M', '6 tháng (theo chuẩn chu kỳ tháng)', 'MONTH', 6],
  ['1Y', '1 năm (12 tháng)', 'YEAR', 1],
  ['CUSTOM', 'Tùy chọn thời gian thuê', 'CUSTOM', 1],
]

export function ContractStatus({ contract }) {
  if (contract.status === 'cancelled') {
    return <Status tone="amber">{contract.depositForfeited ? 'Đã hủy (Mất cọc)' : 'Đã hủy (Trước khi ký)'}</Status>
  }
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
  const { db, actor, isPrototypeMode } = useDemo()
  const [statusFilter, setStatusFilter] = useState('all')
  const rawContracts = db.contracts.filter((item) => item.tenantId === actor.id)
  const contracts = isPrototypeMode ? rawContracts.toReversed() : rawContracts
  const notice = contracts.filter((item) => renewalOpen(item) && !item.renewal)

  const filtered = contracts.filter((item) => {
    if (statusFilter === 'awaiting') return item.status === 'awaiting'
    if (statusFilter === 'active') return item.status === 'signed' && !expired(item)
    if (statusFilter === 'renewal') return item.status === 'signed' && renewalOpen(item)
    if (statusFilter === 'cancelled') return item.status === 'cancelled'
    return true
  })

  return <>
    <div className="page-head">
      <div>
        <h1>Hợp đồng của tôi</h1>
        <p>Xem từng hợp đồng, đặt lịch hẹn ký, theo dõi chu kỳ tiền thuê và phản hồi gia hạn.</p>
      </div>
      <Link className="button outline" to="/">Tìm căn hộ <ArrowRight size={17} /></Link>
    </div>
    {notice.map((contract) => (
      <div className="renewal-banner" key={contract.id}>
        <Bell size={22} />
        <div>
          <b>{contract.apartmentTitle} {expired(contract) ? 'đã hết hạn' : 'sắp hết hạn'}</b>
          <p>Ngày hết hạn {shortDate(contract.endDate)}. Vui lòng chọn có gia hạn hay không.</p>
        </div>
        <Link className="button primary" to={`/tenant/hop-dong/${contract.id}`}>Phản hồi</Link>
      </div>
    ))}
    {contracts.length > 0 && (
      <div className="contract-filter-bar" style={{ marginBottom: 18 }}>
        <div className="actions">
          {[
            ['all', `Tất cả (${contracts.length})`],
            ['awaiting', `Chờ ký (${contracts.filter((c) => c.status === 'awaiting').length})`],
            ['active', `Đang hiệu lực (${contracts.filter((c) => c.status === 'signed' && !expired(c)).length})`],
            ['renewal', `Cần gia hạn (${contracts.filter((c) => c.status === 'signed' && renewalOpen(c)).length})`],
            ['cancelled', `Đã hủy (${contracts.filter((c) => c.status === 'cancelled').length})`],
          ].map(([key, label]) => (
            <button
              key={key}
              type="button"
              className={`button small ${statusFilter === key ? 'primary' : 'outline'}`}
              onClick={() => setStatusFilter(key)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
    )}
    {filtered.length ? (
      <div className="contract-list">
        {filtered.map((contract) => (
          <article className="panel contract-card" key={contract.id}>
            <div className="contract-card-top">
              <div className="step-icon"><FileText size={22} /></div>
              <div className="actions">
                <ContractStatus contract={contract} />
                <PaymentStatus paid={contract.payment === 'paid'} forfeited={contract.depositForfeited} />
              </div>
            </div>
            <p className="subtle">{contractCode(contract)} · Phòng {contract.room}</p>
            <h2>{contract.apartmentTitle}</h2>
            <p>Thời hạn: <b>{shortDate(contract.startDate)} – {shortDate(contract.endDate)}</b></p>
            {contract.status === 'awaiting' && (
              <p className="subtle" style={{ marginTop: 4 }}>
                Lịch hẹn ký: <b>{appointmentText(contract.appointment)}</b>
              </p>
            )}
            {contract.status === 'signed' && (
              <p className="subtle" style={{ marginTop: 4 }}>
                {contract.actualMoveIn
                  ? `Đã nhận phòng: ${shortDate(contract.actualMoveIn)}`
                  : `Hạn chót nhận phòng: ${shortDate(moveInDeadline(contract))}`}
              </p>
            )}
            <div className="contract-card-bottom">
              <strong>{money(contract.rent)}/tháng</strong>
              <Link className="text-link" to={`/tenant/hop-dong/${contract.id}`}>
                Quản lý hợp đồng <ArrowRight size={17} />
              </Link>
            </div>
          </article>
        ))}
      </div>
    ) : (
      <Empty title={contracts.length ? 'Không có hợp đồng ở trạng thái này' : 'Bạn chưa có hợp đồng nào'}>
        <Link className="button primary" to="/">Tìm căn hộ</Link>
      </Empty>
    )}
  </>
}

export function ContractDetail({ role }) {
  const { db, actor, save, notify, isPrototypeMode, markOffline, refreshFromBackend } = useDemo()
  const { id } = useParams()
  const navigate = useNavigate()
  const [modal, setModal] = useState('')
  const [error, setError] = useState('')
  const [signedAtLocation, setSignedAtLocation] = useState(false)
  const [depositReceived, setDepositReceived] = useState(false)
  const [receivedAmountInput, setReceivedAmountInput] = useState('')
  const [history, setHistory] = useState([])
  const [serverSchedule, setServerSchedule] = useState(null)
  const [selectedInvoice, setSelectedInvoice] = useState(null)

  const contract = db.contracts.find((item) => item.id === id && (role === 'tenant' ? item.tenantId : item.ownerId) === actor.id)
  const back = role === 'tenant' ? '/tenant' : '/owner/hop-dong'

  // Edit awaiting contract state
  const [editTermKey, setEditTermKey] = useState('6M')
  const [editStartDate, setEditStartDate] = useState('')
  const [editEndDate, setEditEndDate] = useState('')
  const [editRent, setEditRent] = useState(0)

  // Renewal contract creation state
  const [renewTermKey, setRenewTermKey] = useState('6M')
  const [renewStartDate, setRenewStartDate] = useState('')
  const [renewEndDate, setRenewEndDate] = useState('')
  const [renewRent, setRenewRent] = useState(0)
  const [renewAppointment, setRenewAppointment] = useState('')

  useEffect(() => {
    let cancelled = false
    if (!isPrototypeMode && contract?.id) {
      Promise.all([
        backendApi.getContractHistory(contract.id).catch(() => []),
        backendApi.getRentSchedule(contract.id).catch(() => null),
      ]).then(([histItems, scheduleData]) => {
        if (!cancelled) {
          setHistory(Array.isArray(histItems) ? histItems : [])
          setServerSchedule(scheduleData || null)
        }
      })
    } else {
      setHistory([])
      setServerSchedule(null)
    }
    return () => { cancelled = true }
  }, [isPrototypeMode, contract?.id, contract?.status, contract?.appointment, contract?.startDate, contract?.endDate, contract?.rent, contract?.actualMoveIn, contract?.actualMoveOut, contract?.renewal, contract?.handoverElectricReading, contract?.handoverWaterReading])

  if (!contract) return <Empty title="Không tìm thấy hợp đồng"><Link className="button outline" to={back}>Về danh sách</Link></Empty>

  const waiting = contract.status === 'awaiting'
  const isSigned = contract.status === 'signed'
  const isCancelled = contract.status === 'cancelled'
  const accountsList = db.accounts || []
  const tenant = accountsList.find((p) => p.id === contract.tenantId) ?? identity(contract.tenantId) ?? { name: contract.tenantName ?? 'Khách thuê' }
  const owner = accountsList.find((p) => p.id === contract.ownerId) ?? identity(contract.ownerId) ?? { name: contract.ownerName ?? 'Chủ nhà' }
  const deadline = moveInDeadline(contract)
  const breakdown = serverSchedule && Array.isArray(serverSchedule.periods)
    ? serverSchedule
    : calculateLeaseBreakdown(contract.startDate, contract.endDate, contract.rent)
  const overdueNoShow = canCancelNoShow(contract)
  const parentContract = contract.renewalOf ? db.contracts.find((item) => item.id === contract.renewalOf) : null
  const childContract = db.contracts.find((item) => item.renewalOf === contract.id && item.status !== 'cancelled')
  const contractInvoices = db.invoices.filter((inv) => inv.contractId === contract.id)
  const defaultAppointmentSlot = contract.appointment || `${dateAt(1)}T09:30`
  const defaultHandoverMoveIn = contract.actualMoveIn || (contract.startDate <= dateAt() ? contract.startDate : dateAt())

  async function act(action, values, message) {
    try {
      await runAppMutation({
        prototypeMode: isPrototypeMode,
        apiCall: async () => {
          if (action === 'schedule') {
            await backendApi.scheduleContract(id, values.appointment)
          } else if (action === 'update-awaiting') {
            await backendApi.updateAwaitingContract(id, values)
          } else if (action === 'cancel-awaiting') {
            await backendApi.cancelAwaitingContract(id, values.reason)
          } else if (action === 'confirm-sign-and-deposit') {
            await backendApi.confirmSignAndDeposit(id, {
              signedAtLocation: Boolean(values.signedAtLocation),
              depositReceived: Boolean(values.depositReceived),
              receivedAmount: values.receivedAmount,
            })
          } else if (action === 'cancel-no-show') {
            await backendApi.cancelNoShow(id)
          } else if (action === 'check-in') {
            await backendApi.recordCheckIn(id, {
              actualMoveIn: values.actualMoveIn,
              initialElectric: values.initialElectric,
              initialWater: values.initialWater,
            })
          } else if (action === 'check-out') {
            await backendApi.recordCheckOut(id, {
              actualMoveOut: values.actualMoveOut,
            })
          } else if (action === 'renewal') {
            await backendApi.respondRenewal(id, values.choice)
          }
          await refreshFromBackend()
          const [latestHistory, latestSchedule] = await Promise.all([
            backendApi.getContractHistory(id).catch(() => []),
            backendApi.getRentSchedule(id).catch(() => null),
          ])
          if (Array.isArray(latestHistory)) setHistory(latestHistory)
          if (latestSchedule) setServerSchedule(latestSchedule)
          notify(message)
        },
        prototypeCall: () => {
          const protoAction = (action === 'check-in' || action === 'check-out') ? 'handover' : action
          save(updateContract(db, actor, id, protoAction, values), message)
        },
      })
      setModal('')
      setError('')
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      setError(problem.message)
    }
  }

  function open(name) {
    setError('')
    setSignedAtLocation(false)
    setDepositReceived(false)
    setReceivedAmountInput('')
    if (name === 'edit-awaiting') {
      const matchedTerm = leaseTermOptions.find(([, , u, v]) => u === contract.leaseTermUnit && v === Number(contract.leaseTermValue))
      setEditTermKey(matchedTerm ? matchedTerm[0] : 'CUSTOM')
      setEditStartDate(contract.startDate)
      setEditEndDate(contract.endDate)
      setEditRent(contract.rent)
    } else if (name === 'create-renewal') {
      const nextStart = contract.endDate >= dateAt() ? plusDays(contract.endDate, 1) : dateAt()
      setRenewTermKey('6M')
      setRenewStartDate(nextStart)
      setRenewEndDate(calculateEndDateForTerm(nextStart, 'MONTH', 6))
      setRenewRent(contract.rent)
      setRenewAppointment(`${dateAt(1)}T09:30`)
    }
    setModal(name)
  }

  function handleEditTermChange(nextKey) {
    setEditTermKey(nextKey)
    const found = leaseTermOptions.find(([key]) => key === nextKey)
    if (found && found[2] !== 'CUSTOM' && editStartDate) {
      setEditEndDate(calculateEndDateForTerm(editStartDate, found[2], found[3]))
    }
  }

  function handleEditStartChange(nextStart) {
    setEditStartDate(nextStart)
    const found = leaseTermOptions.find(([key]) => key === editTermKey)
    if (found && found[2] !== 'CUSTOM' && nextStart) {
      setEditEndDate(calculateEndDateForTerm(nextStart, found[2], found[3]))
    }
  }

  function handleRenewTermChange(nextKey) {
    setRenewTermKey(nextKey)
    const found = leaseTermOptions.find(([key]) => key === nextKey)
    if (found && found[2] !== 'CUSTOM' && renewStartDate) {
      setRenewEndDate(calculateEndDateForTerm(renewStartDate, found[2], found[3]))
    }
  }

  function handleRenewStartChange(nextStart) {
    setRenewStartDate(nextStart)
    const found = leaseTermOptions.find(([key]) => key === renewTermKey)
    if (found && found[2] !== 'CUSTOM' && nextStart) {
      setRenewEndDate(calculateEndDateForTerm(nextStart, found[2], found[3]))
    }
  }

  async function submitRenewalContract(event) {
    event.preventDefault()
    setError('')
    const activeTerm = leaseTermOptions.find(([key]) => key === renewTermKey) ?? leaseTermOptions[1]
    try {
      if (isPrototypeMode) {
        throw new Error('Hãy dùng trang Hợp đồng để tạo gia hạn trong chế độ Prototype.')
      }
      const created = await backendApi.createContract({
        apartmentId: contract.apartmentId,
        tenantId: contract.tenantId,
        startDate: renewStartDate,
        endDate: renewEndDate,
        rent: Number(renewRent),
        renewalOf: contract.id,
        leaseTermUnit: activeTerm[2],
        leaseTermValue: activeTerm[3],
        appointment: renewAppointment || null,
      })
      await refreshFromBackend()
      notify('Đã lập hợp đồng gia hạn mới thành công.')
      setModal('')
      navigate(`/owner/hop-dong/${created.id}`)
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      setError(problem.message)
    }
  }

  const editBreakdown = calculateLeaseBreakdown(editStartDate, editEndDate, Number(editRent))
  const renewBreakdown = calculateLeaseBreakdown(renewStartDate, renewEndDate, Number(renewRent))
  const parsedReceivedAmount = receivedAmountInput.trim() === '' ? null : Number(receivedAmountInput)
  const receivedAmountValid = parsedReceivedAmount !== null && Number.isFinite(parsedReceivedAmount) && parsedReceivedAmount >= contract.deposit

  return <>
    <Link className="back" to={back}><ArrowLeft size={17} /> Danh sách hợp đồng</Link>
    <div className="page-head">
      <div>
        <h1>{contract.apartmentTitle}</h1>
        <p>
          {contractCode(contract)} · Căn {contract.room} · {contract.propertyName}
          {parentContract && (
            <> · <Link className="text-link" to={`${back === '/tenant' ? '/tenant/hop-dong' : '/owner/hop-dong'}/${parentContract.id}`}>Gia hạn từ {contractCode(parentContract)}</Link></>
          )}
        </p>
      </div>
      <div className="actions">
        <ContractStatus contract={contract} />
        <PaymentStatus paid={contract.payment === 'paid'} forfeited={contract.depositForfeited} />
      </div>
    </div>

    {isCancelled && (
      <Notice error>
        <AlertTriangle size={17} />{' '}
        {contract.cancellationReason || (contract.depositForfeited
          ? `Hợp đồng đã bị hủy do khách không dọn vào trong 3 ngày kể từ ngày quy định (${shortDate(contract.startDate)}). Khách hàng mất khoản tiền đặt cọc ${money(contract.deposit)}.`
          : 'Hợp đồng chờ ký đã được hủy trước khi ký kết.')}
      </Notice>
    )}

    {role === 'tenant' && renewalOpen(contract) && !contract.renewal && (
      <div className="renewal-banner">
        <Bell size={22} />
        <div>
          <b>{expired(contract) ? 'Hợp đồng đã hết hạn' : 'Hợp đồng sắp hết hạn'}</b>
          <p>Bạn có muốn tiếp tục thuê sau ngày {shortDate(contract.endDate)}?</p>
        </div>
        <div className="actions">
          <button className="button outline" onClick={() => open('declined')}>Không gia hạn</button>
          <button className="button primary" onClick={() => open('requested')}>Muốn gia hạn</button>
        </div>
      </div>
    )}

    {role === 'owner' && isSigned && contract.renewal === 'requested' && !childContract && (
      <div className="renewal-banner">
        <Bell size={22} />
        <div>
          <b>Khách thuê {tenant.name} yêu cầu gia hạn hợp đồng</b>
          <p>Hợp đồng hiện tại kết thúc vào ngày {shortDate(contract.endDate)}. Bạn có thể lập hợp đồng kỳ mới nối tiếp ngay tại đây.</p>
        </div>
        <button className="button primary" onClick={() => open('create-renewal')}>
          <Plus size={17} /> Lập hợp đồng gia hạn
        </button>
      </div>
    )}

    {childContract && (
      <div className="renewal-banner">
        <FileText size={22} />
        <div>
          <b>Đã có hợp đồng gia hạn kỳ tiếp theo ({contractCode(childContract)})</b>
          <p>Thời hạn mới: {shortDate(childContract.startDate)} – {shortDate(childContract.endDate)} · Trạng thái: {childContract.status === 'signed' ? 'Đã ký' : 'Chờ ký'}</p>
        </div>
        <Link className="button primary" to={`${role === 'tenant' ? '/tenant/hop-dong' : '/owner/hop-dong'}/${childContract.id}`}>
          Xem hợp đồng mới <ArrowRight size={17} />
        </Link>
      </div>
    )}

    <div className="contract-detail-grid">
      <article className="panel terms">
        <h2>Thông tin hợp đồng</h2>
        <dl className="facts">
          <div><dt>Mã hợp đồng</dt><dd><b>{contractCode(contract)}</b></dd></div>
          <div><dt>Khách thuê</dt><dd>{tenant.name}</dd></div>
          <div><dt>Chủ nhà / Người môi giới</dt><dd>{owner.name}</dd></div>
          <div><dt>Tòa nhà & Phòng</dt><dd>{contract.propertyName} · Phòng {contract.room}</dd></div>
          <div><dt>Thời hạn hợp đồng</dt><dd>{shortDate(contract.startDate)} – {shortDate(contract.endDate)} ({breakdown.totalPeriods} kỳ)</dd></div>
          <div><dt>Hạn chót dọn vào (3 ngày)</dt><dd>{shortDate(deadline)}</dd></div>
          <div><dt>Tiền thuê mỗi tháng</dt><dd>{money(contract.rent)}</dd></div>
          <div><dt>Tiền đặt cọc yêu cầu</dt><dd>{money(contract.deposit)}</dd></div>
          {contract.payment === 'paid' && (
            <div>
              <dt>Tiền cọc thực nhận</dt>
              <dd>
                {contract.depositVerificationStatus === 'verified' && contract.depositReceivedAmount != null
                  ? <strong>{money(contract.depositReceivedAmount)}</strong>
                  : <Status tone="amber">Dữ liệu lịch sử chưa xác minh</Status>}
              </dd>
            </div>
          )}
          <div><dt>Tiền thuê kỳ đầu</dt><dd><strong>{money(breakdown.firstPeriodRent)}</strong></dd></div>
          <div><dt>Tiền thuê kỳ cuối</dt><dd><strong>{money(breakdown.lastPeriodRent)}</strong></dd></div>
          <div><dt>Tổng tiền thuê toàn hợp đồng</dt><dd><strong>{money(breakdown.totalContractRent)}</strong></dd></div>
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
          <li>Người môi giới / Chủ nhà (<b>{owner.name}</b>) xác nhận khách đã ký tại địa điểm hẹn (<b>{contract.location}</b>) và ghi nhận số tiền đặt cọc thực nhận đủ <b>{money(contract.deposit)}</b> thì hợp đồng mới chuyển sang trạng thái có hiệu lực.</li>
          <li>Nếu đến hết ngày <b>{shortDate(deadline)}</b> (trong vòng 3 ngày kể từ ngày dọn vào quy định <b>{shortDate(contract.startDate)}</b>) mà khách hàng không dọn vào nhận phòng, hợp đồng sẽ bị <b>hủy</b> và khách hàng <b>mất tiền đặt cọc {money(contract.deposit)}</b> trên hợp đồng.</li>
          <li>Nếu khách thuê trả phòng trước ngày kết thúc đã thỏa thuận, tiền thuê vẫn tính đủ theo hợp đồng. Nếu dọn vào trước ngày bắt đầu đã thỏa thuận, khoản phạt bằng một tháng tiền thuê (<b>{money(contract.rent)}</b>).</li>
        </ol>

        {history.length > 0 && <>
          <h2 style={{ marginTop: 22 }}><History size={19} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} /> Lịch sử trạng thái hợp đồng</h2>
          <div className="table-wrap" style={{ marginTop: 10 }}>
            <table>
              <thead>
                <tr><th>Thời điểm</th><th>Sự kiện</th><th>Người thực hiện</th><th>Ghi chú</th></tr>
              </thead>
              <tbody>
                {history.map((entry) => (
                  <tr key={entry.id}>
                    <td>{appointmentText(entry.createdAt)}</td>
                    <td><b>{historyActionLabels[entry.actionType] ?? entry.actionType}</b></td>
                    <td>{entry.actorName || 'Hệ thống'}</td>
                    <td>{entry.note || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>}
      </article>

      <div className="stack detail-side">
        <article className="panel">
          <h2><CalendarDays size={20} /> Lịch ký hợp đồng</h2>
          <p className="appointment-value">{appointmentText(contract.appointment)}</p>
          <p className="place"><MapPin size={17} />{contract.location}</p>
          {waiting && (
            <div className="stack">
              <button className="button outline wide" onClick={() => open('schedule')}>
                <CalendarDays size={16} /> {contract.appointment ? 'Đổi ngày giờ ký' : 'Đặt lịch ký hợp đồng'}
              </button>
              <button className="button outline wide" onClick={() => open('edit-awaiting')}>
                <Edit3 size={16} /> Chỉnh sửa điều khoản
              </button>
              <button className="button outline wide" onClick={() => open('cancel-awaiting')}>
                <Trash2 size={16} /> Hủy hợp đồng chờ ký
              </button>
            </div>
          )}
        </article>

        <article className="panel">
          <h2><ShieldCheck size={20} /> Xác nhận ký & thu tiền ban đầu</h2>
          <dl className="facts">
            <div><dt>Ký kết</dt><dd><ContractStatus contract={contract} /></dd></div>
            <div><dt>Thanh toán cọc</dt><dd><PaymentStatus paid={contract.payment === 'paid'} forfeited={contract.depositForfeited} /></dd></div>
            {contract.depositVerificationStatus === 'verified' && contract.depositReceivedAmount != null && (
              <div><dt>Số cọc thực nhận</dt><dd><b>{money(contract.depositReceivedAmount)}</b></dd></div>
            )}
            {contract.depositVerificationStatus === 'legacy_unverified' && (
              <div><dt>Đối soát cọc</dt><dd><Status tone="amber">Lịch sử chưa xác minh</Status></dd></div>
            )}
            {contract.signedAt && <div><dt>Ngày xác nhận ký</dt><dd>{shortDate(contract.signedAt)}</dd></div>}
            {contract.signedConfirmedBy && <div><dt>Người xác nhận</dt><dd>{accountsList.find((p) => p.id === contract.signedConfirmedBy)?.name ?? identity(contract.signedConfirmedBy)?.name ?? owner.name}</dd></div>}
          </dl>
          {waiting && role === 'owner' && <>
            <button className="button primary wide" disabled={!contract.appointment} onClick={() => open('confirm-sign')}>
              Xác nhận khách đã ký & thu cọc
            </button>
            {!contract.appointment && (
              <p className="subtle">Hãy bấm <b>Đặt lịch ký hợp đồng</b> ở khung bên trên (hoặc chờ khách thuê đặt lịch) trước khi xác nhận ký & thu cọc.</p>
            )}
          </>}
          {waiting && role === 'tenant' && (
            <Notice>
              Sau khi đặt lịch và ký tại <b>{contract.location}</b>, người môi giới / chủ nhà <b>{owner.name}</b> sẽ xác nhận bạn đã ký và ghi nhận số tiền cọc thực nhận <b>{money(contract.deposit)}</b> để kích hoạt hợp đồng.
            </Notice>
          )}
        </article>

        {!waiting && (
          <article className="panel">
            <h2>Bàn giao & Kiểm tra dọn vào (3 ngày)</h2>
            <dl className="facts">
              <div><dt>Ngày dọn vào theo HĐ</dt><dd>{shortDate(contract.startDate)}</dd></div>
              <div><dt>Hạn chót dọn vào (3 ngày)</dt><dd>{shortDate(deadline)}</dd></div>
              <div><dt>Nhận phòng thực tế</dt><dd>{shortDate(contract.actualMoveIn)}</dd></div>
              <div><dt>Trả phòng thực tế</dt><dd>{shortDate(contract.actualMoveOut)}</dd></div>
              <div>
                <dt>Chỉ số bàn giao điện / nước</dt>
                <dd>
                  {contract.handoverMeterStatus === 'verified'
                    ? `${contract.handoverElectricReading} kWh · ${contract.handoverWaterReading} m³`
                    : contract.handoverMeterStatus === 'legacy_unverified'
                      ? <Status tone="amber">Lịch sử chưa ghi chỉ số bàn giao</Status>
                      : 'Chưa ghi nhận'}
                </dd>
              </div>
              {contract.depositForfeited && <div><dt>Tiền cọc</dt><dd><Status tone="amber">Mất cọc ({money(contract.deposit)})</Status></dd></div>}
            </dl>
            {role === 'owner' && isSigned && (
              <div className="stack">
                <button className="button outline wide" onClick={() => open('check-in')}>
                  {contract.actualMoveIn ? 'Cập nhật nhận phòng & chỉ số bàn giao' : 'Ghi nhận nhận phòng & chỉ số ban đầu'}
                </button>
                {contract.actualMoveIn && (
                  <button className="button outline wide" onClick={() => open('check-out')}>
                    {contract.actualMoveOut ? 'Cập nhật ngày trả phòng' : 'Ghi nhận trả phòng thực tế'}
                  </button>
                )}
                {!contract.actualMoveIn && (
                  <button className="button primary wide" onClick={() => open('cancel-no-show')}>
                    {overdueNoShow ? 'Hủy hợp đồng & giữ cọc (Đã quá 3 ngày)' : 'Kiểm tra / Hủy do quá 3 ngày không dọn vào'}
                  </button>
                )}
              </div>
            )}
          </article>
        )}

        {isSigned && (
          <article className="panel">
            <h2><Receipt size={20} /> Hóa đơn của hợp đồng ({contractInvoices.length})</h2>
            {contractInvoices.length > 0 ? (
              <div className="stack" style={{ marginTop: 10 }}>
                {contractInvoices.map((inv) => (
                  <div key={inv.id} className="facts" style={{ border: '1px solid var(--line)', borderRadius: 9, padding: '10px 12px', marginBottom: 4 }}>
                    <div>
                      <dt><b>{inv.kind === 'early-move-in' ? 'Phạt nhận phòng sớm' : `${inv.periodIndex ? `Kỳ ${inv.periodIndex} · ` : ''}Tháng ${inv.month.split('-').reverse().join('/')}`}</b></dt>
                      <dd><PaymentStatus paid={inv.status === 'paid'} /></dd>
                    </div>
                    <div>
                      <dt>{money(invoiceTotal(inv))}</dt>
                      <dd>
                        <button type="button" className="button small outline" onClick={() => setSelectedInvoice(inv)}>
                          Chi tiết
                        </button>
                      </dd>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="subtle" style={{ marginBottom: 12 }}>Chưa có hóa đơn nào được lập cho hợp đồng này.</p>
            )}
            <Link className="button outline wide" to={role === 'owner' ? '/owner/hoa-don' : '/tenant/hoa-don'}>
              {role === 'owner' ? 'Sang trang Lập & Quản lý hóa đơn' : 'Xem tất cả hóa đơn'} <ArrowRight size={16} />
            </Link>
          </article>
        )}
      </div>
    </div>

    {selectedInvoice && (
      <InvoiceDetail invoice={selectedInvoice} contract={contract} close={() => setSelectedInvoice(null)} />
    )}

    {modal === 'schedule' && (
      <Modal title={contract.appointment ? 'Đổi ngày giờ ký' : 'Đặt lịch ký hợp đồng'} close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          act('schedule', { appointment: new FormData(event.currentTarget).get('appointment') }, 'Đã lưu ngày giờ hẹn ký hợp đồng.')
        }}>
          <Field label="Ngày và giờ ký (giờ Việt Nam)">
            <input name="appointment" type="datetime-local" min={nowInVietnam()} defaultValue={defaultAppointmentSlot} required />
          </Field>
          <Notice><MapPin size={17} /> Địa điểm ký: <b>{contract.location}</b></Notice>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Lưu lịch ký</button>
        </form>
      </Modal>
    )}

    {modal === 'edit-awaiting' && (
      <Modal title="Chỉnh sửa điều khoản hợp đồng chờ ký" close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          const activeTerm = leaseTermOptions.find(([key]) => key === editTermKey) ?? leaseTermOptions[1]
          const payload = {
            startDate: editStartDate,
            endDate: editEndDate,
            leaseTermUnit: activeTerm[2],
            leaseTermValue: activeTerm[3],
          }
          if (role === 'owner') {
            payload.rent = Number(editRent)
          }
          act('update-awaiting', payload, 'Đã cập nhật điều khoản hợp đồng chờ ký.')
        }}>
          <Field label="Hình thức thời hạn thuê">
            <Select value={editTermKey} onChange={(event) => handleEditTermChange(event.target.value)}>
              {leaseTermOptions.map(([key, label]) => <option key={key} value={key}>{label}</option>)}
            </Select>
          </Field>
          <div className="form-grid">
            <Field label="Ngày bắt đầu (dọn vào)">
              <input type="date" min={dateAt()} value={editStartDate} onChange={(event) => handleEditStartChange(event.target.value)} required />
            </Field>
            <Field label="Ngày kết thúc">
              <input type="date" min={editStartDate || dateAt()} value={editEndDate} onChange={(event) => { setEditTermKey('CUSTOM'); setEditEndDate(event.target.value) }} required />
            </Field>
          </div>
          {role === 'owner' && (
            <Field label="Tiền thuê/tháng (₫)">
              <input type="number" min="1" value={editRent} onChange={(event) => setEditRent(Number(event.target.value))} required />
            </Field>
          )}
          {editBreakdown.totalPeriods > 0 && (
            <dl className="facts">
              <div><dt>Tiền đặt cọc ban đầu</dt><dd>{money(role === 'owner' ? Number(editRent) : contract.deposit)}</dd></div>
              <div><dt>Tiền thuê kỳ đầu</dt><dd>{money(editBreakdown.firstPeriodRent)}</dd></div>
              <div><dt>Tiền thuê kỳ cuối</dt><dd>{money(editBreakdown.lastPeriodRent)}</dd></div>
              <div><dt>Hạn chót nhận phòng (3 ngày)</dt><dd>{shortDate(plusDays(editStartDate, 3))}</dd></div>
            </dl>
          )}
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Lưu thay đổi</button>
        </form>
      </Modal>
    )}

    {modal === 'cancel-awaiting' && (
      <Modal title="Hủy hợp đồng chờ ký" close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          const reason = new FormData(event.currentTarget).get('reason')
          act('cancel-awaiting', { reason }, 'Đã hủy hợp đồng chờ ký.')
        }}>
          <Notice>
            Hợp đồng này đang ở trạng thái <b>Chờ ký</b> và chưa thu tiền đặt cọc. Sau khi hủy, căn hộ và lịch thuê sẽ được giải phóng.
          </Notice>
          <Field label="Lý do hủy hợp đồng chờ ký">
            <input name="reason" defaultValue={role === 'owner' ? 'Chủ nhà hủy dự thảo hợp đồng chờ ký.' : 'Khách thuê thay đổi kế hoạch thuê trước khi ký.'} required />
          </Field>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Xác nhận hủy hợp đồng chờ ký</button>
        </form>
      </Modal>
    )}

    {modal === 'confirm-sign' && (
      <Modal title="Người môi giới xác nhận ký & thu tiền ban đầu" close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          if (receivedAmountInput.trim() === '') {
            setError('Vui lòng nhập số tiền đặt cọc thực nhận.')
            return
          }
          act(
            'confirm-sign-and-deposit',
            {
              signedAtLocation,
              depositReceived,
              receivedAmount: Number(receivedAmountInput),
            },
            'Đã xác nhận khách ký tại điểm hẹn và ghi nhận Đã thanh toán tiền cọc.',
          )
        }}>
          <Notice>Theo quy trình nghiệp vụ: Người môi giới / Chủ nhà xác nhận khách đã ký tại địa điểm hẹn và nhập chính xác số tiền đặt cọc thực nhận (tối thiểu <b>{money(contract.deposit)}</b>) để hợp đồng chuyển sang <b>Đã ký</b> và <b>Đã thanh toán</b>.</Notice>
          <Field label={`Số tiền đặt cọc thực nhận (₫) — yêu cầu tối thiểu ${money(contract.deposit)}`}>
            <input
              type="number"
              name="receivedAmount"
              min={contract.deposit}
              step="1"
              placeholder={`Nhập số tiền thực nhận (ví dụ ${contract.deposit})`}
              value={receivedAmountInput}
              onChange={(event) => setReceivedAmountInput(event.target.value)}
              required
            />
          </Field>
          {parsedReceivedAmount !== null && parsedReceivedAmount > contract.deposit && (
            <Notice>
              Số tiền thực nhận vượt mức đặt cọc {money(parsedReceivedAmount - contract.deposit)}. Khoản vượt sẽ được ghi nhận vào lịch sử để đối soát riêng và không tự trừ vào tiền thuê hàng tháng.
            </Notice>
          )}
          <label className="check-row">
            <input type="checkbox" checked={signedAtLocation} required onChange={(event) => setSignedAtLocation(event.target.checked)} />
            <span>Xác nhận khách thuê <b>{tenant.name}</b> đã ký hợp đồng tại địa điểm hẹn: <b>{contract.location}</b>.</span>
          </label>
          <label className="check-row">
            <input type="checkbox" checked={depositReceived} required onChange={(event) => setDepositReceived(event.target.checked)} />
            <span>Xác nhận đã thu đủ tiền đặt cọc ban đầu theo số tiền thực nhận đã nhập.</span>
          </label>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary" disabled={!signedAtLocation || !depositReceived || !receivedAmountValid}>Xác nhận ký & Đã thanh toán</button>
        </form>
      </Modal>
    )}

    {modal === 'cancel-no-show' && (
      <Modal title="Hủy hợp đồng do quá 3 ngày không dọn vào" close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          act('cancel-no-show', {}, 'Đã hủy hợp đồng do quá 3 ngày không nhận phòng và ghi nhận mất tiền cọc.')
        }}>
          <Notice>
            Ngày dọn vào quy định trên hợp đồng là <b>{shortDate(contract.startDate)}</b> (hạn chót 3 ngày là hết ngày <b>{shortDate(deadline)}</b>). Máy chủ kiểm tra theo giờ Việt Nam hiện tại; chỉ cho phép hủy và giữ tiền cọc <b>{money(contract.deposit)}</b> sau khi quá hạn chót và khách chưa nhận phòng.
          </Notice>
          {!overdueNoShow && (
            <Notice error>
              Hiện tại chưa quá hạn chót nhận phòng 3 ngày ({shortDate(deadline)}). Yêu cầu hủy sớm sẽ bị máy chủ từ chối.
            </Notice>
          )}
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Xác nhận hủy hợp đồng & giữ tiền cọc</button>
        </form>
      </Modal>
    )}

    {['declined', 'requested'].includes(modal) && (
      <Modal title={modal === 'requested' ? 'Gửi yêu cầu gia hạn' : 'Xác nhận không gia hạn'} close={() => setModal('')}>
        <div className="stack">
          <p>{modal === 'requested' ? 'Chủ nhà sẽ nhận yêu cầu để lập hợp đồng mới cho kỳ thuê tiếp theo.' : 'Chủ nhà sẽ thấy lựa chọn của bạn trong danh sách khách thuê.'}</p>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary" onClick={() => act('renewal', { choice: modal }, 'Đã lưu phản hồi gia hạn.')}>Xác nhận</button>
        </div>
      </Modal>
    )}

    {modal === 'check-in' && (
      <Modal title="Ghi nhận nhận phòng & chỉ số bàn giao" close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          const form = new FormData(event.currentTarget)
          act('check-in', {
            actualMoveIn: form.get('actualMoveIn'),
            initialElectric: Number(form.get('initialElectric')),
            initialWater: Number(form.get('initialWater')),
          }, 'Đã lưu ngày nhận phòng và chỉ số điện nước bàn giao.')
        }}>
          <Field label="Ngày nhận phòng thực tế">
            <input type="date" name="actualMoveIn" max={dateAt()} defaultValue={defaultHandoverMoveIn} required />
          </Field>
          <div className="form-grid">
            <Field label="Chỉ số điện bàn giao (kWh)">
              <input type="number" name="initialElectric" min="0" step="1" defaultValue={contract.handoverElectricReading ?? ''} placeholder="Ví dụ: 100" required />
            </Field>
            <Field label="Chỉ số nước bàn giao (m³)">
              <input type="number" name="initialWater" min="0" step="1" defaultValue={contract.handoverWaterReading ?? ''} placeholder="Ví dụ: 10" required />
            </Field>
          </div>
          <Notice>
            Lưu ý: Chỉ số điện nước bàn giao là mốc bắt đầu cho hóa đơn kỳ 1. Ngày dọn vào trên hợp đồng là <b>{shortDate(contract.startDate)}</b> (hạn chót 3 ngày: <b>{shortDate(deadline)}</b>). Nhận phòng trước ngày {shortDate(contract.startDate)} phát sinh đúng 1 hóa đơn phạt bằng 1 tháng tiền thuê.
          </Notice>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Lưu nhận phòng & chỉ số bàn giao</button>
        </form>
      </Modal>
    )}

    {modal === 'check-out' && (
      <Modal title="Ghi nhận trả phòng thực tế" close={() => setModal('')}>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault()
          const form = new FormData(event.currentTarget)
          act('check-out', { actualMoveOut: form.get('actualMoveOut') }, 'Đã lưu ngày trả phòng thực tế.')
        }}>
          <Notice>
            Ngày nhận phòng đã xác nhận: <b>{shortDate(contract.actualMoveIn)}</b> (được bảo toàn, không bị xóa khi ghi nhận trả phòng). Nếu khách trả phòng sớm hơn ngày kết thúc hợp đồng, tiền thuê các kỳ vẫn giữ nguyên theo thỏa thuận.
          </Notice>
          <Field label="Ngày trả phòng thực tế">
            <input type="date" name="actualMoveOut" min={contract.actualMoveIn} max={dateAt()} defaultValue={contract.actualMoveOut || dateAt()} required />
          </Field>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Lưu ngày trả phòng</button>
        </form>
      </Modal>
    )}

    {modal === 'create-renewal' && (
      <Modal title={`Lập hợp đồng gia hạn cho ${contractCode(contract)}`} close={() => setModal('')}>
        <form className="stack" onSubmit={submitRenewalContract}>
          <Notice>
            Căn hộ: <b>{contract.apartmentTitle} ({contract.room})</b> · Khách thuê: <b>{tenant.name}</b>. Hợp đồng mới nối tiếp sau ngày kết thúc ({shortDate(contract.endDate)}) của {contractCode(contract)}.
          </Notice>
          <Field label="Hình thức thời hạn thuê kỳ mới">
            <Select value={renewTermKey} onChange={(event) => handleRenewTermChange(event.target.value)}>
              {leaseTermOptions.map(([key, label]) => <option key={key} value={key}>{label}</option>)}
            </Select>
          </Field>
          <div className="form-grid">
            <Field label="Ngày bắt đầu kỳ mới">
              <input type="date" min={plusDays(contract.endDate, 1)} value={renewStartDate} onChange={(event) => handleRenewStartChange(event.target.value)} required />
            </Field>
            <Field label="Ngày kết thúc kỳ mới">
              <input type="date" min={renewStartDate} value={renewEndDate} onChange={(event) => { setRenewTermKey('CUSTOM'); setRenewEndDate(event.target.value) }} required />
            </Field>
          </div>
          <Field label="Tiền thuê/tháng (₫)">
            <input type="number" min="1" value={renewRent} onChange={(event) => setRenewRent(Number(event.target.value))} required />
          </Field>
          <Field label="Lịch hẹn ký hợp đồng mới (giờ Việt Nam)">
            <input type="datetime-local" min={nowInVietnam()} value={renewAppointment} onChange={(event) => setRenewAppointment(event.target.value)} />
          </Field>
          {renewBreakdown.totalPeriods > 0 && (
            <dl className="facts">
              <div><dt>Tiền đặt cọc hợp đồng mới</dt><dd>{money(Number(renewRent))}</dd></div>
              <div><dt>Tiền thuê kỳ đầu</dt><dd>{money(renewBreakdown.firstPeriodRent)}</dd></div>
              <div><dt>Tiền thuê kỳ cuối</dt><dd>{money(renewBreakdown.lastPeriodRent)}</dd></div>
            </dl>
          )}
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Tạo hợp đồng gia hạn chờ ký</button>
        </form>
      </Modal>
    )}
  </>
}

export function InvoiceDetail({ invoice, contract, close }) {
  const penalty = invoice.kind === 'early-move-in'
  return <Modal title={penalty ? 'Hóa đơn phạt nhận phòng sớm' : `Hóa đơn ${invoice.periodIndex ? `Kỳ ${invoice.periodIndex} · ` : ''}Tháng ${invoice.month.split('-').reverse().join('/')}`} close={close}>
    <div className="stack"><p>{contract.apartmentTitle} · {contractCode(contract)}</p>
      <dl className="facts">
        {!penalty && invoice.periodStart && invoice.periodEnd && <div><dt>Chu kỳ tính tiền</dt><dd>{shortDate(invoice.periodStart)} – {shortDate(invoice.periodEnd)} ({invoice.daysInPeriod}/{invoice.monthBasisDays} ngày)</dd></div>}
        {!penalty && <><div><dt>Chỉ số điện (kWh)</dt><dd>{invoice.oldElectric} → {invoice.newElectric}</dd></div><div><dt>Chỉ số nước (m³)</dt><dd>{invoice.oldWater} → {invoice.newWater}</dd></div></>}
        {invoice.lines.map((line) => <div key={line.title}><dt>{line.title}</dt><dd>{money(line.amount)}</dd></div>)}
        <div><dt>Tổng cộng</dt><dd><strong>{money(invoiceTotal(invoice))}</strong></dd></div>
        <div><dt>Thanh toán</dt><dd><PaymentStatus paid={invoice.status === 'paid'} /></dd></div>
        {invoice.paidAt && <div><dt>Ngày xác nhận thu</dt><dd>{shortDate(invoice.paidAt)}</dd></div>}
      </dl>
    </div>
  </Modal>
}

export function Invoices({ role }) {
  const { db, actor, save, notify, isPrototypeMode, markOffline, refreshFromBackend } = useDemo()
  const [selectedId, selectId] = useState(null)
  const [confirm, setConfirm] = useState(null)
  const [confirmError, setConfirmError] = useState('')
  const contracts = db.contracts.filter((item) => (role === 'owner' ? item.ownerId : item.tenantId) === actor.id)
  const byId = new Map(contracts.map((item) => [item.id, item]))
  const matchedInvoices = db.invoices.filter((invoice) => byId.has(invoice.contractId))
  const invoices = isPrototypeMode ? matchedInvoices.toReversed() : matchedInvoices
  const selected = invoices.find((invoice) => invoice.id === selectedId)
  const pending = invoices.find((invoice) => invoice.id === confirm && invoice.status === 'unpaid')
  return <>
    {invoices.length ? <div className="table-wrap"><table><thead><tr><th>Căn hộ / Hợp đồng</th><th>Kỳ hóa đơn</th><th>Tổng tiền</th><th>Thanh toán</th><th>Thao tác</th></tr></thead><tbody>
      {invoices.map((invoice) => {
        const contract = byId.get(invoice.contractId)
        return <tr key={invoice.id}>
          <td><b>{contract.apartmentTitle}</b><small>{contractCode(contract)}</small></td>
          <td>
            {invoice.kind === 'early-move-in'
              ? <><span>{invoice.month.split('-').reverse().join('/')}</span><small>Phạt nhận phòng sớm</small></>
              : <><span>{invoice.periodIndex ? `Kỳ ${invoice.periodIndex} · ` : ''}{invoice.month.split('-').reverse().join('/')}</span></>}
          </td>
          <td>{money(invoiceTotal(invoice))}</td>
          <td><PaymentStatus paid={invoice.status === 'paid'} /></td>
          <td><div className="actions"><button className="button small outline" onClick={() => selectId(invoice.id)}>Chi tiết</button>{role === 'owner' && invoice.status === 'unpaid' && <button className="button small primary" onClick={() => { setConfirmError(''); setConfirm(invoice.id) }}>Xác nhận đã thu</button>}</div></td>
        </tr>
      })}
    </tbody></table></div> : <Empty title="Chưa có hóa đơn" />}
    {selected && <InvoiceDetail invoice={selected} contract={byId.get(selected.contractId)} close={() => selectId(null)} />}
    {pending && <Modal title="Xác nhận đã thu tiền" close={() => setConfirm(null)}><div className="stack">
      <p>Xác nhận đã thu {money(invoiceTotal(pending))} cho hóa đơn {pending.periodIndex ? `kỳ ${pending.periodIndex} (` : 'tháng '}{pending.month.split('-').reverse().join('/')}{pending.periodIndex ? ')' : ''}.</p>
      {confirmError && <Notice error>{confirmError}</Notice>}
      <button className="button primary" onClick={async () => {
        setConfirmError('')
        try {
          await runAppMutation({
            prototypeMode: isPrototypeMode,
            apiCall: async () => {
              await backendApi.confirmInvoicePaid(confirm)
              await refreshFromBackend()
              notify('Đã xác nhận thu tiền hóa đơn.')
            },
            prototypeCall: () => {
              save({ ...db, invoices: db.invoices.map((item) => item.id === confirm ? { ...item, status: 'paid', paidAt: dateAt() } : item) }, 'Đã xác nhận thu tiền hóa đơn.')
            },
          })
          setConfirm(null)
        } catch (problem) {
          if (problem.isNetworkError) markOffline()
          setConfirmError(problem.message)
        }
      }}>Xác nhận</button>
    </div></Modal>}
  </>
}

export function TenantInvoices() {
  return <><div className="page-head"><div><h1><Receipt size={31} /> Hóa đơn của tôi</h1><p>Xem tiền thuê theo chu kỳ hợp đồng, chỉ số điện nước và trạng thái thanh toán.</p></div></div><Invoices role="tenant" /></>
}
