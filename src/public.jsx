import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ArrowDown, ArrowLeft, ArrowRight, Bath, BedDouble, Building2, CalendarDays, ChevronDown, FileCheck2, Heart, KeyRound, MapPin, Maximize, Search, SlidersHorizontal, UserRound } from 'lucide-react'
import { addContract, dateAt, identity, money, people, plusDays, unitImage, unitStatus } from './demo'
import { Empty, Field, Modal, Notice, Select, Status, useDemo } from './shared'
import { BudgetFilter, DistrictFilter, formatBudget } from './SearchFilters'

const defaults = { districts: [], minPrice: 0, maxPrice: null, beds: '', query: '', area: '' }
const bedroomOptions = [['', 'Bất kỳ'], ...[1, 2, 3].map((count) => [String(count), `${count} phòng ngủ`])]
const normalize = (text) => text.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '').replace(/đ/g, 'd')

function ApartmentCard({ unit }) {
  const { db, favorites, toggleFavorite } = useDemo()
  const property = db.buildings.find((item) => item.id === unit.buildingId)
  const saved = favorites.includes(unit.id)
  return <article className="apartment-card">
    <div className="card-image">
      <Link to={`/can-ho/${unit.id}`} aria-label={unit.title}><img src={unitImage(unit)} alt={`Phòng khách của ${unit.title}`} /></Link>
      <Status tone="green">Còn trống</Status>
      <button className={`favorite ${saved ? 'is-saved' : ''}`} aria-label={saved ? 'Bỏ lưu căn hộ' : 'Lưu căn hộ'} aria-pressed={saved} onClick={() => toggleFavorite(unit.id)}><Heart size={20} /></button>
    </div>
    <div className="card-content">
      <div className="card-location"><MapPin size={15} /> {property.district}, TP. Hồ Chí Minh</div>
      <h3><Link to={`/can-ho/${unit.id}`}>{unit.title}</Link></h3>
      <div className="features"><span><BedDouble /> {unit.beds} PN</span><span><Bath /> {unit.baths} WC</span><span><Maximize /> {unit.area} m²</span></div>
      <div className="card-footer"><div><strong>{money(unit.price)}</strong><span> / tháng</span></div><Link to={`/can-ho/${unit.id}`} className="icon-btn detail-arrow" aria-label={`Xem ${unit.title}`}><ArrowUpRight /></Link></div>
    </div>
  </article>
}

function ArrowUpRight() {
  return <ArrowRight className="tilt" size={19} />
}

function Results({ units }) {
  return units.length ? <div className="apartment-grid">{units.map((unit) => <ApartmentCard unit={unit} key={unit.id} />)}</div> :
    <Empty title="Chưa tìm thấy căn hộ"><p>Thử thay đổi khu vực hoặc khoảng giá.</p></Empty>
}

