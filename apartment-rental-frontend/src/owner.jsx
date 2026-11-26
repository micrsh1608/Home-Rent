import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AlertTriangle, ArrowRight, Building2, CalendarCheck, CircleDollarSign, ClipboardList, Clock, DoorOpen, FileText, Plus, Receipt, Search, Wrench } from 'lucide-react'
import {
  addContract,
  appointmentText,
  backendApi,
  billForMonth,
  calculateEndDateForTerm,
  calculateLeaseBreakdown,
  canCancelNoShow,
  contractCode,
  dateAt,
  expired,
  identity,
  invoiceTotal,
  money,
  moveInDeadline,
  nowInVietnam,
  people,
  plusDays,
  renewalOpen,
  runAppMutation,
  shortDate,
  unitImage,
  unitStatus,
} from './demo'
import { ContractStatus, Invoices, PaymentStatus, RenewalStatus } from './contracts'
import { Empty, Field, Modal, Notice, Select, Status, useDemo } from './shared'

function OwnerHeader({ title, text, children }) {
  return <div className="page-head"><div><h1>{title}</h1><p>{text}</p></div>{children}</div>
}

function useOwner() {
  const context = useDemo()
  const { db, actor } = context
  const buildings = db.buildings.filter((item) => item.ownerId === actor.id)
  const units = db.apartments.filter((unit) => unit.ownerId === actor.id || buildings.some((item) => item.id === unit.buildingId))
  const contracts = db.contracts.filter((item) => item.ownerId === actor.id)
  return { ...context, buildings, units, contracts }
}

const unitLabels = { ready: ['Còn trống', 'green'], occupied: ['Đã có hợp đồng', 'blue'], maintenance: ['Bảo trì', 'amber'] }
const UnitStatus = ({ value }) => <Status tone={unitLabels[value]?.[1] ?? 'neutral'}>{unitLabels[value]?.[0] ?? value}</Status>

export function OwnerDashboard() {
  const { db, actor, buildings, units, contracts } = useOwner()
  const dash = db.ownerDashboard
  const empty = dash ? dash.emptyUnits : units.filter((unit) => unitStatus(db, unit) === 'ready').length
  const occupied = dash ? dash.occupiedUnits : units.filter((unit) => unitStatus(db, unit) === 'occupied').length
  const occupancyPct = dash ? dash.occupancyRatePercent : (units.length ? Math.round(100 * occupied / units.length) : 0)
  const invoices = db.invoices.filter((bill) => contracts.some((item) => item.id === bill.contractId))
  const collected = dash
    ? dash.collectedThisMonth
    : invoices.filter((bill) => bill.status === 'paid' && bill.paidAt?.slice(0, 7) === dateAt().slice(0, 7)).reduce((sum, bill) => sum + invoiceTotal(bill), 0)
  const due = dash
    ? dash.unpaidTotal
    : invoices.filter((bill) => bill.status === 'unpaid').reduce((sum, bill) => sum + invoiceTotal(bill), 0)
  const expiring = dash?.expiringContracts
    ? dash.expiringContracts.map((c) => ({
        ...c,
        appointment: c.appointment ? String(c.appointment).slice(0, 16) : '',
        actualMoveIn: c.actualMoveIn ?? '',
        actualMoveOut: c.actualMoveOut ?? '',
      }))
    : contracts.filter((item) => renewalOpen(item) && !expired(item))

  return <><OwnerHeader title="Tổng quan cho thuê" text={`Xin chào, ${actor.name}. Bạn đang quản lý ${buildings.length} tòa nhà và ${units.length} căn hộ.`} />
    <div className="metrics">{[[DoorOpen, 'Căn hộ còn trống', empty], [Building2, 'Tỷ lệ lấp đầy', `${occupancyPct}%`], [CircleDollarSign, 'Đã thu trong tháng', money(collected)], [Receipt, 'Hóa đơn chưa thu', money(due)]].map(([Icon, label, value]) => <article className="panel metric" key={label}><div className="step-icon"><Icon size={23} /></div><p>{label}</p><strong>{value}</strong></article>)}</div>
    <section className="dashboard-section"><div className="section-title"><h2>Hợp đồng sắp hết hạn (30 ngày tới)</h2><Link className="text-link" to="/owner/hop-dong">Quản lý hợp đồng <ArrowRight size={17} /></Link></div>
      {expiring.length ? <ContractTable contracts={expiring} db={db} /> : <Empty title="Không có hợp đồng hết hạn trong 30 ngày tới" />}</section>
  </>
}

