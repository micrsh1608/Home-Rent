import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowRight, Building2, CircleDollarSign, ClipboardList, DoorOpen, Plus, Receipt, Wrench } from 'lucide-react'
import { addContract, billForMonth, contractCode, dateAt, expired, identity, invoiceTotal, money, people, plusDays, renewalOpen, shortDate, unitImage, unitStatus } from './demo'
import { ContractStatus, Invoices, PaymentStatus, RenewalStatus } from './contracts'
import { Empty, Field, Modal, Notice, Select, Status, useDemo } from './shared'

function OwnerHeader({ title, text, children }) {
  return <div className="page-head"><div><h1>{title}</h1><p>{text}</p></div>{children}</div>
}

function useOwner() {
  const context = useDemo()
  const { db, actor } = context
  const buildings = db.buildings.filter((item) => item.ownerId === actor.id)
  const units = db.apartments.filter((unit) => buildings.some((item) => item.id === unit.buildingId))
  const contracts = db.contracts.filter((item) => item.ownerId === actor.id)
  return { ...context, buildings, units, contracts }
}

const unitLabels = { ready: ['Còn trống', 'green'], occupied: ['Đã có hợp đồng', 'blue'], maintenance: ['Bảo trì', 'amber'] }
const UnitStatus = ({ value }) => <Status tone={unitLabels[value][1]}>{unitLabels[value][0]}</Status>

export function OwnerDashboard() {
  const { db, actor, buildings, units, contracts } = useOwner()
  const empty = units.filter((unit) => unitStatus(db, unit) === 'ready').length
  const occupied = units.filter((unit) => unitStatus(db, unit) === 'occupied').length
  const invoices = db.invoices.filter((bill) => contracts.some((item) => item.id === bill.contractId))
  const collected = invoices.filter((bill) => bill.status === 'paid' && bill.paidAt?.slice(0, 7) === dateAt().slice(0, 7)).reduce((sum, bill) => sum + invoiceTotal(bill), 0)
  const due = invoices.filter((bill) => bill.status === 'unpaid').reduce((sum, bill) => sum + invoiceTotal(bill), 0)
  const expiring = contracts.filter((item) => renewalOpen(item) && !expired(item))
  return <><OwnerHeader title="Tổng quan cho thuê" text={`Xin chào, ${actor.name}. Bạn đang quản lý ${buildings.length} tòa nhà.`} />
    <div className="metrics">{[[DoorOpen, 'Căn hộ còn trống', empty], [Building2, 'Tỷ lệ lấp đầy', `${units.length ? Math.round(100 * occupied / units.length) : 0}%`], [CircleDollarSign, 'Đã thu trong tháng', money(collected)], [Receipt, 'Hóa đơn chưa thu', money(due)]].map(([Icon, label, value]) => <article className="panel metric" key={label}><div className="step-icon"><Icon size={23} /></div><p>{label}</p><strong>{value}</strong></article>)}</div>
    <section className="dashboard-section"><div className="section-title"><h2>Hợp đồng sắp hết hạn</h2><Link className="text-link" to="/owner/khach-thue">Quản lý khách thuê <ArrowRight size={17} /></Link></div>
      {expiring.length ? <ContractTable contracts={expiring} /> : <Empty title="Không có hợp đồng hết hạn trong 30 ngày tới" />}</section>
  </>
}

