export const people = [
  { id: 't1', name: 'Nguyễn Minh Anh', role: 'tenant' },
  { id: 't2', name: 'Lê Hoàng Nam', role: 'tenant' },
  { id: 'o1', name: 'Trần Thu Hà', role: 'owner' },
  { id: 'o2', name: 'Phạm Gia Bảo', role: 'owner' },
]

export function dateAt(offset = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offset)
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Ho_Chi_Minh' }).format(date)
}

const building = (id, ownerId, name, district, address) => ({
  id, ownerId, name, district, address,
  location: `Sảnh lễ tân ${name}, ${address}`,
  electricRate: 3500, waterRate: 20000,
})

const buildings = [
  building('b1', 'o1', 'The River Thảo Điền', 'TP. Thủ Đức', '28 Nguyễn Văn Hưởng, TP. Hồ Chí Minh'),
  building('b2', 'o2', 'Lumière Residence', 'Quận 7', '88 Nguyễn Đức Cảnh, TP. Hồ Chí Minh'),
  building('b3', 'o1', 'Urban Nest', 'Bình Thạnh', '55 Nguyễn Hữu Cảnh, TP. Hồ Chí Minh'),
  building('b4', 'o2', 'The Greenhouse', 'Quận 3', '46 Võ Văn Tần, TP. Hồ Chí Minh'),
]

const apartment = (id, buildingId, room, title, price, area, beds, baths, image, published = true) => ({
  id, buildingId, room, title, price, area, beds, baths, image, published,
  condition: 'ready',
  description: 'Căn hộ đầy đủ nội thất, đón ánh sáng tự nhiên. Có bếp riêng, máy lạnh và khu vực giặt. Phù hợp cho khách thuê dài hạn.',
})

const apartments = [
  apartment('a1', 'b1', 'A-12.06', 'Căn hộ The River Thảo Điền', 12500000, 62, 2, 2, 'river'),
  apartment('a2', 'b2', 'B-08.02', 'Căn hộ Lumière Residence', 9800000, 45, 1, 1, 'lumiere'),
  apartment('a3', 'b3', 'C-18.03', 'Căn hộ Urban Nest', 15200000, 78, 3, 2, 'urban'),
  apartment('a4', 'b4', 'D-07.01', 'Căn hộ The Greenhouse', 7900000, 38, 1, 1, 'greenhouse'),
  apartment('a5', 'b1', 'A-05.02', 'Căn hộ River Garden', 11200000, 56, 2, 1, 'greenhouse', false),
  apartment('a6', 'b2', 'B-09.03', 'Căn hộ Lumière Park', 10400000, 52, 2, 1, 'urban', false),
]

export function makeContract(db, apartmentId, tenantId, startDate, endDate, rent, renewalOf = null) {
  const unit = db.apartments.find((item) => item.id === apartmentId)
  const property = db.buildings.find((item) => item.id === unit.buildingId)
  return {
    id: globalThis.crypto.randomUUID(), apartmentId, tenantId, ownerId: property.ownerId, renewalOf,
    apartmentTitle: unit.title, room: unit.room, propertyName: property.name,
    startDate, endDate, rent, deposit: rent, location: property.location,
    electricRate: property.electricRate, waterRate: property.waterRate,
    status: 'awaiting', payment: 'unpaid', appointment: '', signedAt: null,
    renewal: null, actualMoveIn: '', actualMoveOut: '',
  }
}

function seed() {
  const db = { buildings, apartments, contracts: [], invoices: [], favorites: {} }
  db.contracts = [
    { ...makeContract(db, 'a5', 't1', dateAt(-160), dateAt(8), 11200000), id: 'HD-1001', status: 'signed', payment: 'paid', signedAt: dateAt(-161) },
    { ...makeContract(db, 'a6', 't2', dateAt(-190), dateAt(-9), 10400000), id: 'HD-1002', status: 'signed', payment: 'paid', signedAt: dateAt(-192), renewal: 'declined' },
  ]
  db.invoices = [{
    id: 'HĐ-1001', contractId: 'HD-1001', month: dateAt(-31).slice(0, 7), status: 'unpaid',
    oldElectric: 661, newElectric: 785, oldWater: 37, newWater: 41,
    lines: [{ title: 'Tiền thuê', amount: 11200000 }, { title: 'Tiền điện (124 kWh)', amount: 434000 }, { title: 'Tiền nước (4 m³)', amount: 80000 }],
  }]
  return db
}

export const storageKey = 'an-cu-demo-v1'
export function loadDemo() {
  try {
    const saved = globalThis.localStorage?.getItem(storageKey)
    if (saved) return JSON.parse(saved)
  } catch {
    // A browser with storage disabled can still run the demo in memory.
  }
  return seed()
}