export function OwnerProperties() {
  const { db, actor, buildings, units, save, notify, isPrototypeMode, markOffline, refreshFromBackend } = useOwner()
  const [filter, setFilter] = useState('')
  const [editor, setEditor] = useState(null)
  const [error, setError] = useState('')
  const open = (value) => { setError(''); setEditor(value) }
  async function submit(event) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const text = (key) => String(data.get(key) || '').trim()
    try {
      if (editor.type === 'building') {
        const name = text('name'), address = text('address'), district = text('district')
        if (!name || !address || !district) throw new Error('Điền tên, khu vực và địa chỉ tòa nhà.')
        const property = {
          id: editor.item?.id ?? crypto.randomUUID(),
          ownerId: actor.id,
          name,
          address,
          district,
          location: text('location') || `Sảnh lễ tân ${name}, ${address}`,
          electricRate: Number(data.get('electricRate')),
          waterRate: Number(data.get('waterRate')),
        }
        await runAppMutation({
          prototypeMode: isPrototypeMode,
          apiCall: async () => {
            if (editor.item) await backendApi.updateBuilding(property.id, property)
            else await backendApi.createBuilding(property)
            await refreshFromBackend()
            notify('Đã lưu tòa nhà.')
          },
          prototypeCall: () => {
            save({ ...db, buildings: editor.item ? db.buildings.map((item) => item.id === property.id ? property : item) : [...db.buildings, property] }, 'Đã lưu tòa nhà.')
          },
        })
      } else {
        const buildingId = text('buildingId'), room = text('room'), title = text('title')
        if (!room || !title || !buildings.some((item) => item.id === buildingId)) throw new Error('Điền mã phòng, tên và tòa nhà.')
        const conditionVal = text('condition') || 'ready'
        const publishNow = editor.item ? editor.item.published : (conditionVal === 'ready' && Boolean(data.get('published')))
        const unit = {
          image: text('image') || 'greenhouse',
          published: publishNow,
          description: text('description') || 'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.',
          ...editor.item,
          id: editor.item?.id ?? crypto.randomUUID(),
          buildingId,
          room,
          title,
          price: Number(data.get('price')),
          area: Number(data.get('area')),
          beds: Number(data.get('beds')),
          baths: Number(data.get('baths')),
          condition: conditionVal,
        }
        await runAppMutation({
          prototypeMode: isPrototypeMode,
          apiCall: async () => {
            if (editor.item) await backendApi.updateApartment(unit.id, unit)
            else await backendApi.createApartment(unit)
            await refreshFromBackend()
            notify(editor.item ? 'Đã lưu căn hộ.' : (publishNow ? 'Đã thêm căn hộ và mở tin đăng cho thuê.' : 'Đã lưu căn hộ.'))
          },
          prototypeCall: () => {
            if (units.some((item) => item.id !== editor.item?.id && item.buildingId === buildingId && item.room.toLowerCase() === room.toLowerCase())) throw new Error('Mã căn hộ đã tồn tại trong tòa nhà.')
            if (unit.condition === 'maintenance' && db.contracts.some((item) => item.apartmentId === unit.id && item.status === 'signed' && !expired(item))) throw new Error('Căn hộ có hợp đồng còn hiệu lực.')
            save({ ...db, apartments: editor.item ? db.apartments.map((item) => item.id === unit.id ? unit : item) : [...db.apartments, unit] }, 'Đã lưu căn hộ.')
          },
        })
      }
      setEditor(null)
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      setError(problem.message)
    }
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
        {!editor.item && (
          <label className="check-row">
            <input type="checkbox" name="published" defaultChecked />
            <span>Mở tin đăng cho thuê công khai ngay trên trang Khám phá</span>
          </label>
        )}
      </>}{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu thông tin</button></form>
    </Modal>}
  </>
}

