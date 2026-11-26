import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  addContract,
  apiRequest,
  billForMonth,
  calculateEndDateForTerm,
  calculateLeaseBreakdown,
  canCancelNoShow,
  clearCachedCsrfToken,
  dateAt,
  identity,
  invoiceTotal,
  IS_PROTOTYPE_MODE,
  loadDemo,
  makeContract,
  monthBasisDays,
  plusDays,
  runAppMutation,
  unitStatus,
  updateContract,
} from '../src/demo.js'

const tenant = identity('t1')
const owner = identity('o1')
const meters = { oldElectric: 10, newElectric: 12, oldWater: 5, newWater: 7 }

function request(db = loadDemo()) {
  return addContract(db, tenant, db.apartments[0], { startDate: dateAt(5), endDate: dateAt(185), rent: 12500000 })
}

test('scheduling and rescheduling change only the appointment; an accepted signature updates both statuses', () => {
  let { db, contract } = request()
  const now = `${dateAt()}T10:00`
  for (const slot of [`${dateAt(1)}T09:30`, `${dateAt(2)}T16:45`]) {
    db = updateContract(db, tenant, contract.id, 'schedule', { now, appointment: slot, location: 'Địa điểm khác' })
    assert.deepEqual(db.contracts.at(-1), { ...contract, appointment: slot })
  }
  assert.throws(() => updateContract(db, tenant, contract.id, 'sign', { accepted: false, receivedAmount: contract.deposit }))
  assert.throws(() => updateContract(db, identity('t2'), contract.id, 'sign', { accepted: true, receivedAmount: contract.deposit }))
  // P0-02: missing or insufficient receivedAmount must be rejected
  assert.throws(() => updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true }))
  assert.throws(() => updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true, receivedAmount: contract.deposit - 1 }))

  db = updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true, receivedAmount: contract.deposit })
  assert.equal(db.contracts.at(-1).status, 'signed')
  assert.equal(db.contracts.at(-1).payment, 'paid')
  assert.equal(db.contracts.at(-1).depositReceivedAmount, contract.deposit)
  assert.equal(db.apartments[0].published, false)
  assert.throws(() => updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true, receivedAmount: contract.deposit }))
})

test('decision 1: broker/owner confirms signature at appointment location & deposit payment, and cancels contract with forfeited deposit after 3 days no-show', () => {
  let { db, contract } = request()
  const slot = `${dateAt(1)}T10:00`
  db = updateContract(db, tenant, contract.id, 'schedule', { now: `${dateAt()}T08:00`, appointment: slot })

  assert.throws(() => updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: false, receivedAmount: contract.deposit }))
  assert.throws(() => updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: false, depositReceived: true, receivedAmount: contract.deposit }))

  db = updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true, receivedAmount: contract.deposit })
  const signedContract = db.contracts.at(-1)
  assert.equal(signedContract.status, 'signed')
  assert.equal(signedContract.payment, 'paid')
  assert.equal(signedContract.signedConfirmedBy, owner.id)
  assert.equal(signedContract.paymentConfirmedBy, owner.id)
  assert.equal(signedContract.depositReceivedAmount, contract.deposit)
  assert.equal(db.apartments[0].published, false)

  // Within 3 days of startDate (startDate + 3), cannot cancel for no-show
  const deadline = plusDays(signedContract.startDate, 3)
  assert.equal(canCancelNoShow(signedContract, deadline), false)
  assert.throws(() => updateContract(db, owner, contract.id, 'cancel-no-show', {}, deadline))

  // Recording move-in more than 3 days after startDate is rejected
  const afterDeadline = plusDays(signedContract.startDate, 4)
  assert.throws(() => updateContract(db, owner, contract.id, 'handover', { actualMoveIn: afterDeadline }, afterDeadline))

  // On day 4 without move-in, owner/broker can cancel and tenant forfeits deposit
  assert.equal(canCancelNoShow(signedContract, afterDeadline), true)
  db = updateContract(db, owner, contract.id, 'cancel-no-show', {}, afterDeadline)
  const cancelled = db.contracts.at(-1)
  assert.equal(cancelled.status, 'cancelled')
  assert.equal(cancelled.depositForfeited, true)
  assert.ok(cancelled.cancellationReason.includes('mất tiền đặt cọc'))
  assert.equal(unitStatus(db, db.apartments[0], afterDeadline), 'ready')
})

