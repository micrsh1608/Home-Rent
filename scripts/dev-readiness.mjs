import { setTimeout as delay } from 'node:timers/promises'

export async function waitForBackend(url, { isRunning, expectedDatabaseEngine, timeoutMs = 120_000, intervalMs = 500 } = {}) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (isRunning && !isRunning()) throw new Error('Backend Java đã dừng trước khi sẵn sàng. Hãy xem lỗi Maven/Java phía trên.')
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(Math.max(1, Math.min(2000, deadline - Date.now()))) })
      if (response.ok) {
        const body = await response.json()
        if (body.status === 'UP' && (!isRunning || isRunning())) {
          if (expectedDatabaseEngine) {
            const actualEngine = String(body.databaseEngine || '')
            if (!actualEngine.toLowerCase().includes(expectedDatabaseEngine.toLowerCase())) {
              throw new Error(
                `Backend khởi động với cơ sở dữ liệu '${actualEngine || 'UNKNOWN'}' thay vì '${expectedDatabaseEngine}'. Đã dừng để tránh chạy nhầm cơ sở dữ liệu.`,
              )
            }
          }
          return body
        }
      }
    } catch (error) {
      if (error?.message?.includes('chạy nhầm cơ sở dữ liệu')) {
        throw error
      }
      /* Maven/Java may still be compiling or opening its HTTP port. */
    }
    await delay(Math.min(intervalMs, Math.max(0, deadline - Date.now())))
  }
  throw new Error(`Backend chưa sẵn sàng sau ${Math.round(timeoutMs / 1000)} giây. Kiểm tra log Java và cấu hình cổng backend.`)
}