export function OwnerListings() {
  const { db, buildings, units, save, notify, isPrototypeMode, markOffline, refreshFromBackend } = useOwner()
  const [editing, setEditing] = useState(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState('')
  async function update(unit, values, message, mode = 'listing') {
    try {
      await runAppMutation({
        prototypeMode: isPrototypeMode,
        apiCall: async () => {
          if (mode === 'publish') await backendApi.togglePublish(unit.id, values.published)
          else await backendApi.updateListing(unit.id, values)
          await refreshFromBackend()
          notify(message)
        },
        prototypeCall: () => {
          save({ ...db, apartments: db.apartments.map((item) => item.id === unit.id ? { ...item, ...values } : item) }, message)
        },
      })
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      throw problem
    }
  }

  async function handleCreateListing(event) {
    event.preventDefault()
    setError('')
    const form = new FormData(event.currentTarget)
    const text = (key) => String(form.get(key) || '').trim()
    const buildingId = text('buildingId')
    const room = text('room')
    const title = text('title')
    const description = text('description')
    if (!buildingId || !room || !title || !description) {
      setError('Vui lòng điền đầy đủ tòa nhà, mã căn hộ, tiêu đề và mô tả.')
      return
    }
    const photo = form.get('photo')
    let customImage = ''
    if (photo && photo.size) {
      if (!['image/jpeg', 'image/png', 'image/webp'].includes(photo.type) || photo.size > 300000) {
        setError('Chọn ảnh JPG, PNG hoặc WebP có dung lượng tối đa 300 KB.')
        return
      }
      try {
        customImage = await new Promise((resolve, reject) => {
          const reader = new FileReader()
          reader.onload = () => resolve(reader.result)
          reader.onerror = reject
          reader.readAsDataURL(photo)
        })
      } catch {
        setError('Không đọc được ảnh đã chọn.')
        return
      }
    }
    const published = Boolean(form.get('published'))
    const newUnit = {
      id: crypto.randomUUID(),
      buildingId,
      room,
      title,
      price: Number(form.get('price')),
      area: Number(form.get('area')),
      beds: Number(form.get('beds')),
      baths: Number(form.get('baths')),
      condition: 'ready',
      image: text('image') || 'river',
      customImage: customImage || null,
      description,
      published,
    }
    try {
      await runAppMutation({
        prototypeMode: isPrototypeMode,
        apiCall: async () => {
          await backendApi.createApartment(newUnit)
          await refreshFromBackend()
          notify(published ? 'Đã thêm căn hộ và đăng tin cho thuê công khai!' : 'Đã thêm căn hộ vào danh sách tin đăng (đang ẩn).')
        },
        prototypeCall: () => {
          if (units.some((item) => item.buildingId === buildingId && item.room.toLowerCase() === room.toLowerCase())) {
            throw new Error('Mã căn hộ đã tồn tại trong tòa nhà này.')
          }
          save({ ...db, apartments: [...db.apartments, newUnit] }, published ? 'Đã thêm căn hộ và đăng tin!' : 'Đã thêm căn hộ (đang ẩn).')
        },
      })
      setCreating(false)
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      setError(problem.message)
    }
  }

  return <><OwnerHeader title="Tin đăng" text="Thêm tin đăng căn hộ mới, chỉnh sửa nội dung và quyết định khi nào mở hoặc ẩn tin cho thuê.">
      <div className="actions">
        <button className="button primary" disabled={!buildings.length} onClick={() => { setError(''); setCreating(true) }}>
          <Plus size={18} /> Thêm tin đăng mới
        </button>
      </div>
    </OwnerHeader>
    {units.length ? <div className="table-wrap"><table><thead><tr><th>Tin căn hộ</th><th>Giá thuê & Diện tích</th><th>Tình trạng căn hộ</th><th>Hiển thị</th><th>Thao tác</th></tr></thead><tbody>
      {units.map((unit) => {
        const state = unitStatus(db, unit)
        return <tr key={unit.id}><td><div className="unit-cell"><img src={unitImage(unit)} alt="" /><div><b>{unit.title}</b><small>{unit.room} · {unit.propertyName}</small></div></div></td><td><b>{money(unit.price)}/tháng</b><small>{unit.area} m² · {unit.beds} PN · {unit.baths} WC</small></td><td><UnitStatus value={state} /></td><td><Status tone={unit.published && state === 'ready' ? 'green' : 'neutral'}>{unit.published && state === 'ready' ? 'Đang hiển thị' : 'Đã ẩn'}</Status></td><td><div className="actions"><button className="button small outline" onClick={() => { setEditing(unit); setError('') }}>Chỉnh sửa</button>{state === 'ready' && <button className={`button small ${unit.published ? 'outline' : 'primary'}`} onClick={() => update(unit, { published: !unit.published }, unit.published ? 'Đã ẩn tin.' : 'Đã mở tin cho thuê.', 'publish').catch((e) => notify(e.message))}>{unit.published ? 'Ẩn tin' : 'Đăng tin'}</button>}</div></td></tr>
      })}
    </tbody></table></div> : <Empty title="Chưa có căn hộ để đăng tin"><button className="button primary" onClick={() => { setError(''); setCreating(true) }}><Plus size={18} /> Thêm tin đăng mới</button></Empty>}
    {creating && (
      <Modal title="Thêm tin đăng cho thuê căn hộ mới" close={() => setCreating(false)}>
        <form className="stack" onSubmit={handleCreateListing}>
          <Field label="Tòa nhà">
            <Select name="buildingId" defaultValue={buildings[0]?.id} required>
              {buildings.map((item) => <option key={item.id} value={item.id}>{item.name} ({item.district})</option>)}
            </Select>
          </Field>
          <div className="form-grid">
            <Field label="Mã căn hộ"><input name="room" placeholder="Ví dụ: A-15.08" required /></Field>
            <Field label="Tiêu đề tin đăng"><input name="title" placeholder="Ví dụ: Căn hộ The River View Sông" required /></Field>
          </div>
          <div className="form-grid">
            <Field label="Tiền thuê/tháng (₫)"><input name="price" type="number" min="1" defaultValue={12000000} required /></Field>
            <Field label="Diện tích (m²)"><input name="area" type="number" min="1" step="0.1" defaultValue={60} required /></Field>
          </div>
          <div className="form-grid">
            <Field label="Số phòng ngủ"><input name="beds" type="number" min="0" defaultValue={2} required /></Field>
            <Field label="Số phòng tắm"><input name="baths" type="number" min="1" defaultValue={2} required /></Field>
          </div>
          <Field label="Mô tả chi tiết">
            <textarea
              name="description"
              rows="3"
              defaultValue="Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn."
              required
            />
          </Field>
          <div className="form-grid">
            <Field label="Ảnh minh họa">
              <Select name="image" defaultValue="river">
                <option value="river">Phòng khách bên sông</option>
                <option value="lumiere">Studio nội thất gỗ</option>
                <option value="urban">Căn hộ nhìn ra thành phố</option>
                <option value="greenhouse">Căn hộ có ban công</option>
              </Select>
            </Field>
            <Field label="Hoặc tải ảnh thực tế (<= 300 KB)">
              <input type="file" name="photo" accept="image/jpeg,image/png,image/webp" />
            </Field>
          </div>
          <label className="check-row">
            <input type="checkbox" name="published" defaultChecked />
            <span><b>Đăng hiển thị công khai ngay</b> trên trang Khám phá để khách hàng tìm và yêu cầu thuê</span>
          </label>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary">Tạo căn hộ & Đăng tin</button>
        </form>
      </Modal>
    )}
    {editing && <Modal title="Chỉnh sửa tin đăng" close={() => setEditing(null)}><form className="stack" onSubmit={async (event) => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      const title = form.get('title').trim(), description = form.get('description').trim()
      if (!title || !description) { setError('Điền tiêu đề và mô tả căn hộ.'); return }
      const photo = form.get('photo')
      const removePhoto = Boolean(form.get('removePhoto'))
      let customImage = removePhoto ? '' : editing.customImage
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
      try {
        await update(editing, { title, description, image: form.get('image'), customImage, removePhoto }, 'Đã lưu tin đăng.', 'listing')
        setEditing(null)
      } catch (problem) {
        setError(problem.message)
      }
    }}><Field label="Tiêu đề"><input name="title" defaultValue={editing.title} required /></Field><Field label="Mô tả"><textarea name="description" defaultValue={editing.description} required rows="4" /></Field>
      <Field label="Ảnh minh họa"><Select name="image" defaultValue={editing.image}><option value="river">Phòng khách bên sông</option><option value="lumiere">Studio nội thất gỗ</option><option value="urban">Căn hộ nhìn ra thành phố</option><option value="greenhouse">Căn hộ có ban công</option></Select></Field>
      <Field label="Tải ảnh căn hộ (tối đa 300 KB)"><input type="file" name="photo" accept="image/jpeg,image/png,image/webp" /></Field>
      {editing.customImage && <label className="check-row"><input type="checkbox" name="removePhoto" /> Dùng lại ảnh minh họa</label>}{error && <Notice error>{error}</Notice>}<button className="button primary">Lưu tin</button></form></Modal>}
  </>
}

