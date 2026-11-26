import { createContext, useContext, useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, Bell, Building2, Check, ChevronDown, CircleHelp, FileText, Heart, House, LayoutDashboard, LogOut, Menu, Newspaper, Receipt, RefreshCw, UserRound, Users, X } from 'lucide-react'
import { backendApi, dateAt, IS_PROTOTYPE_MODE, loadDemo, people, renewalOpen, storageKey } from './demo'

const Context = createContext(null)

function extractBuildingFromApartment(item) {
  if (!item?.buildingId || !item?.propertyName) return null
  return {
    id: item.buildingId,
    ownerId: item.ownerId,
    name: item.propertyName,
    district: item.district ?? '',
    address: item.address ?? '',
    location: item.location ?? `Sảnh lễ tân ${item.propertyName}, ${item.address ?? ''}`,
    electricRate: Number(item.electricRate ?? 3500),
    waterRate: Number(item.waterRate ?? 20000),
  }
}

const emptyDatabase = {
  buildings: [],
  apartments: [],
  contracts: [],
  invoices: [],
  favorites: {},
  accounts: people,
  ownerDashboard: null,
}

export function DemoProvider({ children }) {
  const [db, setDb] = useState(() => (IS_PROTOTYPE_MODE ? { ...loadDemo(), accounts: people, ownerDashboard: null } : emptyDatabase))
  const [actor, setActor] = useState(null)
  const refreshVersion = useRef(0)
  const [toast, setToast] = useState('')
  const [apiConnected, setApiConnected] = useState(!IS_PROTOTYPE_MODE)
  const [apiLoading, setApiLoading] = useState(!IS_PROTOTYPE_MODE)
  const [databaseEngine, setDatabaseEngine] = useState('')

  useEffect(() => {
    if (!IS_PROTOTYPE_MODE) {
      try { localStorage.removeItem(storageKey) } catch { /* Clear any old mockup cache */ }
      sessionStorage.removeItem('an-cu-password')
      sessionStorage.removeItem('an-cu-actor')
    }
  }, [])

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(''), 3600)
    return () => clearTimeout(timer)
  }, [toast])

  const normalizeAccount = (account) => ({ ...account, role: String(account.role || '').toLowerCase() })

  async function refreshFromBackend() {
    if (IS_PROTOTYPE_MODE) {
      setApiLoading(false)
      return
    }
    const version = ++refreshVersion.current
    setApiLoading(true)
    try {
      const health = await backendApi.checkHealth()
      const [publicData, rawAccounts, sessionUser] = await Promise.all([
        backendApi.listPublicApartments('size=100'),
        backendApi.listAccounts().catch(() => null),
        backendApi.me().catch((error) => { if (error.status === 401) return null; throw error }),
      ])

      let accounts = Array.isArray(rawAccounts) && rawAccounts.length > 0
        ? rawAccounts.map(normalizeAccount)
        : people
      const currentActorId = sessionUser?.id

      let ownerBuildings = null
      let ownerUnits = null
      let ownerDashboard = null
      let contracts = null
      let invoices = null
      let favs = null

      if (currentActorId) {
        const contacts = await backendApi.listContacts()
        accounts = [...new Map([...accounts, ...contacts.map(normalizeAccount), normalizeAccount(sessionUser)].map((acc) => [acc.id, acc])).values()]
        const currentRole = String(sessionUser.role).toLowerCase()
        if (currentRole === 'owner') {
          ;[ownerBuildings, ownerUnits, ownerDashboard] = await Promise.all([
            backendApi.listOwnerBuildings(),
            backendApi.listOwnerApartments(),
            backendApi.getOwnerDashboard().catch(() => null),
          ])
        }
        ;[contracts, invoices, favs] = await Promise.all([
          backendApi.listContracts(),
          backendApi.listInvoices(),
          backendApi.listFavorites(),
        ])
      }

      if (version !== refreshVersion.current) return
      setDatabaseEngine(health?.databaseEngine || '')
      setActor(sessionUser ? normalizeAccount(sessionUser) : null)
      setApiConnected(true)
      setDb((prev) => {
        const buildingsMap = new Map()
        const apartmentsMap = new Map()

        if (publicData?.items) {
          for (const item of publicData.items) {
            const normalizedUnit = {
              ...item,
              area: Number(item.area),
              published: true,
              unitStatus: item.unitStatus ?? 'ready',
            }
            apartmentsMap.set(item.id, normalizedUnit)
            const buildingInfo = extractBuildingFromApartment(item)
            if (buildingInfo && !buildingsMap.has(buildingInfo.id)) {
              buildingsMap.set(buildingInfo.id, buildingInfo)
            } else if (buildingInfo && buildingsMap.has(buildingInfo.id)) {
              buildingsMap.set(buildingInfo.id, { ...buildingsMap.get(buildingInfo.id), ...buildingInfo })
            }
          }
        }

        if (currentActorId && ownerBuildings && ownerUnits) {
          for (const b of ownerBuildings) {
            buildingsMap.set(b.id, b)
          }
          for (const u of ownerUnits) {
            apartmentsMap.set(u.id, { ...u, area: Number(u.area) })
          }
        }

        let nextContracts = []
        let nextInvoices = []
        const nextFavorites = { guest: prev.favorites.guest ?? [] }

        if (currentActorId && contracts && invoices) {
          nextContracts = contracts.map((c) => ({
            ...c,
            appointment: c.appointment ? String(c.appointment).slice(0, 16) : '',
            actualMoveIn: c.actualMoveIn ?? '',
            actualMoveOut: c.actualMoveOut ?? '',
          }))
          nextInvoices = invoices
          if (favs?.favoriteIds) {
            nextFavorites[currentActorId] = favs.favoriteIds
          }
        }

        return {
          buildings: Array.from(buildingsMap.values()),
          apartments: Array.from(apartmentsMap.values()),
          contracts: nextContracts,
          invoices: nextInvoices,
          favorites: nextFavorites,
          accounts,
          ownerDashboard,
        }
      })
    } catch (error) {
      if (version !== refreshVersion.current) return
      setApiConnected(false)
      if (error.status === 401) {
        setActor(null)
        setDb(emptyDatabase)
      }
    } finally {
      if (version === refreshVersion.current) setApiLoading(false)
    }
  }

  useEffect(() => {
    refreshFromBackend()
  }, [])

  async function login(id, password = 'AnCu@123') {
    if (IS_PROTOTYPE_MODE) {
      if (!id) {
        setActor(null)
        return null
      }
      const matched = people.find((p) => p.id === id || p.email === id)
      if (!matched) throw new Error('Không tìm thấy tài khoản Prototype.')
      setActor(matched)
      return matched
    }
    if (id) {
      return loginWithCredentials(id, password)
    } else {
      try {
        await backendApi.logout()
        ++refreshVersion.current
        setActor(null)
        setDb(emptyDatabase)
        await refreshFromBackend()
      } catch (problem) {
        setToast(problem.message || 'Chưa thể đăng xuất. Vui lòng kiểm tra kết nối và thử lại.')
      }
    }
  }

  async function loginWithCredentials(identifier, password) {
    const cleanId = String(identifier || '').trim()
    const cleanPass = String(password || '')
    if (!cleanId || !cleanPass) {
      throw new Error('Vui lòng nhập email/mã tài khoản và mật khẩu.')
    }
    if (IS_PROTOTYPE_MODE) {
      return login(cleanId, cleanPass)
    }
    const authUser = await backendApi.login(cleanId, cleanPass)
    setActor(normalizeAccount(authUser))
    await refreshFromBackend()
    return normalizeAccount(authUser)
  }

  async function completeRegistration(challengeId, code) {
    if (IS_PROTOTYPE_MODE) {
      throw new Error('Đăng ký tài khoản OTP không khả dụng trong chế độ Prototype.')
    }
    const user = await backendApi.confirmRegistration({ challengeId, code })
    setActor(normalizeAccount(user))
    await refreshFromBackend()
    return normalizeAccount(user)
  }

  function notify(message) {
    if (message) setToast(message)
  }

  function save(value, message) {
    if (!IS_PROTOTYPE_MODE) {
      throw new Error('Ứng dụng đang chạy ở Application Mode; không được cập nhật dữ liệu cục bộ khi chưa có phản hồi từ máy chủ.')
    }
    setDb(value)
    if (message) setToast(message)
  }

  async function toggleFavorite(id) {
    if (IS_PROTOTYPE_MODE) {
      const key = actor?.id ?? 'guest'
      const list = db.favorites[key] ?? []
      const saved = list.includes(id)
      setDb((prev) => ({
        ...prev,
        favorites: {
          ...prev.favorites,
          [key]: saved ? list.filter((item) => item !== id) : [...list, id],
        },
      }))
      setToast(saved ? 'Đã bỏ lưu căn hộ (Prototype).' : 'Đã lưu căn hộ (Prototype).')
      return
    }

    if (!actor) {
      const list = db.favorites.guest ?? []
      const saved = list.includes(id)
      setDb((prev) => ({
        ...prev,
        favorites: {
          ...prev.favorites,
          guest: saved ? list.filter((item) => item !== id) : [...list, id],
        },
      }))
      setToast(saved ? 'Đã bỏ lưu tạm trên trình duyệt.' : 'Đã lưu tạm trên trình duyệt (đăng nhập để đồng bộ tài khoản).')
      return
    }

    // P1-14: Application Mode — never fake success if API fails
    try {
      const result = await backendApi.toggleFavorite(id)
      setDb((prev) => ({
        ...prev,
        favorites: {
          ...prev.favorites,
          [actor.id]: result.favoriteIds ?? prev.favorites[actor.id] ?? [],
        },
      }))
      setToast(result.saved ? 'Đã lưu căn hộ.' : 'Đã bỏ lưu căn hộ.')
      await refreshFromBackend()
    } catch (problem) {
      if (problem.isNetworkError) setApiConnected(false)
      setToast(problem.message || 'Không thể cập nhật danh sách căn hộ đã lưu.')
    }
  }

  return <Context.Provider value={{
    db, actor, login, loginWithCredentials, completeRegistration, save, notify, toggleFavorite,
    favorites: db.favorites[actor?.id ?? 'guest'] ?? [],
    apiConnected, apiLoading, isPrototypeMode: IS_PROTOTYPE_MODE, databaseEngine,
    markOffline: () => setApiConnected(false),
    refreshFromBackend, refreshFromApi: refreshFromBackend,
  }}>
    {IS_PROTOTYPE_MODE && (
      <div className="api-offline-banner" role="status">
        <div className="frame api-offline-inner">
          <span>
            <AlertTriangle size={17} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />
            <b>Chế độ Prototype (Dữ liệu mô phỏng cục bộ)</b> — Không kết nối Backend Java và không lưu vào cơ sở dữ liệu thật.
          </span>
        </div>
      </div>
    )}
    {!IS_PROTOTYPE_MODE && !apiConnected && !apiLoading && (
      <div className="api-offline-banner" role="alert">
        <div className="frame api-offline-inner">
          <span>
            <AlertTriangle size={17} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />
            <b>Chưa kết nối tới máy chủ Java Spring Boot (<code>/api</code>)</b> — Các thao tác ghi dữ liệu sẽ báo lỗi thật và không tự mô phỏng thành công. Hãy khởi chạy Backend Java.
          </span>
          <button type="button" className="button small outline" onClick={() => refreshFromBackend()}>
            <RefreshCw size={14} /> Kết nối lại
          </button>
        </div>
      </div>
    )}
    {children}
    <div className="toast-region" role="status">{toast && <div className="toast"><Check size={19} />{toast}</div>}</div>
  </Context.Provider>
}

