import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { waitForBackend } from './dev-readiness.mjs'

async function healthServer(context, payloadFn) {
  const server = createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json')
    const payload = typeof payloadFn === 'function' ? payloadFn() : payloadFn
    response.end(JSON.stringify(payload))
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(async () => {
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  })
  return `http://127.0.0.1:${server.address().port}/api/health`
}

test('startup waits until the API actually reports UP', async (context) => {
  let attempts = 0
  const url = await healthServer(context, () => ({
    status: ++attempts >= 3 ? 'UP' : 'STARTING',
    databaseEngine: 'H2',
  }))
  const body = await waitForBackend(url, { isRunning: () => true, expectedDatabaseEngine: 'H2', timeoutMs: 2000, intervalMs: 5 })
  assert.equal(attempts, 3)
  assert.equal(body.databaseEngine, 'H2')
})

test('startup stops waiting when the backend process has exited', async () => {
  await assert.rejects(waitForBackend('http://127.0.0.1:9/api/health', {
    isRunning: () => false, timeoutMs: 2000,
  }), /Backend Java đã dừng/)
})

test('an unhealthy backend reaches the startup timeout', async (context) => {
  const url = await healthServer(context, () => ({ status: 'DOWN', databaseEngine: 'UNKNOWN' }))
  await assert.rejects(waitForBackend(url, { isRunning: () => true, timeoutMs: 150, intervalMs: 10 }), /chưa sẵn sàng/)
})

test('sqlserver mode rejects an H2 backend instead of accepting silent fallback', async (context) => {
  const url = await healthServer(context, () => ({ status: 'UP', databaseEngine: 'H2' }))
  await assert.rejects(waitForBackend(url, {
    isRunning: () => true,
    expectedDatabaseEngine: 'SQL Server',
    timeoutMs: 1000,
    intervalMs: 5,
  }), /chạy nhầm cơ sở dữ liệu/)
})