function ContractTable({ contracts, db, onRenew }) {
  const accountsList = db?.accounts || people
  return <div className="table-wrap"><table><thead><tr><th>Căn hộ / Mã hợp đồng</th><th>Khách thuê</th><th>Thời hạn & Tiền thuê</th><th>Lịch ký / Bàn giao</th><th>Ký kết</th><th>Thanh toán cọc</th><th>Gia hạn</th><th>Thao tác</th></tr></thead><tbody>
    {contracts.map((item) => {
      const tenantName = accountsList.find((p) => p.id === item.tenantId)?.name ?? identity(item.tenantId)?.name ?? item.tenantName ?? 'Khách thuê'
      const canCreateRenewal = onRenew && item.status === 'signed' && item.renewal === 'requested' && !db.contracts.some((other) => other.renewalOf === item.id && other.status !== 'cancelled')
      return <tr key={item.id}>
        <td>
          <b>{item.apartmentTitle}</b>
          <small>{contractCode(item)} · Phòng {item.room}</small>
          {item.status === 'awaiting' && item.appointment && <small>Đã có lịch hẹn — chờ xác nhận ký & cọc</small>}
          {canCancelNoShow(item) && <small style={{ color: '#8a362a', fontWeight: 600 }}>Quá hạn nhận phòng 3 ngày</small>}
        </td>
        <td>
          <b>{tenantName}</b>
          <small>{item.propertyName}</small>
        </td>
        <td>
          <b>{shortDate(item.startDate)} – {shortDate(item.endDate)}</b>
          <small>{money(item.rent)}/tháng</small>
        </td>
        <td>
          {item.status === 'awaiting' ? (
            <>
              <b>{appointmentText(item.appointment)}</b>
              <small>Lịch hẹn ký</small>
            </>
          ) : item.status === 'signed' ? (
            <>
              <b>{item.actualMoveIn ? `Đã nhận: ${shortDate(item.actualMoveIn)}` : 'Chờ nhận phòng'}</b>
              <small>{item.actualMoveOut ? `Đã trả: ${shortDate(item.actualMoveOut)}` : `Hạn chót: ${shortDate(moveInDeadline(item))}`}</small>
            </>
          ) : (
            <span className="subtle">Đã hủy</span>
          )}
        </td>
        <td><ContractStatus contract={item} /></td>
        <td><PaymentStatus paid={item.payment === 'paid'} forfeited={item.depositForfeited} /></td>
        <td>{db ? <RenewalStatus contract={item} db={db} /> : '—'}</td>
        <td>
          <div className="actions">
            <Link className="button small outline" to={`/owner/hop-dong/${item.id}`}>Chi tiết</Link>
            {canCreateRenewal && (
              <button type="button" className="button small primary" onClick={() => onRenew(item)}>
                Lập HĐ mới
              </button>
            )}
          </div>
        </td>
      </tr>
    })}
  </tbody></table></div>
}

const leaseTermOptions = [
  ['1M', '1 tháng (30/31 ngày; tháng 2 tính 30 ngày)', 'MONTH', 1],
  ['6M', '6 tháng (theo chuẩn chu kỳ tháng)', 'MONTH', 6],
  ['1Y', '1 năm (12 tháng)', 'YEAR', 1],
  ['CUSTOM', 'Tùy chọn thời gian thuê', 'CUSTOM', 1],
]

function getDefaultStartForUnit(db, unit, base = null) {
  const today = dateAt()
  if (base) {
    return base.endDate >= today ? plusDays(base.endDate, 1) : today
  }
  if (!unit) return today
  const activeSigned = db.contracts
    .filter((c) => c.apartmentId === unit.id && c.status === 'signed' && c.endDate >= today)
    .sort((a, b) => b.endDate.localeCompare(a.endDate))[0]
  if (activeSigned) {
    return plusDays(activeSigned.endDate, 1)
  }
  return today
}

