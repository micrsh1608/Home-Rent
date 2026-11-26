import { spawn } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { waitForBackend } from './dev-readiness.mjs'

const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const frontendDir = resolve(rootDir, 'apartment-rental-frontend')
const backendDir = resolve(rootDir, 'apartment-rental-backend')
const isWin = process.platform === 'win32'
const mode = process.argv[2] || 'dev'

if (mode === 'dev') {
  for (const path of [resolve(backendDir, '.env'), resolve(rootDir, '.env')]) {
    if (existsSync(path)) process.loadEnvFile(path)
  }
}

function spawnCmd(bin, args, cwd = rootDir) {
  if (isWin) {
    const quotedArgs = args.map((a) => (a.includes(' ') ? `"${a}"` : a)).join(' ')
    return spawn('cmd.exe', ['/d', '/s', '/c', `${bin} ${quotedArgs}`], {
      cwd,
      stdio: 'inherit',
      env: process.env,
      windowsVerbatimArguments: true,
      windowsHide: true,
    })
  }
  return spawn(bin, args, {
    cwd,
    stdio: 'inherit',
    env: process.env,
  })
}

function runStep(bin, args, cwd = rootDir) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawnCmd(bin, args, cwd)
    child.on('error', rejectPromise)
    child.on('exit', (code) => {
      if (code === 0) resolvePromise()
      else rejectPromise(new Error(`Command "${bin} ${args.join(' ')}" exited with code ${code}`))
    })
  })
}

async function ensureFrontendDeps() {
  if (!existsSync(resolve(frontendDir, 'node_modules'))) {
    console.log('[An Cư] Đang cài đặt phụ thuộc cho Frontend...')
    await runStep('npm', ['ci'], frontendDir)
  }
}

async function runDev(useSqlServer = false) {
  await ensureFrontendDeps()

  const backendArgs = useSqlServer
    ? ['spring-boot:run', '-Psqlserver']
    : ['spring-boot:run', '-Plocal']

  console.log(
    `[An Cư] Khởi động Full-Stack (${useSqlServer ? 'Microsoft SQL Server' : 'H2 Local MSSQLServer Mode'})...`,
  )
  const backendTarget = process.env.VITE_API_TARGET || `http://127.0.0.1:${process.env.SERVER_PORT || 8080}`
  const healthUrl = new URL('/api/health', backendTarget).href
  process.env.VITE_API_TARGET = backendTarget
  console.log(`  • Backend API : ${healthUrl}`)
  console.log('  • Frontend UI : http://127.0.0.1:5173')

  let occupied = false
  try { await fetch(healthUrl, { signal: AbortSignal.timeout(1000) }); occupied = true } catch { /* Port not serving HTTP yet. */ }
  if (occupied) throw new Error('Cổng backend đang được một ứng dụng khác sử dụng. Dừng lần chạy cũ trước khi chạy npm.cmd run dev lại.')

  const backendProc = spawnCmd('mvn', backendArgs, backendDir)
  let frontendProc
  let stopping = false
  let backendError
  backendProc.on('error', (error) => { backendError = error })

  const stopChild = (child) => new Promise((done) => {
    if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return done()
    if (isWin) {
      const killer = spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true })
      killer.once('close', done)
      killer.once('error', done)
    } else {
      child.kill('SIGTERM')
      done()
    }
  })
  const shutdown = async () => {
    if (stopping) return
    stopping = true
    await Promise.all([stopChild(frontendProc), stopChild(backendProc)])
  }

  process.once('SIGINT', async () => { await shutdown(); process.exit(0) })
  process.once('SIGTERM', async () => { await shutdown(); process.exit(0) })

  try {
    console.log('[An Cư] Đang chờ backend Java khởi động. Frontend sẽ mở sau khi API sẵn sàng...')
    await waitForBackend(healthUrl, {
      isRunning: () => !backendError && backendProc.exitCode === null && backendProc.signalCode === null,
      expectedDatabaseEngine: useSqlServer ? 'SQL Server' : 'H2',
    })
    if (stopping) return
    console.log('[An Cư] Backend đã sẵn sàng. Đang khởi động frontend...')
    frontendProc = spawnCmd('npm', ['run', 'dev'], frontendDir)
    const onChildExit = async (label, code) => {
      if (stopping) return
      console.error(`[An Cư] ${label} đã dừng (${code ?? 'signal'}). Đang đóng các tiến trình của lần chạy này.`)
      await shutdown()
      process.exitCode = code || 1
    }
    backendProc.once('exit', (code) => onChildExit('Backend', code))
    frontendProc.once('exit', (code) => onChildExit('Frontend', code))
    frontendProc.once('error', () => onChildExit('Frontend', 1))
  } catch (error) {
    await shutdown()
    throw error
  }
}

async function runBuild() {
  await ensureFrontendDeps()

  console.log('[An Cư 1/3] Đang đóng gói Frontend React (Vite build)...')
  await runStep('npm', ['run', 'build'], frontendDir)

  const distDir = resolve(frontendDir, 'dist')
  const staticDir = resolve(backendDir, 'src', 'main', 'resources', 'static')

  console.log('[An Cư 2/3] Đồng bộ bản dựng Frontend vào tài nguyên tĩnh của Spring Boot...')
  rmSync(staticDir, { recursive: true, force: true })
  mkdirSync(staticDir, { recursive: true })
  cpSync(distDir, staticDir, { recursive: true })

  console.log('[An Cư 3/3] Đang biên dịch Backend Spring Boot cùng tài nguyên tĩnh hợp nhất...')
  await runStep('mvn', ['test-compile'], backendDir)
  console.log('[An Cư] Hoàn tất đóng gói dự án Full-Stack!')
}

async function runTests() {
  await ensureFrontendDeps()
  console.log('[An Cư 1/3] Kiểm tra chờ backend sẵn sàng...')
  await runStep('node', ['--test', 'scripts/dev-readiness.test.mjs'], rootDir)
  console.log('[An Cư 2/3] Chạy kiểm thử đơn vị quy tắc nghiệp vụ Frontend...')
  await runStep('npm', ['test'], frontendDir)
  console.log('[An Cư 3/3] Chạy kiểm thử tích hợp Backend (Spring Boot & Flyway)...')
  await runStep('mvn', ['test'], backendDir)
  console.log('[An Cư] Tất cả kiểm thử Frontend và Backend đều đạt!')
}

const useSqlServer = process.argv.includes('--sqlserver') || process.env.ANCU_DB_MODE === 'sqlserver'

if (mode === 'dev') {
  runDev(useSqlServer).catch((err) => {
    console.error(err.message)
    process.exit(1)
  })
} else if (mode === 'build') {
  runBuild().catch((err) => {
    console.error(err.message)
    process.exit(1)
  })
} else if (mode === 'test') {
  runTests().catch((err) => {
    console.error(err.message)
    process.exit(1)
  })
} else {
  console.error(`Chế độ không hợp lệ: ${mode}. Sử dụng: dev | build | test`)
  process.exit(1)
}
