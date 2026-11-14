import { test } from 'node:test'
import assert from 'node:assert/strict'
import { addContract, billForMonth, dateAt, identity, invoiceTotal, loadDemo, makeContract, updateContract } from '../src/demo.js'

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
  assert.throws(() => updateContract(db, tenant, contract.id, 'sign', { accepted: false }))
  assert.throws(() => updateContract(db, identity('t2'), contract.id, 'sign', { accepted: true }))
  db = updateContract(db, tenant, contract.id, 'sign', { accepted: true })
  assert.equal(db.contracts.at(-1).status, 'signed')
  assert.equal(db.contracts.at(-1).payment, 'paid')
  assert.equal(db.apartments[0].published, false)
  assert.throws(() => updateContract(db, tenant, contract.id, 'sign', { accepted: true }))
})

test('one apartment cannot have overlapping signed contracts', () => {
  let { db, contract } = request()
  const other = makeContract(db, contract.apartmentId, 't2', contract.startDate, contract.endDate, contract.rent)
  db = { ...db, contracts: [...db.contracts, other] }
  const appointment = `${dateAt(1)}T09:00`
  db = updateContract(db, tenant, contract.id, 'schedule', { appointment, now: `${dateAt()}T09:00` })
  db = updateContract(db, identity('t2'), other.id, 'schedule', { appointment, now: `${dateAt()}T09:00` })
  db = updateContract(db, tenant, contract.id, 'sign', { accepted: true })
  assert.throws(() => updateContract(db, identity('t2'), other.id, 'sign', { accepted: true }), /trùng thời gian/)
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

test('early handover adds exactly one month of rent and early return leaves the monthly rent intact', () => {
  let db = loadDemo()
  const signed = {
    ...makeContract(db, 'a1', 't1', dateAt(-4), dateAt(25), 12500000),
    status: 'signed', payment: 'paid',
  }
  db = { ...db, contracts: [...db.contracts, signed] }
  const month = signed.startDate.slice(0, 7)
  db = billForMonth(db, owner, signed, month, meters)
  db = updateContract(db, owner, signed.id, 'handover', { actualMoveIn: dateAt(-7), actualMoveOut: dateAt(-2) })
  db = updateContract(db, owner, signed.id, 'handover', { actualMoveIn: dateAt(-6), actualMoveOut: dateAt(-1) })
  const monthly = db.invoices.filter((item) => item.contractId === signed.id && !item.kind)
  const penalties = db.invoices.filter((item) => item.contractId === signed.id && item.kind === 'early-move-in')
  assert.equal(monthly.length, 1)
  assert.equal(monthly[0].lines[0].amount, signed.rent)
  assert.equal(penalties.length, 1)
  assert.equal(invoiceTotal(penalties[0]), signed.rent)
  assert.throws(() => billForMonth(db, owner, signed, month, meters))
  assert.throws(() => billForMonth(db, identity('o2'), signed, month, meters))
})