function CreateContract({ base, close }) {
  const { db, actor, units, save, notify, isPrototypeMode, markOffline, refreshFromBackend } = useOwner()
  const navigate = useNavigate()
  const rawCandidates = units.filter((unit) => unit.condition !== 'maintenance' && (!base || unit.id === base.apartmentId))
  const candidates = [...rawCandidates].sort((a, b) => {
    const aReady = unitStatus(db, a) === 'ready' ? 0 : 1
    const bReady = unitStatus(db, b) === 'ready' ? 0 : 1
    return aReady - bReady
  })
  const [unitId, setUnitId] = useState(candidates[0]?.id ?? '')
  const unit = candidates.find((item) => item.id === unitId)
  const minStart = base && base.endDate >= dateAt() ? plusDays(base.endDate, 1) : dateAt()
  const initialStart = getDefaultStartForUnit(db, unit, base)
  const [termKey, setTermKey] = useState('6M')
  const [startDate, setStartDate] = useState(initialStart)
  const [endDate, setEndDate] = useState(() => calculateEndDateForTerm(initialStart, 'MONTH', 6))
  const [rent, setRent] = useState(base?.rent ?? unit?.price ?? 0)
  const [appointment, setAppointment] = useState(() => `${dateAt(1)}T09:30`)
  const [error, setError] = useState('')
  const accountsList = (db.accounts || people).filter((item) => item.role === 'tenant' && (!base || item.id === base.tenantId))

  function handleUnitChange(nextUnitId) {
    setUnitId(nextUnitId)
    const nextUnit = candidates.find((item) => item.id === nextUnitId)
    if (nextUnit && !base) {
      setRent(nextUnit.price)
      const nextStart = getDefaultStartForUnit(db, nextUnit, null)
      setStartDate(nextStart)
      const found = leaseTermOptions.find(([key]) => key === termKey)
      if (found && found[2] !== 'CUSTOM') {
        setEndDate(calculateEndDateForTerm(nextStart, found[2], found[3]))
      }
    }
  }

  function handleTermChange(nextKey) {
    setTermKey(nextKey)
    const found = leaseTermOptions.find(([key]) => key === nextKey)
    if (found && found[2] !== 'CUSTOM' && startDate) {
      setEndDate(calculateEndDateForTerm(startDate, found[2], found[3]))
    }
  }

  function handleStartChange(nextStart) {
    setStartDate(nextStart)
    const found = leaseTermOptions.find(([key]) => key === termKey)
    if (found && found[2] !== 'CUSTOM' && nextStart) {
      setEndDate(calculateEndDateForTerm(nextStart, found[2], found[3]))
    }
  }

  const activeTerm = leaseTermOptions.find(([key]) => key === termKey) ?? leaseTermOptions[1]
  const breakdown = calculateLeaseBreakdown(startDate, endDate, Number(rent))
  const firstPeriod = breakdown.periods[0]
  const lastPeriod = breakdown.periods.at(-1)

  async function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    try {
      const tenantId = base?.tenantId ?? form.get('tenantId')
      const createdId = await runAppMutation({
        prototypeMode: isPrototypeMode,
        apiCall: async () => {
          const created = await backendApi.createContract({
            apartmentId: unit.id,
            tenantId,
            startDate,
            endDate,
            rent: Number(rent),
            renewalOf: base?.id ?? null,
            leaseTermUnit: activeTerm[2],
            leaseTermValue: activeTerm[3],
            appointment: appointment || null,
          })
          await refreshFromBackend()
          notify('Đã tạo hợp đồng chờ ký.')
          return created.id
        },
        prototypeCall: () => {
          const result = addContract(db, actor, unit, {
            tenantId,
            startDate,
            endDate,
            rent: Number(rent),
            renewalOf: base?.id,
            leaseTermUnit: activeTerm[2],
            leaseTermValue: activeTerm[3],
          })
          save(result.db, 'Đã tạo hợp đồng chờ ký.')
          return result.contract.id
        },
      })
      close()
      navigate(`/owner/hop-dong/${createdId}`)
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      setError(problem.message)
    }
  }
  return <Modal title={base ? 'Lập hợp đồng gia hạn' : 'Tạo hợp đồng mới'} close={close}>
    {unit ? <form className="stack" onSubmit={submit}>
      <Field label="Căn hộ">
        <Select value={unitId} onChange={(event) => handleUnitChange(event.target.value)}>
          {candidates.map((item) => {
            const st = unitStatus(db, item)
            const activeContract = db.contracts.find((c) => c.apartmentId === item.id && c.status === 'signed' && c.endDate >= dateAt())
            const statusSuffix = st === 'ready'
              ? `Còn trống · ${money(item.price)}/tháng`
              : `Đang có HĐ đến ${shortDate(activeContract?.endDate)}`
            return <option key={item.id} value={item.id}>{item.title} · {item.room} ({statusSuffix})</option>
          })}
        </Select>
      </Field>
      <Field label="Khách thuê">
        <Select name="tenantId" defaultValue={base?.tenantId ?? (accountsList[0]?.id ?? '')} required>
          <option value="" disabled>Chọn khách thuê</option>
          {accountsList.map((item) => <option key={item.id} value={item.id}>{item.name} ({item.email})</option>)}
        </Select>
      </Field>
      <Field label="Hình thức thời hạn thuê"><Select value={termKey} onChange={(event) => handleTermChange(event.target.value)}>{leaseTermOptions.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></Field>
      <div className="form-grid">
        <Field label="Ngày bắt đầu (dọn vào)"><input name="startDate" type="date" min={minStart} value={startDate} onChange={(event) => handleStartChange(event.target.value)} required /></Field>
        <Field label="Ngày kết thúc"><input name="endDate" type="date" min={startDate || minStart} value={endDate} onChange={(event) => { setTermKey('CUSTOM'); setEndDate(event.target.value) }} required /></Field>
      </div>
      <div className="form-grid">
        <Field label="Tiền thuê/tháng (₫)"><input type="number" name="rent" min="1" value={rent} onChange={(event) => setRent(Number(event.target.value))} required /></Field>
        <Field label="Lịch hẹn ký (giờ Việt Nam — tùy chọn)"><input type="datetime-local" min={nowInVietnam()} value={appointment} onChange={(event) => setAppointment(event.target.value)} /></Field>
      </div>
      {breakdown.totalPeriods > 0 && <dl className="facts">
        <div><dt>Tiền đặt cọc (thu khi ký)</dt><dd>{money(Number(rent))}</dd></div>
        <div><dt>Tiền thuê kỳ đầu ({firstPeriod.daysInPeriod}/{firstPeriod.monthBasisDays} ngày)</dt><dd>{money(breakdown.firstPeriodRent)}</dd></div>
        <div><dt>Tiền thuê kỳ cuối ({lastPeriod.daysInPeriod}/{lastPeriod.monthBasisDays} ngày)</dt><dd>{money(breakdown.lastPeriodRent)}</dd></div>
        <div><dt>Hạn chót nhận phòng (3 ngày)</dt><dd>{shortDate(plusDays(startDate, 3))}</dd></div>
      </dl>}
      {base && <Notice>Hợp đồng kỳ mới có mã và thời hạn riêng, liên kết với {contractCode(base)}.</Notice>}
      {error && <Notice error>{error}</Notice>}
      <button className="button primary">Tạo hợp đồng chờ ký</button>
    </form> : <Empty title="Không có căn hộ sẵn sàng" />}
  </Modal>
}