export const useDemo = () => useContext(Context)

export function ScrollToTop() {
  const { pathname } = useLocation()
  useEffect(() => { window.scrollTo(0, 0) }, [pathname])
  return null
}

export function Logo() {
  return <Link to="/" className="logo" aria-label="An Cư – trang chủ">
    <span className="logo-mark"><House size={21} strokeWidth={1.65} /></span><span>An Cư<span className="logo-dot">.</span></span>
  </Link>
}

export function PublicLayout() {
  const { actor, favorites } = useDemo()
  const [menu, setMenu] = useState(false)
  const location = useLocation()
  useEffect(() => setMenu(false), [location.key])
  return <>
    <header className="site-header">
      <div className="frame header-inner">
        <Logo />
        <nav className={menu ? 'public-nav open' : 'public-nav'} aria-label="Điều hướng chính">
          <NavLink to="/" end>Khám phá</NavLink>
          <NavLink to="/huong-dan">Hướng dẫn thuê</NavLink>
        </nav>
        <div className="header-actions">
          <NavLink to="/da-luu" className="icon-btn saved-link" aria-label="Căn hộ đã lưu"><Heart size={20} />{favorites.length > 0 && <span className="count">{favorites.length}</span>}</NavLink>
          <Link className="button primary header-login" to={actor ? `/${actor.role}` : '/dang-nhap'}>{actor ? (actor.role === 'owner' ? 'Trang chủ nhà' : 'Hợp đồng của tôi') : 'Đăng nhập'}</Link>
          <button type="button" className="icon-btn mobile-menu" aria-expanded={menu} aria-label="Menu" onClick={() => setMenu(!menu)}>{menu ? <X /> : <Menu />}</button>
        </div>
      </div>
    </header>
    <main id="main"><Outlet /></main>
    <footer className="footer"><div className="frame footer-inner"><Logo /><p>Hệ thống Quản lý & Cho thuê Căn hộ TP. Hồ Chí Minh (Java Spring Boot & React)</p><Link to="/huong-dan"><CircleHelp size={17} /> Hướng dẫn thuê</Link></div></footer>
  </>
}