export function OwnerProperties() {
  const { db, actor, buildings, units, save } = useOwner()
  const [filter, setFilter] = useState('')
  const [editor, setEditor] = useState(null)
  const [error, setError] = useState('')
  const open = (value) => { setError(''); setEditor(value) }
  function submit(event) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const text = (key) => String(data.get(key) || '').trim()
    try {
      if (editor.type === 'building') {
        const name = text('name'), address = text('address'), district = text('district')
        if (!name || !address || !district) throw new Error('Điền tên, khu vực và địa chỉ tòa nhà.')
        const property = { id: editor.item?.id ?? crypto.randomUUID(), ownerId: actor.id, name, address, district,
          location: text('location') || `Sảnh lễ tân ${name}, ${address}`, electricRate: Number(data.get('electricRate')), waterRate: Number(data.get('waterRate')) }
        save({ ...db, buildings: editor.item ? db.buildings.map((item) => item.id === property.id ? property : item) : [...db.buildings, property] }, 'Đã lưu tòa nhà.')
      } else {
        const buildingId = text('buildingId'), room = text('room'), title = text('title')
        if (!room || !title || !buildings.some((item) => item.id === buildingId)) throw new Error('Điền mã phòng, tên và tòa nhà.')
        if (units.some((item) => item.id !== editor.item?.id && item.buildingId === buildingId && item.room.toLowerCase() === room.toLowerCase())) throw new Error('Mã căn hộ đã tồn tại trong tòa nhà.')
        const unit = { image: 'greenhouse', published: false, description: '', ...editor.item, id: editor.item?.id ?? crypto.randomUUID(), buildingId, room, title,
          price: Number(data.get('price')), area: Number(data.get('area')), beds: Number(data.get('beds')), baths: Number(data.get('baths')), condition: text('condition') }
        if (unit.condition === 'maintenance' && db.contracts.some((item) => item.apartmentId === unit.id && item.status === 'signed' && !expired(item))) throw new Error('Căn hộ có hợp đồng còn hiệu lực.')
        save({ ...db, apartments: editor.item ? db.apartments.map((item) => item.id === unit.id ? unit : item) : [...db.apartments, unit] }, 'Đã lưu căn hộ.')
      }
      setEditor(null)
    } catch (problem) { setError(problem.message) }
  }
  return <><OwnerHeader title="Tòa nhà & căn hộ" text="Quản lý địa chỉ ký, đơn giá điện nước và tình trạng căn hộ."><div className="actions"><button className="button outline" onClick={() => open({ type: 'building' })}><Plus size={18} /> Thêm tòa nhà</button><button className="button primary" disabled={!buildings.length} onClick={() => open({ type: 'unit' })}><Plus size={18} /> Thêm căn hộ</button></div></OwnerHeader>
    <div className="property-grid">{buildings.map((item) => <article className="panel property" key={item.id}><div className="step-icon"><Building2 size={23} /></div><div><h2>{item.name}</h2><p>{item.address}</p><p className="subtle">Điện {money(item.electricRate)}/kWh · Nước {money(item.waterRate)}/m³</p></div><button className="button outline small" onClick={() => open({ type: 'building', item })}>Chỉnh sửa</button></article>)}</div>
    <div className="section-title"><h2>Danh sách căn hộ</h2><Select aria-label="Lọc theo tòa nhà" value={filter} onChange={(event) => setFilter(event.target.value)}><option value="">Tất cả tòa nhà</option>{buildings.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</Select></div>
    {units.length ? <div className="table-wrap"><table><thead><tr><th>Căn hộ</th><th>Diện tích</th><th>Giá thuê/tháng</th><th>Tình trạng</th><th></th></tr></thead><tbody>
      {units.filter((unit) => !filter || unit.buildingId === filter).map((unit) => <tr key={unit.id}><td><b>{unit.title}</b><small>{unit.room}</small></td><td>{unit.area} m²</td><td>{money(unit.price)}</td><td><UnitStatus value={unitStatus(db, unit)} /></td><td><button className="button small outline" onClick={() => open({ type: 'unit', item: unit })}><Wrench size={15} /> Chỉnh sửa</button></td></tr>)}
    </tbody></table></div> : <Empty title="Chưa có căn hộ" />}
    {editor && <Modal title={`${editor.item ? 'Sửa' : 'Thêm'} ${editor.type === 'building' ? 'tòa nhà' : 'căn hộ'}`} close={() => setEditor(null)}>
      <form className="stack" onSubmit={submit}>{editor.type === 'building' ? <>
        <Field label="Tên tòa nhà"><input name="name" defaultValue={editor.item?.name} required /></Field>
        <div className="form-grid"><Field label="Khu vực"><input name="district" defaultValue={editor.item?.district} required /></Field><Field label="Địa chỉ"><input name="address" defaultValue={editor.item?.address} required /></Field></div>
        <Field label="Địa điểm ký (mặc định là sảnh lễ tân)"><input name="location" defaultValue={editor.item?.location} /></Field>
        <div className="form-grid"><Field label="Đơn giá điện (₫/kWh)"><input name="electricRate" type="number" min="0" defaultValue={editor.item?.electricRate ?? 3500} required /></Field><Field label="Đơn giá nước (₫/m³)"><input name="waterRate" type="number" min="0" defaultValue={editor.item?.waterRate ?? 20000} required /></Field></div>
      </> : <>
        <Field label="Tòa nhà"><Select name="buildingId" defaultValue={editor.item?.buildingId} required aria-disabled={Boolean(editor.item)}>{(editor.item ? buildings.filter((item) => item.id === editor.item.buildingId) : buildings).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></Field>
        <div className="form-grid"><Field label="Mã căn hộ"><input name="room" defaultValue={editor.item?.room} required /></Field><Field label="Tên căn hộ"><input name="title" defaultValue={editor.item?.title} required /></Field></div>
        <div className="form-grid"><Field label="Tiền thuê/tháng (₫)"><input name="price" type="number" min="1" defaultValue={editor.item?.price} required /></Field><Field label="Diện tích (m²)"><input name="area" type="number" min="1" step="0.1" defaultValue={editor.item?.area} required /></Field></div>
        <div className="form-grid"><Field label="Số phòng ngủ"><input name="beds" type="number" min="0" defaultValue={editor.item?.beds ?? 1} required /></Field><Field label="Số phòng tắm"><input name="baths" type="number" min="1" defaultValue={editor.item?.baths ?? 1} required /></Field></div>
        <Field label="Tình trạng"><Select name="condition" defaultValue={editor.item?.condition ?? 'ready'}><option value="ready">Sẵn sàng</option><option value="maintenance">Bảo trì</option></Select></Field>
      </>}{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu thông tin</button></form>
    </Modal>}
  </>
}

export function OwnerListings() {
  const { db, units, save } = useOwner()
  const [editing, setEditing] = useState(null)
  const [error, setError] = useState('')
  function update(unit, values, message) {
    save({ ...db, apartments: db.apartments.map((item) => item.id === unit.id ? { ...item, ...values } : item) }, message)
  }
  return <><OwnerHeader title="Tin đăng" text="Chỉnh sửa nội dung và quyết định khi nào mở lại tin cho thuê." />
    {units.length ? <div className="table-wrap"><table><thead><tr><th>Tin căn hộ</th><th>Tình trạng căn hộ</th><th>Hiển thị</th><th>Thao tác</th></tr></thead><tbody>
      {units.map((unit) => {
        const state = unitStatus(db, unit)
        return <tr key={unit.id}><td><div className="unit-cell"><img src={unitImage(unit)} alt="" /><div><b>{unit.title}</b><small>{unit.room}</small></div></div></td><td><UnitStatus value={state} /></td><td><Status tone={unit.published && state === 'ready' ? 'green' : 'neutral'}>{unit.published && state === 'ready' ? 'Đang hiển thị' : 'Đã ẩn'}</Status></td><td><div className="actions"><button className="button small outline" onClick={() => { setEditing(unit); setError('') }}>Chỉnh sửa</button>{state === 'ready' && <button className={`button small ${unit.published ? 'outline' : 'primary'}`} onClick={() => update(unit, { published: !unit.published }, unit.published ? 'Đã ẩn tin.' : 'Đã mở tin cho thuê.')}>{unit.published ? 'Ẩn tin' : 'Đăng tin'}</button>}</div></td></tr>
      })}
    </tbody></table></div> : <Empty title="Chưa có căn hộ để đăng tin"><Link className="button primary" to="/owner/can-ho">Quản lý căn hộ</Link></Empty>}
    {editing && <Modal title="Chỉnh sửa tin đăng" close={() => setEditing(null)}><form className="stack" onSubmit={async (event) => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      const title = form.get('title').trim(), description = form.get('description').trim()
      if (!title || !description) { setError('Điền tiêu đề và mô tả căn hộ.'); return }
      const photo = form.get('photo')
      let customImage = form.get('removePhoto') ? '' : editing.customImage
      if (photo.size) {
        if (!['image/jpeg', 'image/png', 'image/webp'].includes(photo.type) || photo.size > 300000) {
          setError('Chọn ảnh JPG, PNG hoặc WebP có dung lượng tối đa 300 KB.'); return
        }
        try {
          customImage = await new Promise((resolve, reject) => {
            const reader = new FileReader()
            reader.onload = () => resolve(reader.result)
            reader.onerror = reject
            reader.readAsDataURL(photo)
          })
        } catch { setError('Không đọc được ảnh đã chọn.'); return }
      }
      update(editing, { title, description, image: form.get('image'), customImage }, 'Đã lưu tin đăng.')
      setEditing(null)
    }}><Field label="Tiêu đề"><input name="title" defaultValue={editing.title} required /></Field><Field label="Mô tả"><textarea name="description" defaultValue={editing.description} required rows="4" /></Field>
      <Field label="Ảnh minh họa"><Select name="image" defaultValue={editing.image}><option value="river">Phòng khách bên sông</option><option value="lumiere">Studio nội thất gỗ</option><option value="urban">Căn hộ nhìn ra thành phố</option><option value="greenhouse">Căn hộ có ban công</option></Select></Field>
      <Field label="Tải ảnh căn hộ (tối đa 300 KB)"><input type="file" name="photo" accept="image/jpeg,image/png,image/webp" /></Field>
      {editing.customImage && <label className="check-row"><input type="checkbox" name="removePhoto" /> Dùng lại ảnh minh họa</label>}{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu tin</button></form></Modal>}
  </>
}

function ContractTable({ contracts }) {
  return <div className="table-wrap"><table><thead><tr><th>Căn hộ / Mã hợp đồng</th><th>Khách thuê</th><th>Thời hạn</th><th>Ký kết</th><th>Thanh toán</th><th></th></tr></thead><tbody>
    {contracts.map((item) => <tr key={item.id}><td><b>{item.apartmentTitle}</b><small>{contractCode(item)}</small></td><td>{identity(item.tenantId).name}</td><td>{shortDate(item.startDate)} – {shortDate(item.endDate)}</td><td><ContractStatus contract={item} /></td><td><PaymentStatus paid={item.payment === 'paid'} /></td><td><Link className="button small outline" to={`/owner/hop-dong/${item.id}`}>Chi tiết</Link></td></tr>)}
  </tbody></table></div>
}

function CreateContract({ base, close }) {
  const { db, actor, units, save } = useOwner()
  const navigate = useNavigate()
  const candidates = units.filter((unit) => unit.condition !== 'maintenance' && (!base || unit.id === base.apartmentId))
  const [unitId, setUnitId] = useState(candidates[0]?.id ?? '')
  const [error, setError] = useState('')
  const unit = candidates.find((item) => item.id === unitId)
  const minStart = base && base.endDate >= dateAt() ? plusDays(base.endDate, 1) : dateAt()
  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    try {
      const result = addContract(db, actor, unit, { tenantId: base?.tenantId ?? form.get('tenantId'), startDate: form.get('startDate'), endDate: form.get('endDate'), rent: Number(form.get('rent')), renewalOf: base?.id })
      save(result.db, 'Đã tạo hợp đồng chờ ký.')
      close()
      navigate(`/owner/hop-dong/${result.contract.id}`)
    } catch (problem) { setError(problem.message) }
  }
  return <Modal title={base ? 'Lập hợp đồng gia hạn' : 'Tạo hợp đồng mới'} close={close}>
    {unit ? <form className="stack" onSubmit={submit}><Field label="Căn hộ"><Select value={unitId} onChange={(event) => setUnitId(event.target.value)}>{candidates.map((item) => <option key={item.id} value={item.id}>{item.title} · {item.room}</option>)}</Select></Field>
      <Field label="Khách thuê"><Select name="tenantId" defaultValue={base?.tenantId ?? ''} required><option value="" disabled>Chọn khách thuê</option>{people.filter((item) => item.role === 'tenant' && (!base || item.id === base.tenantId)).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></Field>
      <div className="form-grid"><Field label="Ngày bắt đầu"><input name="startDate" type="date" min={minStart} defaultValue={base ? minStart : dateAt(7)} required /></Field><Field label="Ngày kết thúc"><input name="endDate" type="date" min={minStart} defaultValue={plusDays(minStart, 179)} required /></Field></div>
      <Field label="Tiền thuê/tháng (₫)"><input type="number" name="rent" min="1" key={unit.id} defaultValue={base?.rent ?? unit.price} required /></Field>
      {base && <Notice>Hợp đồng kỳ mới có mã và thời hạn riêng, liên kết với {contractCode(base)}.</Notice>}{error && <Notice error>{error}</Notice>}<button className="button primary">Tạo hợp đồng chờ ký</button>
    </form> : <Empty title="Không có căn hộ sẵn sàng" />}
  </Modal>
}

export function OwnerContracts() {
  const { contracts } = useOwner()
  const [create, setCreate] = useState(false)
  return <><OwnerHeader title="Hợp đồng" text="Theo dõi hợp đồng riêng cho từng căn hộ và khách thuê."><button className="button primary" onClick={() => setCreate(true)}><Plus size={18} /> Tạo hợp đồng</button></OwnerHeader>
    {contracts.length ? <ContractTable contracts={contracts.toReversed()} /> : <Empty title="Chưa có hợp đồng" />}
    {create && <CreateContract close={() => setCreate(false)} />}
  </>
}

export function OwnerTenants() {
  const { db, contracts } = useOwner()
  const [base, setBase] = useState(null)
  const list = contracts.filter((item) => item.status === 'signed').toReversed()
  return <><OwnerHeader title="Khách thuê" text="Xem thời hạn thuê và phản hồi gia hạn của từng khách thuê." />
    {list.length ? <div className="table-wrap"><table><thead><tr><th>Khách thuê</th><th>Căn hộ</th><th>Hết hạn</th><th>Gia hạn</th><th>Thao tác</th></tr></thead><tbody>
      {list.map((item) => <tr key={item.id}><td><b>{identity(item.tenantId).name}</b><small>{contractCode(item)}</small></td><td>{item.apartmentTitle}</td><td>{shortDate(item.endDate)}</td><td><RenewalStatus contract={item} db={db} /></td><td><div className="actions"><Link className="button small outline" to={`/owner/hop-dong/${item.id}`}>Hợp đồng</Link>
        {item.renewal === 'requested' && !db.contracts.some((other) => other.renewalOf === item.id) && <button className="button small primary" onClick={() => setBase(item)}>Lập hợp đồng mới</button>}
        {expired(item) && item.renewal === 'declined' && <Link className="button small primary" to="/owner/tin-dang">Quản lý đăng lại</Link>}
      </div></td></tr>)}
    </tbody></table></div> : <Empty title="Chưa có khách thuê đã ký hợp đồng" />}
    {base && <CreateContract base={base} close={() => setBase(null)} />}
  </>
}

export function OwnerBilling() {
  const { db, actor, contracts, save } = useOwner()
  const [open, setOpen] = useState(false)
  const [id, setId] = useState('')
  const [month, setMonth] = useState('')
  const [error, setError] = useState('')
  const eligible = contracts.filter((item) => item.status === 'signed')
  const selected = eligible.find((item) => item.id === id)
  const previous = db.invoices.filter((item) => item.contractId === id && item.kind !== 'early-move-in' && item.month < month).sort((a, b) => b.month.localeCompare(a.month))[0]
  function choose(id) {
    const item = eligible.find((contract) => contract.id === id)
    const current = dateAt().slice(0, 7)
    setId(id)
    setMonth([item.startDate.slice(0, 7), current, item.endDate.slice(0, 7)].sort()[1])
  }
  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const values = Object.fromEntries(['oldElectric', 'newElectric', 'oldWater', 'newWater'].map((key) => [key, Number(form.get(key))]))
    try {
      save(billForMonth(db, actor, selected, month, values), 'Đã lập hóa đơn tháng.')
      setOpen(false)
    } catch (problem) { setError(problem.message) }
  }
  return <><OwnerHeader title="Hóa đơn" text="Chốt chỉ số điện nước, lập hóa đơn và xác nhận đã thu tiền."><button className="button primary" onClick={() => { setError(''); if (eligible.length) choose(eligible[0].id); setOpen(true) }}><Plus size={18} /> Lập hóa đơn</button></OwnerHeader>
    <Invoices role="owner" />
    {open && <Modal title="Lập hóa đơn theo tháng" close={() => setOpen(false)}>
      {selected ? <form className="stack" onSubmit={submit}>
        <Field label="Hợp đồng"><Select value={id} onChange={(event) => choose(event.target.value)}>{eligible.map((item) => <option key={item.id} value={item.id}>{item.apartmentTitle} · {contractCode(item)}</option>)}</Select></Field>
        <Field label="Tháng hóa đơn"><input type="month" value={month} min={selected.startDate.slice(0, 7)} max={selected.endDate.slice(0, 7)} onChange={(event) => setMonth(event.target.value)} required /></Field>
        <div className="stack" key={`${id}-${month}`}>
          <div className="form-grid"><Field label="Điện cũ (kWh)"><input name="oldElectric" type="number" min="0" defaultValue={previous?.newElectric ?? 0} required /></Field><Field label="Điện mới (kWh)"><input name="newElectric" type="number" min="0" required /></Field></div>
          <div className="form-grid"><Field label="Nước cũ (m³)"><input name="oldWater" type="number" min="0" defaultValue={previous?.newWater ?? 0} required /></Field><Field label="Nước mới (m³)"><input name="newWater" type="number" min="0" required /></Field></div>
        </div>
        <Notice><ClipboardList size={18} /> Tiền thuê theo hợp đồng {money(selected.rent)}/tháng.</Notice>{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu hóa đơn</button>
      </form> : <Empty title="Cần có hợp đồng đã ký để lập hóa đơn" />}
    </Modal>}
  </>
}