export function OwnerContracts() {
  const { db, contracts: rawContracts, isPrototypeMode } = useOwner()
  const [create, setCreate] = useState(false)
  const [renewBase, setRenewBase] = useState(null)
  const [statusFilter, setStatusFilter] = useState('all')
  const [searchQuery, setSearchQuery] = useState('')

  const contracts = isPrototypeMode ? rawContracts.toReversed() : rawContracts
  const accountsList = db.accounts || people

  const awaitingCount = contracts.filter((c) => c.status === 'awaiting').length
  const activeCount = contracts.filter((c) => c.status === 'signed' && !expired(c)).length
  const renewalOrOverdueCount = contracts.filter(
    (c) => (c.status === 'signed' && renewalOpen(c)) || canCancelNoShow(c),
  ).length
  const cancelledCount = contracts.filter((c) => c.status === 'cancelled').length
  const expiredCount = contracts.filter((c) => expired(c)).length

  const filtered = contracts.filter((item) => {
    if (statusFilter === 'awaiting' && item.status !== 'awaiting') return false
    if (statusFilter === 'active' && !(item.status === 'signed' && !expired(item))) return false
    if (statusFilter === 'renewal' && !(item.status === 'signed' && renewalOpen(item))) return false
    if (statusFilter === 'expired' && !expired(item)) return false
    if (statusFilter === 'cancelled' && item.status !== 'cancelled') return false

    if (searchQuery.trim()) {
      const q = searchQuery.trim().toLowerCase()
      const tenantName = (accountsList.find((p) => p.id === item.tenantId)?.name ?? identity(item.tenantId)?.name ?? item.tenantName ?? '').toLowerCase()
      const haystack = `${contractCode(item)} ${item.apartmentTitle} ${item.room} ${item.propertyName} ${tenantName}`.toLowerCase()
      if (!haystack.includes(q)) return false
    }
    return true
  })

  return <>
    <OwnerHeader title="Quản lý Hợp đồng" text="Theo dõi vòng đời hợp đồng: lập hợp đồng, đặt lịch hẹn ký, xác nhận ký & thu cọc, bàn giao nhận/trả phòng, kiểm soát quá hạn 3 ngày và gia hạn.">
      <button className="button primary" onClick={() => setCreate(true)}><Plus size={18} /> Tạo hợp đồng mới</button>
    </OwnerHeader>

    <div className="metrics" style={{ marginBottom: 24 }}>
      {[
        [FileText, 'Tổng số hợp đồng', contracts.length],
        [Clock, 'Chờ ký kết', awaitingCount],
        [CalendarCheck, 'Đang hiệu lực', activeCount],
        [AlertTriangle, 'Sắp hết hạn / Cần xử lý', renewalOrOverdueCount],
      ].map(([Icon, label, value]) => (
        <article className="panel metric" key={label}>
          <div className="step-icon"><Icon size={23} /></div>
          <p>{label}</p>
          <strong>{value}</strong>
        </article>
      ))}
    </div>

    <div className="contract-toolbar" style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center', gap: 12, marginBottom: 18 }}>
      <div className="actions">
        {[
          ['all', `Tất cả (${contracts.length})`],
          ['awaiting', `Chờ ký (${awaitingCount})`],
          ['active', `Đang hiệu lực (${activeCount})`],
          ['renewal', `Gia hạn / 30 ngày (${contracts.filter((c) => c.status === 'signed' && renewalOpen(c)).length})`],
          ['expired', `Đã hết hạn (${expiredCount})`],
          ['cancelled', `Đã hủy (${cancelledCount})`],
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
      <div style={{ position: 'relative', minWidth: 260, flex: '0 1 320px' }}>
        <Search size={16} style={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', color: 'var(--muted)' }} />
        <input
          type="search"
          placeholder="Tìm mã HĐ, căn hộ, phòng, khách thuê..."
          value={searchQuery}
          onChange={(event) => setSearchQuery(event.target.value)}
          style={{ paddingLeft: 34 }}
        />
      </div>
    </div>

    {filtered.length ? (
      <ContractTable contracts={filtered} db={db} onRenew={(item) => setRenewBase(item)} />
    ) : (
      <Empty title={contracts.length ? 'Không tìm thấy hợp đồng phù hợp bộ lọc' : 'Chưa có hợp đồng'} />
    )}
    {create && <CreateContract close={() => setCreate(false)} />}
    {renewBase && <CreateContract base={renewBase} close={() => setRenewBase(null)} />}
  </>
}

export function OwnerTenants() {
  const { db, contracts: rawContracts, isPrototypeMode } = useOwner()
  const [base, setBase] = useState(null)
  const signedContracts = rawContracts.filter((item) => item.status === 'signed')
  const list = isPrototypeMode ? signedContracts.toReversed() : signedContracts
  const accountsList = db.accounts || people
  return <><OwnerHeader title="Khách thuê" text="Xem thời hạn thuê và phản hồi gia hạn của từng khách thuê." />
    {list.length ? <div className="table-wrap"><table><thead><tr><th>Khách thuê</th><th>Căn hộ</th><th>Hết hạn</th><th>Gia hạn</th><th>Thao tác</th></tr></thead><tbody>
      {list.map((item) => <tr key={item.id}><td><b>{accountsList.find((p) => p.id === item.tenantId)?.name ?? identity(item.tenantId)?.name ?? item.tenantName ?? 'Khách thuê'}</b><small>{contractCode(item)}</small></td><td>{item.apartmentTitle}</td><td>{shortDate(item.endDate)}</td><td><RenewalStatus contract={item} db={db} /></td><td><div className="actions"><Link className="button small outline" to={`/owner/hop-dong/${item.id}`}>Hợp đồng</Link>
        {item.renewal === 'requested' && !db.contracts.some((other) => other.renewalOf === item.id && other.status !== 'cancelled') && <button className="button small primary" onClick={() => setBase(item)}>Lập hợp đồng mới</button>}
        {expired(item) && item.renewal === 'declined' && <Link className="button small primary" to="/owner/tin-dang">Quản lý đăng lại</Link>}
      </div></td></tr>)}
    </tbody></table></div> : <Empty title="Chưa có khách thuê đã ký hợp đồng" />}
    {base && <CreateContract base={base} close={() => setBase(null)} />}
  </>
}

export function OwnerBilling() {
  const { db, actor, contracts, save, notify, isPrototypeMode, markOffline, refreshFromBackend } = useOwner()
  const [open, setOpen] = useState(false)
  const [id, setId] = useState('')
  const [preview, setPreview] = useState(null)
  const [loadingPreview, setLoadingPreview] = useState(false)
  const [error, setError] = useState('')
  const eligible = contracts.filter((item) => item.status === 'signed')
  const selected = eligible.find((item) => item.id === id)

  useEffect(() => {
    let cancelled = false
    if (!open || !id) return undefined
    if (isPrototypeMode) {
      const contract = eligible.find((c) => c.id === id)
      if (!contract) return undefined
      const breakdown = calculateLeaseBreakdown(contract.startDate, contract.endDate, contract.rent)
      const existing = db.invoices.filter((inv) => inv.contractId === id && inv.kind !== 'early-move-in')
      const maxIdx = existing.reduce((max, inv) => Math.max(max, inv.periodIndex || 0), 0)
      const nextPeriod = breakdown.periods.find((p) => p.periodIndex === maxIdx + 1)
      const prevInv = existing.find((inv) => inv.periodIndex === maxIdx)
      if (!nextPeriod) {
        setPreview({ contractId: id, billable: false, blockedReason: 'Hợp đồng đã lập đủ hóa đơn cho tất cả các kỳ thuê.' })
      } else if (nextPeriod.periodIndex === 1 && (contract.handoverElectricReading == null || contract.handoverWaterReading == null)) {
        setPreview({ contractId: id, billable: false, blockedReason: 'Cần ghi nhận nhận phòng và chỉ số điện nước bàn giao trước khi lập hóa đơn kỳ 1.', ...nextPeriod })
      } else {
        setPreview({
          contractId: id,
          billable: true,
          ...nextPeriod,
          expectedOldElectric: nextPeriod.periodIndex === 1 ? contract.handoverElectricReading : prevInv?.newElectric ?? 0,
          expectedOldWater: nextPeriod.periodIndex === 1 ? contract.handoverWaterReading : prevInv?.newWater ?? 0,
          meterSource: nextPeriod.periodIndex === 1 ? 'handover' : 'previous_invoice',
        })
      }
      return undefined
    }

    setLoadingPreview(true)
    setError('')
    backendApi.getNextInvoicePreview(id)
      .then((data) => { if (!cancelled) setPreview(data) })
      .catch((problem) => {
        if (!cancelled) {
          if (problem.isNetworkError) markOffline()
          setPreview(null)
          setError(problem.message)
        }
      })
      .finally(() => { if (!cancelled) setLoadingPreview(false) })
    return () => { cancelled = true }
  }, [open, id, isPrototypeMode, db.invoices.length])

  function choose(contractId) {
    setId(contractId)
    setError('')
  }

  async function submit(event) {
    event.preventDefault()
    if (!selected || !preview?.billable) return
    const form = new FormData(event.currentTarget)
    const newElectric = Number(form.get('newElectric'))
    const newWater = Number(form.get('newWater'))
    try {
      await runAppMutation({
        prototypeMode: isPrototypeMode,
        apiCall: async () => {
          await backendApi.createInvoice(selected.id, {
            periodIndex: preview.periodIndex,
            month: preview.billingMonth,
            oldElectric: preview.expectedOldElectric,
            newElectric,
            oldWater: preview.expectedOldWater,
            newWater,
          })
          await refreshFromBackend()
          notify(`Đã lập hóa đơn kỳ ${preview.periodIndex} (${preview.billingMonth}).`)
        },
        prototypeCall: () => {
          const nextDb = billForMonth(db, actor, selected, preview.billingMonth, {
            oldElectric: preview.expectedOldElectric,
            newElectric,
            oldWater: preview.expectedOldWater,
            newWater,
          })
          save(nextDb, 'Đã lập hóa đơn tháng.')
        },
      })
      setOpen(false)
    } catch (problem) {
      if (problem.isNetworkError) markOffline()
      setError(problem.message)
    }
  }

  return <><OwnerHeader title="Hóa đơn" text="Chốt chỉ số điện nước tuần tự theo từng kỳ thuê, lập hóa đơn và xác nhận đã thu tiền."><button className="button primary" onClick={() => { setError(''); if (eligible.length) choose(eligible[0].id); setOpen(true) }}><Plus size={18} /> Lập hóa đơn</button></OwnerHeader>
    <Invoices role="owner" />
    {open && <Modal title="Lập hóa đơn theo kỳ thuê" close={() => setOpen(false)}>
      {selected ? <form className="stack" onSubmit={submit}>
        <Field label="Hợp đồng"><Select value={id} onChange={(event) => choose(event.target.value)}>{eligible.map((item) => <option key={item.id} value={item.id}>{item.apartmentTitle} · {contractCode(item)}</option>)}</Select></Field>
        {loadingPreview ? (
          <Notice>Đang xác định kỳ hóa đơn tiếp theo và chỉ số điện nước từ máy chủ…</Notice>
        ) : preview ? (
          <>
            {preview.periodIndex && (
              <dl className="facts">
                <div><dt>Kỳ hóa đơn tiếp theo</dt><dd><b>Kỳ {preview.periodIndex} ({preview.billingMonth})</b></dd></div>
                <div><dt>Khoảng thời gian</dt><dd>{shortDate(preview.periodStart)} – {shortDate(preview.periodEnd)} ({preview.daysInPeriod}/{preview.monthBasisDays} ngày)</dd></div>
                <div><dt>Tiền thuê kỳ này</dt><dd><strong>{money(preview.rentAmount)}</strong></dd></div>
              </dl>
            )}
            {preview.billable ? (
              <div className="stack" key={`${id}-${preview.periodIndex}`}>
                <Notice>
                  <ClipboardList size={18} /> Chỉ số cũ do máy chủ xác định từ <b>{preview.meterSource === 'handover' ? 'biên bản bàn giao nhận phòng' : `hóa đơn kỳ ${preview.periodIndex - 1}`}</b> để bảo đảm liên tục và không thể sửa lùi.
                </Notice>
                <div className="form-grid">
                  <Field label="Điện cũ (kWh — máy chủ xác định)">
                    <input type="number" value={preview.expectedOldElectric} readOnly />
                  </Field>
                  <Field label="Điện mới (kWh)">
                    <input name="newElectric" type="number" min={preview.expectedOldElectric} step="1" required />
                  </Field>
                </div>
                <div className="form-grid">
                  <Field label="Nước cũ (m³ — máy chủ xác định)">
                    <input type="number" value={preview.expectedOldWater} readOnly />
                  </Field>
                  <Field label="Nước mới (m³)">
                    <input name="newWater" type="number" min={preview.expectedOldWater} step="1" required />
                  </Field>
                </div>
              </div>
            ) : (
              <Notice error>{preview.blockedReason || 'Chưa thể lập hóa đơn cho hợp đồng này.'}</Notice>
            )}
          </>
        ) : null}
        {error && <Notice error>{error}</Notice>}
        <button className="button primary" disabled={loadingPreview || !preview?.billable}>Lưu hóa đơn</button>
      </form> : <Empty title="Cần có hợp đồng đã ký để lập hóa đơn" />}
    </Modal>}
  </>
}
