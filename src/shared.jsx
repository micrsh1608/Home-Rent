import { createContext, useContext, useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, Bell, Building2, Check, ChevronDown, CircleHelp, FileText, Heart, House, LayoutDashboard, LogOut, Menu, Moon, Newspaper, Receipt, ShieldCheck, Sun, Users, X } from 'lucide-react'
import { backendApi, dateAt, identity, loadDemo, renewalOpen, storageKey } from './demo'

const Context = createContext(null)

export function DemoProvider({ children }) {
  const [db, setDb] = useState(loadDemo)
  const [actorId, setActorId] = useState(() => sessionStorage.getItem('an-cu-actor'))
  const [toast, setToast] = useState('')
  const [apiConnected, setApiConnected] = useState(false)
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem('an-cu-theme') || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    } catch {
      return 'light'
    }
  })
  const actor = identity(actorId)

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    try {
      localStorage.setItem('an-cu-theme', theme)
    } catch { /* ignore */ }
    const metaTheme = document.querySelector('meta[name="theme-color"]')
    if (metaTheme) {
      metaTheme.setAttribute('content', theme === 'dark' ? '#0f1412' : '#f8f7f3')
    }
  }, [theme])

  function toggleTheme() {
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'))
  }

  useEffect(() => {
    try { localStorage.setItem(storageKey, JSON.stringify(db)) } catch { /* Keep the in-memory session. */ }
  }, [db])

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(''), 3600)
    return () => clearTimeout(timer)
  }, [toast])

  async function refreshFromBackend(currentActorId = actorId) {
    try {
      await backendApi.checkHealth()
      setApiConnected(true)
      const publicData = await backendApi.listPublicApartments('size=100')
      let mergedBuildings = db.buildings
      let mergedApartments = db.apartments
      let mergedContracts = db.contracts
      let mergedInvoices = db.invoices
      let mergedFavorites = { ...db.favorites }

      if (publicData?.items) {
        const existingUnitsById = new Map(mergedApartments.map((u) => [u.id, u]))
        const publicById = new Map(publicData.items.map((item) => {
          const prevUnit = existingUnitsById.get(item.id)
          return [item.id, {
            ...prevUnit,
            ...item,
            area: Number(item.area),
          }]
        }))
        mergedApartments = mergedApartments.map((unit) => publicById.get(unit.id) ?? unit)
        for (const item of publicData.items) {
          if (!mergedApartments.some((u) => u.id === item.id)) {
            mergedApartments.push({ ...item, area: Number(item.area) })
          }
        }
      }

      if (currentActorId) {
        const currentRole = identity(currentActorId)?.role
        await backendApi.login(currentActorId, 'AnCu@123')
        if (currentRole === 'owner') {
          const [ownerBuildings, ownerUnits] = await Promise.all([
            backendApi.listOwnerBuildings(),
            backendApi.listOwnerApartments(),
          ])
          const existingBuildingsById = new Map(mergedBuildings.map((b) => [b.id, b]))
          const otherBuildings = mergedBuildings.filter((b) => b.ownerId !== currentActorId)
          mergedBuildings = [
            ...otherBuildings,
            ...ownerBuildings.map((b) => ({ ...existingBuildingsById.get(b.id), ...b })),
          ]
          const existingUnitsById = new Map(mergedApartments.map((u) => [u.id, u]))
          const ownerUnitById = new Map(ownerUnits.map((u) => [u.id, { ...existingUnitsById.get(u.id), ...u, area: Number(u.area) }]))
          mergedApartments = mergedApartments.map((u) => ownerUnitById.get(u.id) ?? u)
          for (const u of ownerUnits) {
            if (!mergedApartments.some((item) => item.id === u.id)) {
              mergedApartments.push({ ...u, area: Number(u.area) })
            }
          }
        }
        const [contracts, invoices, favs] = await Promise.all([
          backendApi.listContracts(),
          backendApi.listInvoices(),
          backendApi.listFavorites(),
        ])
        const normalizedContracts = contracts.map((c) => ({
          ...c,
          appointment: c.appointment ? String(c.appointment).slice(0, 16) : '',
          actualMoveIn: c.actualMoveIn ?? '',
          actualMoveOut: c.actualMoveOut ?? '',
        }))
        const byContractId = new Map(normalizedContracts.map((c) => [c.id, c]))
        mergedContracts = [
          ...mergedContracts.filter((c) => !byContractId.has(c.id)),
          ...normalizedContracts,
        ]
        const byInvoiceId = new Map(invoices.map((i) => [i.id, i]))
        mergedInvoices = [
          ...mergedInvoices.filter((i) => !byInvoiceId.has(i.id)),
          ...invoices,
        ]
        if (favs?.favoriteIds) {
          mergedFavorites[currentActorId] = favs.favoriteIds
        }
      }

      setDb((prev) => ({
        ...prev,
        buildings: mergedBuildings,
        apartments: mergedApartments,
        contracts: mergedContracts,
        invoices: mergedInvoices,
        favorites: mergedFavorites,
      }))
    } catch {
      setApiConnected(false)
    }
  }

  useEffect(() => {
    refreshFromBackend(actorId)
  }, [actorId])

  function login(id) {
    setActorId(id)
    if (id) {
      sessionStorage.setItem('an-cu-actor', id)
      backendApi.login(id, 'AnCu@123').catch(() => {})
    } else {
      sessionStorage.removeItem('an-cu-actor')
      backendApi.logout().catch(() => {})
    }
  }

  function save(value, message) {
    setDb(value)
    if (message) setToast(message)
  }

  function toggleFavorite(id) {
    const key = actor?.id ?? 'guest'
    const list = db.favorites[key] ?? []
    const saved = list.includes(id)
    save(
      { ...db, favorites: { ...db.favorites, [key]: saved ? list.filter((item) => item !== id) : [...list, id] } },
      saved ? 'Đã bỏ lưu căn hộ.' : 'Đã lưu căn hộ.',
    )
    if (actor && apiConnected) {
      backendApi.toggleFavorite(id).then(() => refreshFromBackend(actor.id)).catch(() => {})
    }
  }

  return <Context.Provider value={{
    db, actor, login, save, toggleFavorite,
    favorites: db.favorites[actor?.id ?? 'guest'] ?? [],
    apiConnected, refreshFromBackend, refreshFromApi: refreshFromBackend,
    theme, toggleTheme,
  }}>
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
  return <Link to="/" className="logo" aria-label="An Cư – trang chủ" title="An Cư">
    <span className="logo-mark" aria-hidden="true">
      <svg className="logo-svg" viewBox="0 0 44 44" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="ancu-bg" x1="4" y1="2" x2="40" y2="42" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor="#2d5647" />
            <stop offset="100%" stopColor="#193329" />
          </linearGradient>
          <linearGradient id="ancu-gold" x1="14" y1="14" x2="30" y2="36" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor="#f0d5a8" />
            <stop offset="100%" stopColor="#b58652" />
          </linearGradient>
        </defs>
        {/* Outer Sanctuary Badge */}
        <rect x="1.5" y="1.5" width="41" height="41" rx="12.5" fill="url(#ancu-bg)" stroke="#ead3a9" strokeOpacity="0.28" strokeWidth="1.2" />
        {/* Subtle Architectural Halo */}
        <circle cx="22" cy="21" r="14.5" stroke="#ead3a9" strokeOpacity="0.12" strokeWidth="1" />
        {/* Left High-Rise Wing & Sanctuary Roof Apex (Letter A silhouette - "Cư") */}
        <path
          d="M9.5 33.5V18.2L22 7.5L26.2 11.1V15.4L22 11.8L14.2 18.5V33.5H9.5Z"
          fill="#fffef7"
        />
        {/* Right Stepped Urban Tower Wing (3D depth & modern apartment skyline) */}
        <path
          d="M22 7.5L34.5 18.2V33.5H29.8V18.5L22 11.8V7.5Z"
          fill="#dce7dc"
        />
        {/* Secondary Modern Tower Accent */}
        <path
          d="M31.2 13.2H34.5V33.5H31.2V13.2Z"
          fill="#fffef7"
          fillOpacity="0.88"
        />
        {/* Sanctuary Archway Portal (Warmth & Peace - "An") */}
        <path
          d="M16.2 33.5V23.2C16.2 20.05 18.8 17.5 22 17.5C25.2 17.5 27.8 20.05 27.8 23.2V33.5"
          stroke="url(#ancu-gold)"
          strokeWidth="2.2"
          strokeLinecap="round"
        />
        {/* Inner Arch Warm Glow */}
        <path
          d="M18.3 33.5V23.4C18.3 21.35 19.95 19.7 22 19.7C24.05 19.7 25.7 21.35 25.7 23.4V33.5H18.3Z"
          fill="url(#ancu-gold)"
          fillOpacity="0.18"
        />
        {/* Warm Hearth / Lit Window Orb at Heart of the Arch */}
        <circle cx="22" cy="23.6" r="2.55" fill="url(#ancu-gold)" />
        {/* Grounded Architectural Plinth ("An cư lạc nghiệp") */}
        <path
          d="M7.5 33.8H36.5"
          stroke="url(#ancu-gold)"
          strokeWidth="2"
          strokeLinecap="round"
        />
      </svg>
    </span>
  </Link>
}