export const money = (amount) => `${new Intl.NumberFormat('vi-VN').format(amount)} ₫`
export const shortDate = (value) => value ? value.slice(0, 10).split('-').reverse().join('/') : '—'
export const appointmentText = (value) => value ? `${value.slice(11, 16)}, ${shortDate(value)}` : 'Chưa đặt lịch'
export const nowInVietnam = () => new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'short',
}).format(new Date()).replace(' ', 'T')
export const plusDays = (iso, days) => {
  const date = new Date(`${iso}T12:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}
export const identity = (id) => people.find((person) => person.id === id)
export const contractCode = (contract) => contract.id.startsWith('HD-') ? contract.id : `HD-${contract.id.slice(0, 8).toUpperCase()}`
export const expired = (contract, today = dateAt()) => contract.status === 'signed' && contract.endDate < today
export const renewalOpen = (contract, today = dateAt()) => contract.status === 'signed' && contract.endDate <= plusDays(today, 30)
export const overlaps = (a, b) => a.startDate <= b.endDate && b.startDate <= a.endDate

export function unitStatus(db, unit, today = dateAt()) {
  if (unit.condition === 'maintenance') return 'maintenance'
  if (db.contracts.some((c) => c.apartmentId === unit.id && c.status === 'signed' && c.endDate >= today)) return 'occupied'
  return 'ready'
}

export function updateContract(db, actor, id, action, payload = {}, today = dateAt()) {
  const contract = db.contracts.find((item) => item.id === id)
  const authorized = actor && contract && (actor.role === 'tenant'
    ? contract.tenantId === actor.id
    : actor.role === 'owner' && contract.ownerId === actor.id)
  if (!authorized) throw new Error('Bạn không có quyền thao tác với hợp đồng này.')

  let changes
  let invoices = db.invoices
  switch (action) {
    case 'schedule':
      if (actor.role !== 'tenant' || contract.status !== 'awaiting') throw new Error('Hợp đồng này không thể đổi lịch.')
      if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(payload.appointment) || payload.appointment <= (payload.now ?? nowInVietnam())) {
        throw new Error('Chọn ngày giờ ký trong tương lai.')
      }
      changes = { appointment: payload.appointment }
      break
    case 'sign':
      if (actor.role !== 'tenant' || contract.status !== 'awaiting' || !contract.appointment || !payload.accepted) {
        throw new Error('Cần đặt lịch và xác nhận đã đọc các điều khoản trước khi ký.')
      }
      if (contract.endDate < today) throw new Error('Thời hạn của hợp đồng này đã qua.')
      if (db.contracts.some((other) => other.id !== id && other.apartmentId === contract.apartmentId && other.status === 'signed' && overlaps(occupiedDates(other), contract))) {
        throw new Error('Căn hộ đã có hợp đồng đã ký trùng thời gian.')
      }
      if (db.apartments.find((unit) => unit.id === contract.apartmentId)?.condition === 'maintenance') throw new Error('Căn hộ đang bảo trì.')
      changes = { status: 'signed', payment: 'paid', signedAt: new Date().toISOString() }
      break
    case 'renewal':
      if (actor.role !== 'tenant' || !renewalOpen(contract, today) || contract.renewal || !['requested', 'declined'].includes(payload.choice)) {
        throw new Error('Hợp đồng này không thể cập nhật lựa chọn gia hạn.')
      }
      changes = { renewal: payload.choice }
      break
    case 'handover':
      if (actor.role !== 'owner' || contract.status !== 'signed') throw new Error('Chỉ ghi nhận bàn giao cho hợp đồng đã ký.')
      if ((payload.actualMoveIn && payload.actualMoveIn > today) || (payload.actualMoveOut && payload.actualMoveOut > today)) {
        throw new Error('Ngày bàn giao thực tế phải là hôm nay hoặc trước đó.')
      }
      if (payload.actualMoveIn && payload.actualMoveOut && payload.actualMoveOut < payload.actualMoveIn) {
        throw new Error('Ngày trả phòng phải từ ngày nhận phòng trở đi.')
      }
      if (payload.actualMoveIn && payload.actualMoveIn < contract.startDate && db.contracts.some((other) =>
        other.id !== id && other.apartmentId === contract.apartmentId && other.status === 'signed' &&
        overlaps(occupiedDates(other), { startDate: payload.actualMoveIn, endDate: plusDays(contract.startDate, -1) })
      )) throw new Error('Ngày nhận phòng sớm trùng với hợp đồng khác.')
      if (payload.actualMoveIn !== contract.actualMoveIn) {
        const penalty = invoices.find((bill) => bill.contractId === id && bill.kind === 'early-move-in')
        if (penalty?.status === 'paid') throw new Error('Hóa đơn phạt đã được xác nhận thu tiền.')
        invoices = invoices.filter((bill) => bill !== penalty)
        if (payload.actualMoveIn && payload.actualMoveIn < contract.startDate) {
          invoices.push({
            id: penalty?.id ?? globalThis.crypto.randomUUID(), contractId: id, kind: 'early-move-in',
            month: payload.actualMoveIn.slice(0, 7), status: 'unpaid',
            lines: [{ title: 'Phạt nhận phòng trước ngày thỏa thuận', amount: contract.rent }],
          })
        }
      }
      changes = { actualMoveIn: payload.actualMoveIn, actualMoveOut: payload.actualMoveOut }
      break
    default:
      throw new Error('Thao tác không hợp lệ.')
  }
  const apartments = action === 'sign'
    ? db.apartments.map((item) => item.id === contract.apartmentId ? { ...item, published: false } : item)
    : db.apartments
  return { ...db, apartments, invoices, contracts: db.contracts.map((item) => item.id === id ? { ...item, ...changes } : item) }
}

function occupiedDates(contract) {
  return contract.actualMoveIn && contract.actualMoveIn < contract.startDate
    ? { startDate: contract.actualMoveIn, endDate: contract.endDate }
    : contract
}

export function addContract(db, actor, unit, terms) {
  if (!actor || !unit || unit.condition === 'maintenance') throw new Error('Căn hộ không khả dụng để tạo hợp đồng.')
  const property = db.buildings.find((item) => item.id === unit.buildingId)
  const base = db.contracts.find((item) => item.id === terms.renewalOf)
  if (actor.role === 'owner' ? property.ownerId !== actor.id : actor.role !== 'tenant' || !unit.published || unitStatus(db, unit) !== 'ready') {
    throw new Error('Căn hộ không khả dụng để tạo hợp đồng.')
  }
  if (!terms.startDate || !terms.endDate || terms.startDate < dateAt() || terms.endDate < terms.startDate || !Number.isSafeInteger(terms.rent) || terms.rent <= 0) {
    throw new Error('Kiểm tra lại thời hạn và tiền thuê.')
  }
  const tenantId = actor.role === 'tenant' ? actor.id : terms.tenantId
  if (identity(tenantId)?.role !== 'tenant') throw new Error('Chọn khách thuê.')
  if (terms.renewalOf && (actor.role !== 'owner' || !base || base.apartmentId !== unit.id || base.tenantId !== tenantId ||
    base.renewal !== 'requested' || terms.startDate <= base.endDate || db.contracts.some((item) => item.renewalOf === base.id))) {
    throw new Error('Kiểm tra lại yêu cầu gia hạn và thời hạn của hợp đồng mới.')
  }
  const conflict = db.contracts.some((item) => item.apartmentId === unit.id && overlaps(occupiedDates(item), terms) &&
    (item.status === 'signed' || item.tenantId === tenantId))
  if (conflict) throw new Error('Đã có hợp đồng hoặc yêu cầu của khách thuê này trùng thời gian.')
  const contract = makeContract(db, unit.id, tenantId, terms.startDate, terms.endDate, terms.rent, terms.renewalOf)
  return { db: { ...db, contracts: [...db.contracts, contract] }, contract }
}

export function billForMonth(db, actor, contract, month, values) {
  if (!contract || actor.role !== 'owner' || contract.ownerId !== actor.id || contract.status !== 'signed') throw new Error('Chọn hợp đồng đã ký của bạn.')
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new Error('Chọn tháng lập hóa đơn.')
  if (month < contract.startDate.slice(0, 7) || month > contract.endDate.slice(0, 7)) throw new Error('Tháng lập hóa đơn nằm ngoài thời hạn hợp đồng.')
  if (db.invoices.some((item) => item.contractId === contract.id && item.month === month && item.kind !== 'early-move-in')) throw new Error('Hợp đồng đã có hóa đơn cho tháng này.')
  const { oldElectric, newElectric, oldWater, newWater } = values
  if (![oldElectric, newElectric, oldWater, newWater].every((value) => Number.isSafeInteger(value) && value >= 0) ||
    newElectric < oldElectric || newWater < oldWater) throw new Error('Chỉ số mới phải lớn hơn hoặc bằng chỉ số cũ.')
  const lines = [
    { title: 'Tiền thuê', amount: contract.rent },
    { title: `Tiền điện (${newElectric - oldElectric} kWh)`, amount: (newElectric - oldElectric) * contract.electricRate },
    { title: `Tiền nước (${newWater - oldWater} m³)`, amount: (newWater - oldWater) * contract.waterRate },
  ]
  return { ...db, invoices: [...db.invoices, { id: globalThis.crypto.randomUUID(), contractId: contract.id, month, ...values, lines, status: 'unpaid' }] }
}

export const invoiceTotal = (invoice) => invoice.lines.reduce((sum, line) => sum + line.amount, 0)
export const unitImage = (unit) => unit.customImage || `/images/${unit.image}.webp`