test('decision 2: first and last period rent calculation from move-in date with 30/31-day months and February counted as 30 days', () => {
  assert.equal(monthBasisDays(1), 31)
  assert.equal(monthBasisDays(2), 30)
  assert.equal(monthBasisDays(3), 31)
  assert.equal(monthBasisDays(4), 30)

  const febEnd = calculateEndDateForTerm('2026-02-10', 'MONTH', 1)
  assert.equal(febEnd, '2026-03-11')
  const febBreakdown = calculateLeaseBreakdown('2026-02-10', febEnd, 12000000)
  assert.equal(febBreakdown.totalPeriods, 1)
  assert.equal(febBreakdown.firstPeriodRent, 12000000)
  assert.equal(febBreakdown.lastPeriodRent, 12000000)
  assert.equal(febBreakdown.periods[0].daysInPeriod, 30)
  assert.equal(febBreakdown.periods[0].monthBasisDays, 30)

  const customBreakdown = calculateLeaseBreakdown('2026-03-10', '2026-04-24', 12000000)
  assert.equal(customBreakdown.totalPeriods, 2)
  assert.equal(customBreakdown.firstPeriodRent, 12000000)
  assert.equal(customBreakdown.lastPeriodRent, 6000000)
  assert.equal(customBreakdown.totalContractRent, 18000000)
})

test('one apartment cannot have overlapping signed contracts', () => {
  let { db, contract } = request()
  const other = makeContract(db, contract.apartmentId, 't2', contract.startDate, contract.endDate, contract.rent)
  db = { ...db, contracts: [...db.contracts, other] }
  const appointment = `${dateAt(1)}T09:00`
  db = updateContract(db, tenant, contract.id, 'schedule', { appointment, now: `${dateAt()}T09:00` })
  db = updateContract(db, identity('t2'), other.id, 'schedule', { appointment, now: `${dateAt()}T09:00` })
  db = updateContract(db, owner, contract.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true, receivedAmount: contract.deposit })
  assert.throws(() => updateContract(db, owner, other.id, 'confirm-sign-and-deposit', { signedAtLocation: true, depositReceived: true, receivedAmount: other.deposit }), /trùng thời gian/)
  assert.equal(db.contracts.at(-1).payment, 'unpaid')
})

test('renewal produces an independent contract scoped to the owner', () => {
  const id = 'HD-1001'
  const db = updateContract(loadDemo(), tenant, id, 'renewal', { choice: 'requested' })
  const original = db.contracts.find((item) => item.id === id)
  const unit = db.apartments.find((item) => item.id === original.apartmentId)
  const terms = { startDate: dateAt(9), endDate: dateAt(180), rent: original.rent, tenantId: tenant.id, renewalOf: id }
  assert.throws(() => addContract(db, identity('o2'), unit, terms))
  const result = addContract(db, owner, unit, terms)
  assert.notEqual(result.contract.id, id)
  assert.equal(result.contract.renewalOf, id)
  assert.equal(result.contract.status, 'awaiting')
  assert.deepEqual(result.db.contracts.find((item) => item.id === id), original)
  assert.throws(() => addContract(result.db, owner, unit, terms))
})

test('early handover adds exactly one month of rent, checkout preserves move-in date, and early return leaves rent intact', () => {
  let db = loadDemo()
  const signed = {
    ...makeContract(db, 'a1', 't1', dateAt(-4), dateAt(25), 12500000),
    status: 'signed', payment: 'paid',
  }
  db = { ...db, contracts: [...db.contracts, signed] }
  const month = signed.startDate.slice(0, 7)
  db = billForMonth(db, owner, signed, month, meters)
  db = updateContract(db, owner, signed.id, 'handover', { actualMoveIn: dateAt(-7), initialElectric: 100, initialWater: 10 })
  // P0-06: recording checkout with omitted actualMoveIn preserves actualMoveIn
  db = updateContract(db, owner, signed.id, 'handover', { actualMoveOut: dateAt(-2) })
  const updated = db.contracts.find((c) => c.id === signed.id)
  assert.equal(updated.actualMoveIn, dateAt(-7))
  assert.equal(updated.actualMoveOut, dateAt(-2))
  const monthly = db.invoices.filter((item) => item.contractId === signed.id && !item.kind)
  const penalties = db.invoices.filter((item) => item.contractId === signed.id && item.kind === 'early-move-in')
  assert.equal(monthly.length, 1)
  assert.equal(monthly[0].lines[0].amount, signed.rent)
  assert.equal(penalties.length, 1)
  assert.equal(invoiceTotal(penalties[0]), signed.rent)
  assert.throws(() => billForMonth(db, owner, signed, month, meters))
  assert.throws(() => billForMonth(db, identity('o2'), signed, month, meters))
})