export function RoleLayout({ role }) {
  const { actor, db, login, apiConnected, apiLoading, isPrototypeMode, databaseEngine } = useDemo()
  const navigate = useNavigate()
  const location = useLocation()
  if (apiLoading && !actor) return <section className="frame inner-page" role="status">Đang tải tài khoản…</section>
  if (actor?.role !== role) return <Navigate to="/dang-nhap" state={{ from: location.pathname, role }} replace />
  const count = role === 'tenant'
    ? db.contracts.filter((item) => item.tenantId === actor.id && !item.renewal && renewalOpen(item, dateAt())).length
    : (db.ownerDashboard?.notificationCount ?? db.contracts.filter((item) => item.ownerId === actor.id && item.renewal === 'requested' && !db.contracts.some((other) => other.renewalOf === item.id && other.status !== 'cancelled')).length)
  const items = role === 'owner' ? [
    ['/owner', 'Tổng quan', LayoutDashboard],
    ['/owner/can-ho', 'Tòa nhà & căn hộ', Building2],
    ['/owner/tin-dang', 'Tin đăng', Newspaper],
    ['/owner/hop-dong', 'Hợp đồng', FileText],
    ['/owner/khach-thue', 'Khách thuê', Users],
    ['/owner/hoa-don', 'Hóa đơn', Receipt],
  ] : [
    ['/tenant', 'Hợp đồng của tôi', FileText],
    ['/tenant/hoa-don', 'Hóa đơn', Receipt],
  ]
  items.push([`/${role}/ho-so`, 'Hồ sơ & bảo mật', UserRound])
  const statusLabel = isPrototypeMode
    ? 'Prototype Mode (Mô phỏng)'
    : apiConnected
      ? `Java Spring Boot API${databaseEngine ? ` · ${databaseEngine}` : ''}`
      : 'Mất kết nối Backend Java'
  return <div className="workspace">
    <aside className="sidebar">
      <Logo />
      <p className="sidebar-label">{role === 'owner' ? 'QUẢN LÝ CHO THUÊ' : 'KHU VỰC KHÁCH THUÊ'}</p>
      <nav aria-label="Điều hướng tài khoản">{items.map(([path, label, Icon], index) => <NavLink key={path} to={path} end={index === 0}><Icon size={19} />{label}</NavLink>)}</nav>
      <Link to="/" className="sidebar-home"><ArrowLeft size={17} /> Khám phá căn hộ</Link>
    </aside>
    <div className="workspace-main">
      <header className="workspace-header">
        <div className="workspace-user">
          <span className={`status ${isPrototypeMode ? 'amber' : apiConnected ? 'green' : 'amber'}`}>{statusLabel}</span>
          <Link className="icon-btn bell" aria-label={`${count} thông báo cần xem`} to={role === 'owner' ? '/owner/khach-thue' : '/tenant'}><Bell size={21} />{count > 0 && <span className="count">{count}</span>}</Link>
          <Link className="profile-shortcut" to={`/${role}/ho-so`}><span className="avatar">{actor.name.split(' ').at(-1).slice(0, 1)}</span><b>{actor.name}</b></Link>
          <button className="icon-btn" title="Đăng xuất" aria-label="Đăng xuất" onClick={() => { flushSync(() => navigate('/', { replace: true })); login(null) }}><LogOut size={18} /></button>
        </div>
      </header>
      <main className="workspace-body" id="main"><Outlet /></main>
    </div>
  </div>
}

export function Modal({ title, close, children }) {
  const dialog = useRef(null)
  useEffect(() => {
    const element = dialog.current
    const trigger = document.activeElement
    element.showModal()
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      element.close()
      document.body.style.overflow = previous
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])
  return <dialog ref={dialog} className="modal" aria-label={title} onCancel={(event) => { event.preventDefault(); close() }}>
      <div className="modal-head"><h2>{title}</h2><button className="icon-btn" aria-label="Đóng" onClick={close}><X size={21} /></button></div>
      {children}
  </dialog>
}

export function Field({ label, children }) {
  return <label className="field"><span>{label}</span>{children}</label>
}

export function Select({ children, ...props }) {
  return <span className="select-wrap"><select {...props}>{children}</select><ChevronDown size={15} aria-hidden="true" /></span>
}

export function Notice({ children, error = false }) {
  return <div className={`notice ${error ? 'error' : ''}`} role={error ? 'alert' : undefined}>{children}</div>
}

export function Status({ children, tone = 'neutral' }) {
  return <span className={`status ${tone}`}>{children}</span>
}

export function Empty({ title, children }) {
  return <div className="empty"><House size={29} /><h3>{title}</h3>{children}</div>
}
