import { createContext, useContext, useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, Bell, Building2, Check, ChevronDown, CircleHelp, FileText, Heart, House, LayoutDashboard, LogOut, Menu, Newspaper, Receipt, Users, X } from 'lucide-react'
import { dateAt, identity, loadDemo, renewalOpen, storageKey } from './demo'

const Context = createContext(null)

export function DemoProvider({ children }) {
  const [db, setDb] = useState(loadDemo)
  const [actorId, setActorId] = useState(() => sessionStorage.getItem('an-cu-actor'))
  const [toast, setToast] = useState('')
  const actor = identity(actorId)
  useEffect(() => {
    try { localStorage.setItem(storageKey, JSON.stringify(db)) } catch { /* Keep the in-memory session. */ }
  }, [db])
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(''), 3600)
    return () => clearTimeout(timer)
  }, [toast])
  function login(id) {
    setActorId(id)
    if (id) sessionStorage.setItem('an-cu-actor', id)
    else sessionStorage.removeItem('an-cu-actor')
  }
  function save(value, message) {
    setDb(value)
    if (message) setToast(message)
  }
  function toggleFavorite(id) {
    const key = actor?.id ?? 'guest'
    const list = db.favorites[key] ?? []
    const saved = list.includes(id)
    save({ ...db, favorites: { ...db.favorites, [key]: saved ? list.filter((item) => item !== id) : [...list, id] } }, saved ? 'Đã bỏ lưu căn hộ.' : 'Đã lưu căn hộ.')
  }
  return <Context.Provider value={{ db, actor, login, save, toggleFavorite, favorites: db.favorites[actor?.id ?? 'guest'] ?? [] }}>
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
    <footer className="footer"><div className="frame footer-inner"><Logo /><p>Tìm và thuê căn hộ tại TP. Hồ Chí Minh</p><Link to="/huong-dan"><CircleHelp size={17} /> Hướng dẫn thuê</Link></div></footer>
  </>
}

export function RoleLayout({ role }) {
  const { actor, db, login } = useDemo()
  const navigate = useNavigate()
  const location = useLocation()
  if (actor?.role !== role) return <Navigate to="/dang-nhap" state={{ from: location.pathname, role }} replace />
  const count = role === 'tenant'
    ? db.contracts.filter((item) => item.tenantId === actor.id && !item.renewal && renewalOpen(item, dateAt())).length
    : db.contracts.filter((item) => item.ownerId === actor.id && item.renewal === 'requested' && !db.contracts.some((other) => other.renewalOf === item.id)).length
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
          <span className="demo-label">Bản demo</span>
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