test('contract management: owner can schedule appointment, update awaiting contract terms, and cancel awaiting contract before signing', () => {
  let { db, contract } = request()
  const slot = `${dateAt(1)}T14:00`
  db = updateContract(db, owner, contract.id, 'schedule', { now: `${dateAt()}T08:00`, appointment: slot })
  assert.equal(db.contracts.at(-1).appointment, slot)

  // P0-03: tenant cannot tamper with rent
  assert.throws(() => addContract(db, tenant, db.apartments[0], { startDate: dateAt(10), endDate: dateAt(40), rent: 1000 }))
  assert.throws(() => updateContract(db, tenant, contract.id, 'update-awaiting', { startDate: dateAt(10), rent: 1000 }))

  db = updateContract(db, owner, contract.id, 'update-awaiting', {
    startDate: dateAt(10),
    leaseTermUnit: 'YEAR',
    leaseTermValue: 1,
    rent: 12000000,
  })
  const updated = db.contracts.at(-1)
  assert.equal(updated.startDate, dateAt(10))
  assert.equal(updated.leaseTermUnit, 'YEAR')
  assert.equal(updated.rent, 12000000)
  assert.equal(updated.deposit, 12000000)

  db = updateContract(db, tenant, contract.id, 'cancel-awaiting', { reason: 'Đổi kế hoạch thuê' })
  const cancelled = db.contracts.at(-1)
  assert.equal(cancelled.status, 'cancelled')
  assert.equal(cancelled.depositForfeited, false)
  assert.equal(cancelled.cancellationReason, 'Đổi kế hoạch thuê')
})

test('T20 (P1-14): Application Mode defaults to real API and NEVER falls back to local mock state on network/500/409 errors', async () => {
  assert.equal(IS_PROTOTYPE_MODE, false)

  let prototypeCalled = false
  // Simulate backend offline (network error)
  await assert.rejects(
    runAppMutation({
      prototypeMode: false,
      apiCall: () => apiRequest('/contracts', { method: 'POST', body: '{}' }, async () => { throw new TypeError('Failed to fetch') }),
      prototypeCall: () => { prototypeCalled = true; return { fake: true } },
    }),
    (err) => err.isNetworkError === true && err.message.includes('Không thể kết nối'),
  )
  assert.equal(prototypeCalled, false)

  // Simulate HTTP 409 conflict
  await assert.rejects(
    runAppMutation({
      prototypeMode: false,
      apiCall: () => apiRequest('/contracts/c1/confirm-sign-and-deposit', { method: 'POST', body: '{}' }, async () => ({
        ok: false,
        status: 409,
        headers: new Headers(),
        json: async () => ({ code: 'OVERLAPPING_SIGNED_CONTRACT', message: 'Căn hộ đã có hợp đồng đã ký trùng thời gian.' }),
      })),
      prototypeCall: () => { prototypeCalled = true },
    }),
    (err) => err.status === 409 && err.code === 'OVERLAPPING_SIGNED_CONTRACT',
  )
  assert.equal(prototypeCalled, false)

  // Simulate HTTP 500 error
  await assert.rejects(
    runAppMutation({
      prototypeMode: false,
      apiCall: () => apiRequest('/invoices/inv1/confirm-paid', { method: 'POST' }, async () => ({
        ok: false,
        status: 500,
        headers: new Headers(),
        json: async () => ({ code: 'INTERNAL_ERROR', message: 'Hệ thống gặp lỗi khi xử lý yêu cầu.' }),
      })),
      prototypeCall: () => { prototypeCalled = true },
    }),
    (err) => err.status === 500 && err.code === 'INTERNAL_ERROR',
  )
  assert.equal(prototypeCalled, false)

  // Verify CSRF token from login response header is automatically propagated to subsequent mutating requests
  clearCachedCsrfToken()
  let capturedHeaders = null
  await apiRequest('/auth/login', { method: 'POST', body: '{}' }, async () => ({
    ok: true,
    status: 200,
    headers: new Headers({ 'X-CSRF-Token': 'test-csrf-token-123' }),
    json: async () => ({ id: 'o1', role: 'OWNER' }),
  }))
  await apiRequest('/contracts/c1/cancel', { method: 'POST', body: '{}' }, async (_url, init) => {
    capturedHeaders = init.headers
    return {
      ok: true,
      status: 200,
      headers: new Headers(),
      json: async () => ({ id: 'c1', status: 'cancelled' }),
    }
  })
  assert.equal(capturedHeaders['X-CSRF-Token'], 'test-csrf-token-123')
  clearCachedCsrfToken()
})