export function Home() {
  const { db } = useDemo()
  const [draft, setDraft] = useState(defaults)
  const [filters, setFilters] = useState(defaults)
  const [sort, setSort] = useState('featured')
  const [advanced, setAdvanced] = useState(false)
  const [openFilter, setOpenFilter] = useState(null)
  const options = useRef(null)
  useEffect(() => {
    if (openFilter) {
      const input = options.current?.querySelector('input:checked') ?? options.current?.querySelector('input')
      input?.focus({ preventScroll: true })
    }
  }, [openFilter])
  const buildingsById = new Map(db.buildings.map((building) => [building.id, building]))
  const districts = [...new Set(db.buildings.map((property) => property.district))]
  const visible = db.apartments.filter((unit) => unit.published && unitStatus(db, unit) === 'ready')
  const districtCounts = new Map()
  let budgetCeiling = 24000000
  for (const unit of visible) {
    const district = buildingsById.get(unit.buildingId).district
    districtCounts.set(district, (districtCounts.get(district) ?? 0) + 1)
    budgetCeiling = Math.max(budgetCeiling, Math.ceil(unit.price / 1000000) * 1000000)
  }
  const draftMax = draft.maxPrice ?? budgetCeiling
  const budgetSummary = draft.minPrice === 0 && draft.maxPrice === null
    ? 'Mọi mức giá'
    : `${formatBudget(draft.minPrice)} – ${formatBudget(draftMax)} ₫`
  const districtSummary = draft.districts.length < 2
    ? draft.districts[0] ?? 'Tất cả khu vực'
    : `${draft.districts.length} khu vực đã chọn`
  const searchFields = [
    { key: 'districts', label: 'Khu vực', icon: MapPin, summary: districtSummary },
    { key: 'budget', label: 'Ngân sách', icon: KeyRound, summary: budgetSummary },
    { key: 'beds', label: 'Phòng ngủ', icon: BedDouble, summary: bedroomOptions.find(([value]) => value === draft.beds)[1] },
  ]
  const activeField = searchFields.find(({ key }) => key === openFilter)
  const results = visible.filter((unit) => {
    const property = buildingsById.get(unit.buildingId)
    return (!filters.districts.length || filters.districts.includes(property.district)) &&
      unit.price >= filters.minPrice && (filters.maxPrice === null || unit.price <= filters.maxPrice) &&
      (!filters.beds || unit.beds === Number(filters.beds)) &&
      (!filters.area || unit.area >= Number(filters.area)) &&
      (!filters.query || normalize(`${unit.title} ${property.name} ${property.address}`).includes(normalize(filters.query.trim())))
  })
  if (sort === 'ascending') results.sort((a, b) => a.price - b.price)
  if (sort === 'descending') results.sort((a, b) => b.price - a.price)
  if (sort === 'area') results.sort((a, b) => b.area - a.area)
  const filtered = Boolean(filters.districts.length || filters.minPrice || filters.maxPrice !== null || filters.beds || filters.query || filters.area)
  const set = (key) => (event) => {
    const { value } = event.target
    setDraft((previous) => ({ ...previous, [key]: value }))
  }
  function toggleDistrict(district) {
    setDraft((previous) => ({ ...previous, districts: previous.districts.includes(district)
      ? previous.districts.filter((name) => name !== district)
      : [...previous.districts, district] }))
  }
  function setMinPrice(value) {
    setDraft((previous) => ({ ...previous, minPrice: Math.min(value, previous.maxPrice ?? budgetCeiling) }))
  }
  function setMaxPrice(value) {
    setDraft((previous) => ({ ...previous, maxPrice: value >= budgetCeiling ? null : Math.max(value, previous.minPrice) }))
  }
  function search(event) {
    event.preventDefault()
    setOpenFilter(null)
    setFilters(draft)
    document.getElementById('can-ho').scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
  return <>
    <section className="frame landing">
      <div className="hero">
        <div className="hero-copy">
          <div className="hero-kicker"><span /> CĂN HỘ CHO THUÊ · TP. HỒ CHÍ MINH</div>
          <h1>Tìm một nơi<br /> để <em>an cư.</em></h1>
          <p>Khám phá căn hộ phù hợp với ngân sách và nhịp sống của bạn.</p>
          <a href="#can-ho" className="hero-jump" onClick={(event) => { event.preventDefault(); document.getElementById('can-ho').scrollIntoView({ behavior: 'smooth' }) }}><ArrowDown size={17} /> Khám phá căn hộ</a>
        </div>
      </div>
      <form className="searchbar" onSubmit={search} onKeyDown={(event) => {
        if (event.key === 'Escape' && activeField) {
          event.currentTarget.querySelector('.search-field[aria-expanded="true"]')?.focus()
          setOpenFilter(null)
        }
      }}>
        {searchFields.map(({ key, label, icon: Icon, summary }) => <button
          type="button" className="search-field" key={key} aria-expanded={openFilter === key}
          aria-controls={openFilter === key ? 'search-options' : undefined}
          onClick={() => setOpenFilter((previous) => previous === key ? null : key)}
        >
          <Icon size={20} aria-hidden="true" />
          <span className="search-field-text"><strong>{label}</strong><span>{summary}</span></span>
          <ChevronDown size={16} className="search-chevron" aria-hidden="true" />
        </button>)}
        <button className="button primary search-button" type="submit"><Search size={18} /> Tìm căn hộ</button>
        {activeField && <fieldset id="search-options" key={activeField.key} ref={options} className="search-options">
          <legend>{activeField.label}</legend>
          {openFilter === 'districts' && <DistrictFilter districts={districts} counts={districtCounts} selected={draft.districts} onToggle={toggleDistrict} />}
          {openFilter === 'budget' && <BudgetFilter
            minimum={draft.minPrice} maximum={draftMax} ceiling={budgetCeiling}
            onMinimum={setMinPrice} onMaximum={setMaxPrice}
            onPreset={(minPrice, maxPrice) => setDraft((previous) => ({ ...previous, minPrice, maxPrice }))}
          />}
          {openFilter === 'beds' && <div className="search-choice-list">{bedroomOptions.map(([value, label]) => <label className="search-choice" key={value}>
            <input type="radio" name="beds" value={value} checked={draft.beds === value} onChange={set('beds')} />
            <span>{label}</span>
          </label>)}</div>}
        </fieldset>}
        {advanced && <div className="advanced form-grid"><Field label="Tên tòa nhà hoặc địa chỉ"><input placeholder="Ví dụ: Thảo Điền" value={draft.query} onChange={set('query')} /></Field><Field label="Diện tích tối thiểu (m²)"><input type="number" min="0" value={draft.area} onChange={set('area')} /></Field></div>}
      </form>
    </section>
    <section id="can-ho" className="frame results">
      <div className="section-title">
        <div><h2>{filtered ? 'Kết quả tìm kiếm' : 'Căn hộ nổi bật'}</h2><p aria-live="polite">{results.length} căn hộ đang cho thuê</p></div>
        <div className="list-tools">
          {filtered && <button className="button ghost" onClick={() => { setDraft(defaults); setFilters(defaults); setOpenFilter(null) }}>Bỏ bộ lọc</button>}
          <button className={`button outline ${advanced ? 'selected' : ''}`} aria-expanded={advanced} onClick={() => { setAdvanced(!advanced); if (!advanced) setTimeout(() => document.querySelector('.advanced input')?.focus(), 0) }}><SlidersHorizontal size={16} /> Lọc thêm</button>
          <Select aria-label="Sắp xếp căn hộ" value={sort} onChange={(event) => setSort(event.target.value)}><option value="featured">Nổi bật</option><option value="ascending">Giá thấp trước</option><option value="descending">Giá cao trước</option><option value="area">Diện tích lớn trước</option></Select>
        </div>
      </div>
      <Results units={results} />
    </section>
  </>
}

export function Saved() {
  const { db, favorites } = useDemo()
  const units = db.apartments.filter((unit) => favorites.includes(unit.id) && unit.published && unitStatus(db, unit) === 'ready')
  return <section className="frame inner-page"><Link className="back" to="/"><ArrowLeft size={17} /> Khám phá</Link><h1>Căn hộ đã lưu</h1>
    {units.length ? <Results units={units} /> : <Empty title="Bạn chưa lưu căn hộ nào"><Link className="button primary" to="/">Tìm căn hộ</Link></Empty>}
  </section>
}

export function Guide() {
  const steps = [
    [Search, 'Chọn căn hộ', 'Lọc theo khu vực, ngân sách và số phòng ngủ. Mở từng tin để xem chi phí và thông tin chủ nhà.'],
    [CalendarDays, 'Đặt lịch ký', 'Chọn ngày và giờ ký thuận tiện. Địa điểm ký được ghi sẵn trong hợp đồng.'],
    [FileCheck2, 'Đọc và ký hợp đồng', 'Kiểm tra thời hạn, tiền thuê, phí và điều khoản bàn giao trước khi xác nhận ký.'],
    [KeyRound, 'Theo dõi kỳ thuê', 'Xem hóa đơn hàng tháng và phản hồi yêu cầu gia hạn khi hợp đồng sắp hết hạn.'],
  ]
  return <section className="frame inner-page"><Link className="back" to="/"><ArrowLeft size={17} /> Khám phá</Link><h1>Hướng dẫn thuê căn hộ</h1><div className="guide-grid">
    {steps.map(([Icon, title, body]) => <article className="panel" key={title}><div className="step-icon"><Icon size={25} /></div><h2>{title}</h2><p>{body}</p></article>)}
  </div><Link className="button primary" to="/">Tìm căn hộ <ArrowRight size={17} /></Link></section>
}

export function Login() {
  const location = useLocation()
  const navigate = useNavigate()
  const { login } = useDemo()
  const [role, setRole] = useState(location.state?.role ?? 'tenant')
  function choose(id) {
    login(id)
    navigate(location.state?.role === role ? location.state?.from ?? `/${role}` : `/${role}`, { replace: true })
  }
  return <section className="frame login-page">
    <div className="login-image" />
    <div className="login-content">
      <Link className="back" to="/"><ArrowLeft size={17} /> Khám phá</Link>
      <h1>Đăng nhập</h1><p>Chọn tài khoản mẫu để trải nghiệm chức năng của từng vai trò.</p>
      <div className="tabs" aria-label="Vai trò">{[['tenant', 'Khách thuê', UserRound], ['owner', 'Chủ nhà', Building2]].map(([key, label, Icon]) => <button className={role === key ? 'current' : ''} aria-pressed={role === key} key={key} onClick={() => setRole(key)}><Icon size={17} /> {label}</button>)}</div>
      <div className="accounts">{people.filter((person) => person.role === role).map((person) => <button key={person.id} onClick={() => choose(person.id)}><span className="avatar">{person.name.split(' ').at(-1)[0]}</span><span>{person.name}</span><ArrowRight size={18} /></button>)}</div>
    </div>
  </section>
}

export function ApartmentDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { db, actor, favorites, toggleFavorite, save } = useDemo()
  const [request, setRequest] = useState(false)
  const [error, setError] = useState('')
  const unit = db.apartments.find((item) => item.id === id && item.published && unitStatus(db, item) === 'ready')
  if (!unit) return <section className="frame inner-page"><Empty title="Căn hộ này hiện không mở cho thuê"><Link className="button primary" to="/">Xem căn hộ khác</Link></Empty></section>
  const property = db.buildings.find((item) => item.id === unit.buildingId)
  const owner = identity(property.ownerId)
  const saved = favorites.includes(id)
  function begin() {
    if (!actor) navigate('/dang-nhap', { state: { role: 'tenant', from: `/can-ho/${id}` } })
    else { setError(''); setRequest(true) }
  }
  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    try {
      const result = addContract(db, actor, unit, { startDate: form.get('startDate'), endDate: form.get('endDate'), rent: unit.price })
      save(result.db, 'Đã tạo hợp đồng chờ ký.')
      navigate(`/tenant/hop-dong/${result.contract.id}`)
    } catch (problem) { setError(problem.message) }
  }
  return <section className="frame inner-page">
    <Link className="back" to="/"><ArrowLeft size={17} /> Tất cả căn hộ</Link>
    <h1>{unit.title}</h1><p className="detail-address"><MapPin size={18} /> {property.address}</p>
    <div className="detail-grid">
      <div><img className="detail-image" src={unitImage(unit)} alt={unit.title} /><article className="panel description"><h2>Thông tin căn hộ</h2><p>{unit.description}</p><div className="features"><span><BedDouble /> {unit.beds} phòng ngủ</span><span><Bath /> {unit.baths} phòng tắm</span><span><Maximize /> {unit.area} m²</span></div></article></div>
      <aside className="panel booking"><Status tone="green">Còn trống</Status><div className="booking-price"><strong>{money(unit.price)}</strong><span> / tháng</span></div>
        <dl className="facts"><div><dt>Chủ nhà</dt><dd>{owner.name}</dd></div><div><dt>Mã căn hộ</dt><dd>{unit.room}</dd></div><div><dt>Tiền điện</dt><dd>{money(property.electricRate)}/kWh</dd></div><div><dt>Tiền nước</dt><dd>{money(property.waterRate)}/m³</dd></div></dl>
        {actor?.role !== 'owner' && <button className="button primary" onClick={begin}>Yêu cầu thuê <ArrowRight size={17} /></button>}
        <button className="button outline" aria-pressed={saved} onClick={() => toggleFavorite(id)}><Heart size={18} fill={saved ? 'currentColor' : 'none'} /> {saved ? 'Đã lưu căn hộ' : 'Lưu căn hộ'}</button>
      </aside>
    </div>
    {request && <Modal title="Yêu cầu thuê căn hộ" close={() => setRequest(false)}><form className="stack" onSubmit={submit}>
      <p>{unit.title} · {money(unit.price)}/tháng</p>
      <div className="form-grid"><Field label="Ngày bắt đầu"><input name="startDate" type="date" min={dateAt()} defaultValue={dateAt(7)} required /></Field><Field label="Ngày kết thúc"><input name="endDate" type="date" min={dateAt()} defaultValue={plusDays(dateAt(7), 179)} required /></Field></div>
      <Notice>Chọn thời hạn để tạo hợp đồng chờ ký. Bạn có thể đọc điều khoản và đặt lịch trong khu vực khách thuê.</Notice>
      {error && <Notice error>{error}</Notice>}<button className="button primary">Xem hợp đồng chờ ký <ArrowRight size={18} /></button>
    </form></Modal>}
  </section>
}
