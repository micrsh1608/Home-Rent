export const people = [
  { id: 't1', name: 'Nguyễn Minh Anh', role: 'tenant', email: 'minhanh@ancu.vn' },
  { id: 't2', name: 'Lê Hoàng Nam', role: 'tenant', email: 'hoangnam@ancu.vn' },
  { id: 'o1', name: 'Trần Thu Hà', role: 'owner', email: 'thuha@ancu.vn' },
  { id: 'o2', name: 'Phạm Gia Bảo', role: 'owner', email: 'giabao@ancu.vn' },
]

export function dateAt(offset = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offset)
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Ho_Chi_Minh' }).format(date)
}

export const plusDays = (iso, days) => {
  const date = new Date(`${iso}T12:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

export const daysBetweenInclusive = (startIso, endIso) => {
  const start = new Date(`${startIso}T12:00:00Z`)
  const end = new Date(`${endIso}T12:00:00Z`)
  return Math.round((end - start) / 86400000) + 1
}

/**
 * Quyết định 2:
 * 1 tháng là tùy theo tháng có 30 hay 31 ngày;
 * riêng đối với tháng 2 vẫn tính theo 30 ngày kể từ ngày bắt đầu trên hợp đồng.
 */
export function monthBasisDays(monthNumber) {
  const m = Number(monthNumber)
  if ([1, 3, 5, 7, 8, 10, 12].includes(m)) return 31
  if ([4, 6, 9, 11].includes(m)) return 30
  if (m === 2) return 30
  throw new Error(`Tháng không hợp lệ: ${monthNumber}`)
}

export function calculateEndDateForTerm(startDate, unit = 'MONTH', value = 1) {
  if (!startDate || Number(value) <= 0) throw new Error('Kiểm tra lại ngày bắt đầu và thời hạn thuê.')
  const normalizedUnit = String(unit || 'MONTH').toUpperCase()
  const totalMonths = normalizedUnit === 'YEAR' ? Number(value) * 12 : Number(value)
  let cursor = startDate
  for (let i = 0; i < totalMonths; i += 1) {
    const month = Number(cursor.slice(5, 7))
    cursor = plusDays(cursor, monthBasisDays(month))
  }
  return plusDays(cursor, -1)
}

export function calculateLeaseBreakdown(startDate, endDate, monthlyRent) {
  if (!startDate || !endDate || endDate < startDate || !Number.isFinite(monthlyRent) || monthlyRent <= 0) {
    return {
      startDate, endDate, monthlyRent: monthlyRent || 0,
      totalPeriods: 0, firstPeriodRent: 0, lastPeriodRent: 0, totalContractRent: 0, periods: [],
    }
  }
  const periods = []
  let cursor = startDate
  let index = 1
  while (cursor <= endDate) {
    const month = Number(cursor.slice(5, 7))
    const basisDays = monthBasisDays(month)
    const fullPeriodEnd = plusDays(cursor, basisDays - 1)
    const billingMonth = cursor.slice(0, 7)
    if (endDate >= fullPeriodEnd) {
      periods.push({
        periodIndex: index,
        billingMonth,
        periodStart: cursor,
        periodEnd: fullPeriodEnd,
        daysInPeriod: basisDays,
        monthBasisDays: basisDays,
        fullMonth: true,
        rentAmount: monthlyRent,
      })
      cursor = plusDays(fullPeriodEnd, 1)
    } else {
      const actualDays = daysBetweenInclusive(cursor, endDate)
      const proratedRent = Math.round((monthlyRent * actualDays) / basisDays)
      periods.push({
        periodIndex: index,
        billingMonth,
        periodStart: cursor,
        periodEnd: endDate,
        daysInPeriod: actualDays,
        monthBasisDays: basisDays,
        fullMonth: actualDays === basisDays,
        rentAmount: proratedRent,
      })
      break
    }
    index += 1
  }
  const firstPeriodRent = periods[0]?.rentAmount ?? 0
  const lastPeriodRent = periods.at(-1)?.rentAmount ?? 0
  const totalContractRent = periods.reduce((sum, p) => sum + p.rentAmount, 0)
  return {
    startDate,
    endDate,
    monthlyRent,
    totalPeriods: periods.length,
    firstPeriodRent,
    lastPeriodRent,
    totalContractRent,
    periods,
  }
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

export function makeContract(db, apartmentId, tenantId, startDate, endDate, rent, renewalOf = null, extra = {}) {
  const unit = db.apartments.find((item) => item.id === apartmentId)
  const property = db.buildings.find((item) => item.id === unit.buildingId)
  return {
    id: globalThis.crypto.randomUUID(), apartmentId, tenantId, ownerId: property.ownerId, renewalOf,
    apartmentTitle: unit.title, room: unit.room, propertyName: property.name,
    startDate, endDate, rent, deposit: rent, location: property.location,
    electricRate: property.electricRate, waterRate: property.waterRate,
    status: 'awaiting', payment: 'unpaid', appointment: '', signedAt: null,
    signedConfirmedBy: null, paymentConfirmedBy: null,
    moveInDeadline: plusDays(startDate, 3),
    depositForfeited: false, cancelledAt: null, cancellationReason: '',
    leaseTermUnit: extra.leaseTermUnit ?? 'CUSTOM',
    leaseTermValue: extra.leaseTermValue ?? 6,
    renewal: null, actualMoveIn: '', actualMoveOut: '',
  }
}

function seed() {
  const db = { buildings, apartments, contracts: [], invoices: [], favorites: {} }
  db.contracts = [
    {
      ...makeContract(db, 'a5', 't1', dateAt(-160), dateAt(8), 11200000),
      id: 'HD-1001', status: 'signed', payment: 'paid',
      signedAt: dateAt(-161), signedConfirmedBy: 'o1', paymentConfirmedBy: 'o1',
      actualMoveIn: dateAt(-160),
    },
    {
      ...makeContract(db, 'a6', 't2', dateAt(-190), dateAt(-9), 10400000),
      id: 'HD-1002', status: 'signed', payment: 'paid',
      signedAt: dateAt(-192), signedConfirmedBy: 'o2', paymentConfirmedBy: 'o2',
      actualMoveIn: dateAt(-190), renewal: 'declined',
    },
  ]
  db.invoices = [{
    id: 'HĐ-1001', contractId: 'HD-1001', month: dateAt(-31).slice(0, 7), status: 'unpaid',
    oldElectric: 661, newElectric: 785, oldWater: 37, newWater: 41,
    lines: [{ title: 'Tiền thuê', amount: 11200000 }, { title: 'Tiền điện (124 kWh)', amount: 434000 }, { title: 'Tiền nước (4 m³)', amount: 80000 }],
  }]
  return db
}

export const storageKey = 'an-cu-demo-v2'
export function loadDemo() {
  try {
    const saved = globalThis.localStorage?.getItem(storageKey)
    if (saved) return JSON.parse(saved)
  } catch {
    // A browser with storage disabled can still run the demo in memory.
  }
  return seed()
}

export const money = (amount) => `${new Intl.NumberFormat('vi-VN').format(amount ?? 0)} ₫`
export const shortDate = (value) => value ? String(value).slice(0, 10).split('-').reverse().join('/') : '—'
export const appointmentText = (value) => value ? `${String(value).slice(11, 16)}, ${shortDate(value)}` : 'Chưa đặt lịch'
export const nowInVietnam = () => new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'short',
}).format(new Date()).replace(' ', 'T')
export const identity = (id) => people.find((person) => person.id === id)
export const contractCode = (contract) => contract.code || (contract.id.startsWith('HD-') ? contract.id : `HD-${contract.id.slice(0, 8).toUpperCase()}`)
export const expired = (contract, today = dateAt()) => contract.status === 'signed' && contract.endDate < today
export const renewalOpen = (contract, today = dateAt()) => contract.status === 'signed' && contract.endDate <= plusDays(today, 30)
export const moveInDeadline = (contract) => contract.moveInDeadline || plusDays(contract.startDate, 3)
export const canCancelNoShow = (contract, today = dateAt()) =>
  contract.status === 'signed' && !contract.actualMoveIn && today > moveInDeadline(contract)
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
    case 'confirm-sign-and-deposit': {
      const confirmed = Boolean(payload.accepted || (payload.signedAtLocation && payload.depositReceived))
      if (contract.status !== 'awaiting' || !contract.appointment || !confirmed) {
        throw new Error('Người môi giới cần xác nhận khách đã ký tại địa điểm hẹn và đã thu khoản tiền đặt cọc trên hợp đồng.')
      }
      if (contract.endDate < today) throw new Error('Thời hạn của hợp đồng này đã qua.')
      if (db.contracts.some((other) => other.id !== id && other.apartmentId === contract.apartmentId && other.status === 'signed' && overlaps(occupiedDates(other), contract))) {
        throw new Error('Căn hộ đã có hợp đồng đã ký trùng thời gian.')
      }
      if (db.apartments.find((unit) => unit.id === contract.apartmentId)?.condition === 'maintenance') throw new Error('Căn hộ đang bảo trì.')
      const confirmerId = actor.role === 'owner' ? actor.id : contract.ownerId
      changes = {
        status: 'signed',
        payment: 'paid',
        signedAt: new Date().toISOString(),
        signedConfirmedBy: confirmerId,
        paymentConfirmedBy: confirmerId,
      }
      break
    }
    case 'cancel-no-show': {
      if (actor.role !== 'owner' || contract.status !== 'signed') {
        throw new Error('Chỉ người môi giới / chủ nhà mới được hủy hợp đồng đã ký quá hạn nhận phòng.')
      }
      if (contract.actualMoveIn) {
        throw new Error('Khách hàng đã nhận phòng thực tế, không thể hủy theo điều khoản quá hạn dọn vào.')
      }
      const deadline = moveInDeadline(contract)
      const checkDate = payload.today ?? today
      if (checkDate <= deadline) {
        throw new Error(`Chưa hết thời hạn 3 ngày kể từ ngày dọn vào quy định trên hợp đồng (${shortDate(deadline)}).`)
      }
      changes = {
        status: 'cancelled',
        depositForfeited: true,
        cancelledAt: new Date().toISOString(),
        cancellationReason: `Khách hàng không dọn vào trong 3 ngày kể từ ngày quy định (${shortDate(contract.startDate)}); hợp đồng bị hủy và mất tiền đặt cọc.`,
      }
      break
    }
    case 'renewal':
      if (actor.role !== 'tenant' || !renewalOpen(contract, today) || contract.renewal || !['requested', 'declined'].includes(payload.choice)) {
        throw new Error('Hợp đồng này không thể cập nhật lựa chọn gia hạn.')
      }
      changes = { renewal: payload.choice }
      break
    case 'handover': {
      if (actor.role !== 'owner' || contract.status !== 'signed') throw new Error('Chỉ ghi nhận bàn giao cho hợp đồng đã ký.')
      if ((payload.actualMoveIn && payload.actualMoveIn > today) || (payload.actualMoveOut && payload.actualMoveOut > today)) {
        throw new Error('Ngày bàn giao thực tế phải là hôm nay hoặc trước đó.')
      }
      if (payload.actualMoveIn && payload.actualMoveOut && payload.actualMoveOut < payload.actualMoveIn) {
        throw new Error('Ngày trả phòng phải từ ngày nhận phòng trở đi.')
      }
      const deadline = moveInDeadline(contract)
      if (payload.actualMoveIn && payload.actualMoveIn > deadline) {
        throw new Error(`Khách dọn vào sau quá 3 ngày kể từ ngày quy định (${shortDate(contract.startDate)}); hợp đồng thuộc diện bị hủy và mất tiền cọc.`)
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
    }
    default:
      throw new Error('Thao tác không hợp lệ.')
  }
  const apartments = (action === 'sign' || action === 'confirm-sign-and-deposit')
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
  const leaseTermUnit = terms.leaseTermUnit || 'CUSTOM'
  const leaseTermValue = Number(terms.leaseTermValue || 1)
  const startDate = terms.startDate
  const endDate = terms.endDate || ((leaseTermUnit === 'MONTH' || leaseTermUnit === 'YEAR')
    ? calculateEndDateForTerm(startDate, leaseTermUnit, leaseTermValue)
    : '')
  if (!startDate || !endDate || startDate < dateAt() || endDate < startDate || !Number.isSafeInteger(terms.rent) || terms.rent <= 0) {
    throw new Error('Kiểm tra lại thời hạn và tiền thuê.')
  }
  const tenantId = actor.role === 'tenant' ? actor.id : terms.tenantId
  if (identity(tenantId)?.role !== 'tenant') throw new Error('Chọn khách thuê.')
  if (terms.renewalOf && (actor.role !== 'owner' || !base || base.apartmentId !== unit.id || base.tenantId !== tenantId ||
    base.renewal !== 'requested' || startDate <= base.endDate || db.contracts.some((item) => item.renewalOf === base.id && item.status !== 'cancelled'))) {
    throw new Error('Kiểm tra lại yêu cầu gia hạn và thời hạn của hợp đồng mới.')
  }
  const candidateDates = { startDate, endDate }
  const conflict = db.contracts.some((item) => item.apartmentId === unit.id && item.status !== 'cancelled' && overlaps(occupiedDates(item), candidateDates) &&
    (item.status === 'signed' || item.tenantId === tenantId))
  if (conflict) throw new Error('Đã có hợp đồng hoặc yêu cầu của khách thuê này trùng thời gian.')
  const contract = makeContract(db, unit.id, tenantId, startDate, endDate, terms.rent, terms.renewalOf, {
    leaseTermUnit,
    leaseTermValue,
  })
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

  const breakdown = calculateLeaseBreakdown(contract.startDate, contract.endDate, contract.rent)
  const matchedPeriod = breakdown.periods.find((p) => p.billingMonth === month) ?? breakdown.periods[0]
  const rentAmount = matchedPeriod ? matchedPeriod.rentAmount : contract.rent
  const rentTitle = matchedPeriod && !matchedPeriod.fullMonth
    ? `Tiền thuê (${matchedPeriod.daysInPeriod}/${matchedPeriod.monthBasisDays} ngày)`
    : 'Tiền thuê'

  const lines = [
    { title: rentTitle, amount: rentAmount },
    { title: `Tiền điện (${newElectric - oldElectric} kWh)`, amount: (newElectric - oldElectric) * contract.electricRate },
    { title: `Tiền nước (${newWater - oldWater} m³)`, amount: (newWater - oldWater) * contract.waterRate },
  ]
  return {
    ...db,
    invoices: [
      ...db.invoices,
      {
        id: globalThis.crypto.randomUUID(),
        contractId: contract.id,
        month,
        periodStart: matchedPeriod?.periodStart ?? contract.startDate,
        periodEnd: matchedPeriod?.periodEnd ?? contract.endDate,
        daysInPeriod: matchedPeriod?.daysInPeriod ?? 30,
        monthBasisDays: matchedPeriod?.monthBasisDays ?? 30,
        ...values,
        lines,
        status: 'unpaid',
      },
    ],
  }
}

export const invoiceTotal = (invoice) => invoice.lines.reduce((sum, line) => sum + line.amount, 0)
export const unitImage = (unit) => unit.customImage || `/images/${unit.image}.webp`

// ============================================================================
// Kết nối trực tiếp tới Backend API (apartment-rental-backend)
// ============================================================================

const API_BASE = import.meta.env?.VITE_API_BASE_URL || '/api'

export async function apiRequest(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
    ...options,
  })
  if (!response.ok) {
    let message = `Lỗi kết nối máy chủ (${response.status})`
    try {
      const err = await response.json()
      if (err?.message) message = err.message
    } catch {
      // Ignore JSON parse error
    }
    throw new Error(message)
  }
  if (response.status === 204) return null
  return response.json()
}

export const backendApi = {
  checkHealth: () => apiRequest('/health'),
  login: (userIdOrEmail, password = 'AnCu@123') =>
    apiRequest('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ userId: userIdOrEmail, email: userIdOrEmail, password }),
    }),
  logout: () => apiRequest('/auth/logout', { method: 'POST' }),
  me: () => apiRequest('/auth/me'),
  listPublicApartments: (params = '') => apiRequest(`/public/apartments${params ? `?${params}` : ''}`),
  listOwnerBuildings: () => apiRequest('/owner/buildings'),
  createBuilding: (body) => apiRequest('/owner/buildings', { method: 'POST', body: JSON.stringify(body) }),
  updateBuilding: (id, body) => apiRequest(`/owner/buildings/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  listOwnerApartments: () => apiRequest('/owner/apartments'),
  createApartment: (body) => apiRequest('/owner/apartments', { method: 'POST', body: JSON.stringify(body) }),
  updateApartment: (id, body) => apiRequest(`/owner/apartments/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  updateListing: (id, body) => apiRequest(`/owner/apartments/${id}/listing`, { method: 'PATCH', body: JSON.stringify(body) }),
  togglePublish: (id, published) =>
    apiRequest(`/owner/apartments/${id}/publish`, { method: 'PATCH', body: JSON.stringify({ published }) }),
  listContracts: () => apiRequest('/contracts'),
  createContract: (body) => apiRequest('/contracts', { method: 'POST', body: JSON.stringify(body) }),
  scheduleContract: (id, appointment) =>
    apiRequest(`/contracts/${id}/schedule`, { method: 'POST', body: JSON.stringify({ appointment }) }),
  confirmSignAndDeposit: (id, body) =>
    apiRequest(`/contracts/${id}/confirm-sign-and-deposit`, { method: 'POST', body: JSON.stringify(body) }),
  cancelNoShow: (id, today) =>
    apiRequest(`/contracts/${id}/cancel-no-show${today ? `?today=${today}` : ''}`, { method: 'POST' }),
  recordHandover: (id, body) =>
    apiRequest(`/contracts/${id}/handover`, { method: 'POST', body: JSON.stringify(body) }),
  respondRenewal: (id, choice) =>
    apiRequest(`/contracts/${id}/renewal`, { method: 'POST', body: JSON.stringify({ choice }) }),
  createInvoice: (contractId, body) =>
    apiRequest(`/contracts/${contractId}/invoices`, { method: 'POST', body: JSON.stringify(body) }),
  listInvoices: () => apiRequest('/invoices'),
  confirmInvoicePaid: (id) => apiRequest(`/invoices/${id}/confirm-paid`, { method: 'POST' }),
  listFavorites: () => apiRequest('/favorites'),
  toggleFavorite: (apartmentId) => apiRequest(`/favorites/${apartmentId}/toggle`, { method: 'POST' }),
}