export function ThemeToggle({ className = '' }) {
  const { theme, toggleTheme } = useDemo()
  const isDark = theme === 'dark'
  return (
    <button
      type="button"
      className={`icon-btn theme-toggle ${className}`}
      onClick={toggleTheme}
      title={isDark ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối (Dark mode)'}
      aria-label={isDark ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối (Dark mode)'}
    >
      {isDark ? <Sun size={20} /> : <Moon size={20} />}
    </button>
  )
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
          <ThemeToggle />
          <NavLink to="/da-luu" className="icon-btn saved-link" aria-label="Căn hộ đã lưu"><Heart size={20} />{favorites.length > 0 && <span className="count">{favorites.length}</span>}</NavLink>
          <Link className="button primary header-login" to={actor ? `/${actor.role}` : '/dang-nhap'}>{actor ? (actor.role === 'owner' ? 'Trang chủ nhà' : 'Hợp đồng của tôi') : 'Đăng nhập'}</Link>
          <button type="button" className="icon-btn mobile-menu" aria-expanded={menu} aria-label="Menu" onClick={() => setMenu(!menu)}>{menu ? <X /> : <Menu />}</button>
        </div>
      </div>
    </header>
    <main id="main"><Outlet /></main>
    <footer className="footer"><div className="frame footer-inner"><Logo /><p>Tìm và thuê căn hộ tại TP. Hồ Chí Minh</p><Link to="/huong-dan"><CircleHelp size={17} /> Hướng dẫn thuê</Link></div></footer>
  </>
}

export function RoleLayout({ role }) {
  const { actor, db, login, apiConnected } = useDemo()
  const navigate = useNavigate()
  const location = useLocation()
  if (actor?.role !== role) return <Navigate to="/dang-nhap" state={{ from: location.pathname, role }} replace />
  const count = role === 'tenant'
    ? db.contracts.filter((item) => item.tenantId === actor.id && !item.renewal && renewalOpen(item, dateAt())).length
    : db.contracts.filter((item) => item.ownerId === actor.id && item.renewal === 'requested' && !db.contracts.some((other) => other.renewalOf === item.id && other.status !== 'cancelled')).length
  const items = role === 'owner' ? [
    ['/owner', 'Tổng quan', LayoutDashboard],
    ['/owner/can-ho', 'Tòa nhà & căn hộ', Building2],
    ['/owner/tin-dang', 'Tin đăng', Newspaper],
    ['/owner/hop-dong', 'Hợp đồng', FileText],
    ['/owner/khach-thue', 'Khách thuê', Users],
    ['/owner/hoa-don', 'Hóa đơn', Receipt],
    ['/owner/kiem-toan-rbac', 'Kiểm toán & RBAC', ShieldCheck],
  ] : [
    ['/tenant', 'Hợp đồng của tôi', FileText],
    ['/tenant/hoa-don', 'Hóa đơn', Receipt],
  ]
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
          <span className="demo-label">{apiConnected ? 'Đã kết nối Backend API' : 'Bản demo'}</span>
          <ThemeToggle />
          <Link className="icon-btn bell" aria-label={`${count} thông báo cần xem`} to={role === 'owner' ? '/owner/khach-thue' : '/tenant'}><Bell size={21} />{count > 0 && <span className="count">{count}</span>}</Link>
          <span className="avatar">{actor.name.split(' ').at(-1).slice(0, 1)}</span><b>{actor.name}</b>
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
