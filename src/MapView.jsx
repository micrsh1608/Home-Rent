import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Bath, BedDouble, Building2, Check, Compass, Crosshair, ExternalLink, Heart, Loader2, LocateFixed, MapPin, Maximize, Minus, Navigation, Plus, Search, Sparkles, TrainFront } from 'lucide-react'
import { money, unitImage, unitStatus } from './demo'
import { Status } from './shared'

const TILE_SIZE = 256
const HCMC_CENTER = { lat: 10.7725, lng: 106.7145 }

export const DISTRICT_COORDS = {
  'TP. Thủ Đức': { lat: 10.8068, lng: 106.7318 },
  'Thủ Đức': { lat: 10.8068, lng: 106.7318 },
  'Thảo Điền': { lat: 10.8068, lng: 106.7318 },
  'Quận 7': { lat: 10.7256, lng: 106.7118 },
  'Bình Thạnh': { lat: 10.7932, lng: 106.7186 },
  'Quận 3': { lat: 10.7781, lng: 106.6912 },
  'Quận 1': { lat: 10.7756, lng: 106.7019 },
  'Quận 2': { lat: 10.7872, lng: 106.7498 },
  'Phú Nhuận': { lat: 10.7992, lng: 106.6803 },
  'Quận 4': { lat: 10.7578, lng: 106.7013 },
  'Quận 5': { lat: 10.7540, lng: 106.6634 },
  'Quận 10': { lat: 10.7746, lng: 106.6678 },
  'Tân Bình': { lat: 10.8015, lng: 106.6526 },
  'Gò Vấp': { lat: 10.8387, lng: 106.6653 },
  'Bình Tân': { lat: 10.7653, lng: 106.6038 },
}

const HCMC_STREET_FALLBACKS = [
  { keywords: ['nguyen van huong', 'thao dien', 'the river'], lat: 10.8068, lng: 106.7318, label: 'Nguyễn Văn Hưởng, Thảo Điền, TP. Thủ Đức' },
  { keywords: ['nguyen duc canh', 'phu my hung', 'lumiere'], lat: 10.7256, lng: 106.7118, label: 'Nguyễn Đức Cảnh, Phú Mỹ Hưng, Quận 7' },
  { keywords: ['nguyen huu canh', 'vinhomes', 'urban nest', 'landmark 81'], lat: 10.7932, lng: 106.7186, label: 'Nguyễn Hữu Cảnh, Phường 22, Bình Thạnh' },
  { keywords: ['vo van tan', 'the greenhouse', 'ho con rua'], lat: 10.7781, lng: 106.6912, label: 'Võ Văn Tần, Phường Võ Thị Sáu, Quận 3' },
  { keywords: ['le thanh ton', 'vincom dong khoi'], lat: 10.7780, lng: 106.7018, label: 'Lê Thánh Tôn, Bến Nghé, Quận 1' },
  { keywords: ['nguyen hue', 'pho di bo'], lat: 10.7744, lng: 106.7035, label: 'Nguyễn Huệ, Bến Nghé, Quận 1' },
  { keywords: ['mai chi tho', 'thu thiem', 'sala'], lat: 10.7728, lng: 106.7235, label: 'Mai Chí Thọ, Khu đô thị Thủ Thiêm, TP. Thủ Đức' },
  { keywords: ['dien bien phu', 'pearl plaza'], lat: 10.7998, lng: 106.7182, label: 'Điện Biên Phủ, Bình Thạnh' },
  { keywords: ['nguyen thi minh khai'], lat: 10.7805, lng: 106.6952, label: 'Nguyễn Thị Minh Khai, Quận 1 / Quận 3' },
  { keywords: ['nguyen van linh', 'crescent mall', 'vivo'], lat: 10.7295, lng: 106.7082, label: 'Nguyễn Văn Linh, Phú Mỹ Hưng, Quận 7' },
  { keywords: ['xa lo ha noi', 'masteri', 'an phu'], lat: 10.8019, lng: 106.7422, label: 'Xa lộ Hà Nội, An Phú, TP. Thủ Đức' },
  { keywords: ['cong hoa', 'tan binh'], lat: 10.8018, lng: 106.6472, label: 'Cộng Hòa, Quận Tân Bình' },
  { keywords: ['phan xich long', 'phu nhuan'], lat: 10.7978, lng: 106.6889, label: 'Phan Xích Long, Quận Phú Nhuận' },
  { keywords: ['ben van don', 'quan 4'], lat: 10.7629, lng: 106.6988, label: 'Bến Vân Đồn, Quận 4' },
]

function normalizeText(value = '') {
  return String(value)
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .replace(/đ/g, 'd')
    .trim()
}

export async function geocodeAddressInHCMC(address, district = '') {
  const cleanAddress = String(address || '').trim()
  const cleanDistrict = String(district || '').trim()
  const combined = [cleanAddress, cleanDistrict, 'TP. Hồ Chí Minh, Việt Nam'].filter(Boolean).join(', ')
  const results = []

  try {
    const params = new URLSearchParams({
      format: 'json',
      addressdetails: '1',
      limit: '5',
      countrycodes: 'vn',
      viewbox: '106.50,10.92,106.88,10.65',
      bounded: '0',
      q: combined,
    })
    const response = await fetch(`https://nominatim.openstreetmap.org/search?${params.toString()}`, {
      headers: { 'Accept-Language': 'vi' },
    })
    if (response.ok) {
      const data = await response.json()
      for (const item of data) {
        const lat = Number(item.lat)
        const lng = Number(item.lon)
        if (Number.isFinite(lat) && Number.isFinite(lng)) {
          results.push({
            lat: Number(lat.toFixed(5)),
            lng: Number(lng.toFixed(5)),
            label: item.display_name,
            district: item.address?.suburb || item.address?.city_district || item.address?.county || cleanDistrict,
          })
        }
      }
    }
  } catch {
    // Fallback to local HCMC street database if offline
  }

  const normQuery = normalizeText(`${cleanAddress} ${cleanDistrict}`)
  for (const item of HCMC_STREET_FALLBACKS) {
    if (item.keywords.some((kw) => normQuery.includes(kw))) {
      const exists = results.some((r) => Math.abs(r.lat - item.lat) < 0.002 && Math.abs(r.lng - item.lng) < 0.002)
      if (!exists) {
        results.push({
          lat: item.lat,
          lng: item.lng,
          label: cleanAddress ? `${cleanAddress} (${item.label})` : item.label,
          district: cleanDistrict,
        })
      }
    }
  }

  if (results.length === 0) {
    for (const [distName, coords] of Object.entries(DISTRICT_COORDS)) {
      if (normQuery.includes(normalizeText(distName))) {
        results.push({
          lat: coords.lat,
          lng: coords.lng,
          label: `${cleanAddress ? `${cleanAddress}, ` : ''}${distName}, TP. Hồ Chí Minh`,
          district: distName,
        })
        break
      }
    }
  }

  return results
}

export async function reverseGeocodeHCMC(lat, lng) {
  try {
    const params = new URLSearchParams({
      format: 'json',
      lat: String(lat),
      lon: String(lng),
      zoom: '18',
      addressdetails: '1',
    })
    const response = await fetch(`https://nominatim.openstreetmap.org/reverse?${params.toString()}`, {
      headers: { 'Accept-Language': 'vi' },
    })
    if (response.ok) {
      const data = await response.json()
      const addr = data.address || {}
      const streetPart = [addr.house_number, addr.road || addr.pedestrian || addr.suburb].filter(Boolean).join(' ')
      const districtPart = addr.city_district || addr.county || addr.suburb || addr.town || ''
      return {
        fullAddress: streetPart ? `${streetPart}, TP. Hồ Chí Minh` : data.display_name,
        district: districtPart,
        displayName: data.display_name,
      }
    }
  } catch {
    // Ignore network error on reverse geocode
  }
  return null
}

export const BUILDING_DEFAULTS = {
  b1: {
    lat: 10.8068,
    lng: 106.7318,
    shortName: 'The River',
    areaNote: 'Bán đảo Thảo Điền · Ven sông Sài Gòn',
    pois: [
      { name: 'Ga Metro Thảo Điền (Tuyến số 1)', category: 'Metro & Giao thông', time: '4 phút', distance: '1,2 km', lat: 10.8005, lng: 106.7342 },
      { name: 'Trường Quốc tế BIS & ISHCMC', category: 'Giáo dục quốc tế', time: '3 phút', distance: '850 m', lat: 10.8095, lng: 106.7294 },
      { name: 'Vincom Mega Mall Thảo Điền', category: 'Mua sắm & Ẩm thực', time: '5 phút', distance: '1,5 km', lat: 10.8021, lng: 106.7408 },
      { name: 'Trung tâm Quận 1 (Cầu Ba Son)', category: 'Kết nối trung tâm', time: '12 phút', distance: '6,2 km', lat: 10.7862, lng: 106.7105 },
    ],
  },
  b2: {
    lat: 10.7256,
    lng: 106.7118,
    shortName: 'Lumière',
    areaNote: 'Khu Cảnh Đồi · Phú Mỹ Hưng, Quận 7',
    pois: [
      { name: 'Hồ Bán Nguyệt & Cầu Ánh Sao', category: 'Công viên & Dạo bộ', time: '3 phút', distance: '700 m', lat: 10.7278, lng: 106.7182 },
      { name: 'Crescent Mall & SC VivoCity', category: 'Trung tâm thương mại', time: '4 phút', distance: '1,1 km', lat: 10.7289, lng: 106.7196 },
      { name: 'Bệnh viện Quốc tế FV & Tâm Đức', category: 'Y tế chất lượng cao', time: '5 phút', distance: '1,4 km', lat: 10.7318, lng: 106.7192 },
      { name: 'Chợ Bến Thành (Quận 1)', category: 'Kết nối trung tâm', time: '15 phút', distance: '6,8 km', lat: 10.7725, lng: 106.6980 },
    ],
  },
  b3: {
    lat: 10.7932,
    lng: 106.7186,
    shortName: 'Urban Nest',
    areaNote: 'Trục Nguyễn Hữu Cảnh · Cửa ngõ Đông Sài Gòn',
    pois: [
      { name: 'Công viên Central Park & Landmark 81', category: 'Công viên & Tiện ích', time: '3 phút', distance: '900 m', lat: 10.7951, lng: 106.7218 },
      { name: 'Ga Metro Tân Cảng', category: 'Metro & Giao thông', time: '5 phút', distance: '1,4 km', lat: 10.7985, lng: 106.7231 },
      { name: 'Cầu Thủ Thiêm & Ba Son', category: 'Kết nối liên quận', time: '4 phút', distance: '1,2 km', lat: 10.7888, lng: 106.7179 },
      { name: 'Phố đi bộ Nguyễn Huệ (Quận 1)', category: 'Kết nối trung tâm', time: '7 phút', distance: '2,8 km', lat: 10.7744, lng: 106.7035 },
    ],
  },
  b4: {
    lat: 10.7781,
    lng: 106.6912,
    shortName: 'Greenhouse',
    areaNote: 'Lõi di sản Quận 3 · Nhiều cây xanh',
    pois: [
      { name: 'Hồ Con Rùa & Nhà thờ Đức Bà', category: 'Văn hóa & Nhịp sống', time: '3 phút', distance: '650 m', lat: 10.7827, lng: 106.6959 },
      { name: 'Công viên Tao Đàn', category: 'Không gian xanh', time: '4 phút', distance: '750 m', lat: 10.7746, lng: 106.6924 },
      { name: 'Dinh Độc Lập & Diamond Plaza', category: 'Thương mại & Lịch sử', time: '5 phút', distance: '950 m', lat: 10.7770, lng: 106.6953 },
      { name: 'Ga Metro Bến Thành', category: 'Metro & Giao thông', time: '6 phút', distance: '1,4 km', lat: 10.7718, lng: 106.6981 },
    ],
  },
}

export function getBuildingLocation(building) {
  if (!building) return HCMC_CENTER
  if (Number.isFinite(Number(building.lat)) && Number.isFinite(Number(building.lng))) {
    return { lat: Number(building.lat), lng: Number(building.lng) }
  }
  if (BUILDING_DEFAULTS[building.id]) {
    return { lat: BUILDING_DEFAULTS[building.id].lat, lng: BUILDING_DEFAULTS[building.id].lng }
  }
  if (building.district && DISTRICT_COORDS[building.district]) {
    return DISTRICT_COORDS[building.district]
  }
  return HCMC_CENTER
}

export function getUnitLocation(unit, building) {
  if (unit && Number.isFinite(Number(unit.lat)) && Number.isFinite(Number(unit.lng))) {
    return { lat: Number(unit.lat), lng: Number(unit.lng) }
  }
  return getBuildingLocation(building)
}

export function getBuildingMeta(building, unit) {
  const base = BUILDING_DEFAULTS[building?.id]
  const coords = getUnitLocation(unit, building)
  if (base) return { ...base, ...coords }
  return {
    ...coords,
    shortName: building?.name?.split(' ').slice(0, 2).join(' ') || 'Tòa nhà',
    areaNote: `${building?.district || 'TP. Hồ Chí Minh'} · Khu dân cư hiện đại`,
    pois: [
      { name: 'Trạm xe buýt & Metro gần nhất', category: 'Giao thông công cộng', time: '5 phút', distance: '1,1 km', lat: coords.lat + 0.0032, lng: coords.lng + 0.0028 },
      { name: 'Siêu thị & Trung tâm thương mại', category: 'Mua sắm hàng ngày', time: '4 phút', distance: '900 m', lat: coords.lat - 0.0026, lng: coords.lng + 0.0035 },
      { name: 'Công viên nội khu & Thể thao', category: 'Không gian xanh', time: '3 phút', distance: '600 m', lat: coords.lat + 0.0022, lng: coords.lng - 0.0031 },
      { name: 'Trung tâm Quận 1', category: 'Kết nối trung tâm', time: '12 phút', distance: '4,8 km', lat: 10.7756, lng: 106.7019 },
    ],
  }
}

export function googleMapsUrl(query, coords) {
  const destination = query
    ? encodeURIComponent(query)
    : `${coords.lat},${coords.lng}`
  return `https://www.google.com/maps/search/?api=1&query=${destination}`
}

function project(lat, lng, zoom) {
  const scale = TILE_SIZE * Math.pow(2, zoom)
  const x = ((lng + 180) / 360) * scale
  const sinLat = Math.sin((lat * Math.PI) / 180)
  const y = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale
  return { x, y }
}

function unproject(x, y, zoom) {
  const scale = TILE_SIZE * Math.pow(2, zoom)
  const lng = (x / scale) * 360 - 180
  const n = Math.PI - (2 * Math.PI * y) / scale
  const lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)))
  return { lat: Number(lat.toFixed(5)), lng: Number(lng.toFixed(5)) }
}

const SAIGON_RIVER_POINTS = [
  [10.842, 106.711],
  [10.826, 106.716],
  [10.816, 106.709],
  [10.806, 106.715],
  [10.815, 106.727],
  [10.817, 106.742],
  [10.805, 106.749],
  [10.793, 106.731],
  [10.785, 106.717],
  [10.774, 106.708],
  [10.761, 106.709],
  [10.749, 106.726],
  [10.733, 106.738],
  [10.712, 106.744],
]

function shortMillion(amount) {
  const millions = amount / 1000000
  return `${Number.isInteger(millions) ? millions : millions.toFixed(1).replace('.', ',')} tr`
}

function scaleLabelForZoom(zoom) {
  if (zoom >= 16) return '100 m'
  if (zoom === 15) return '250 m'
  if (zoom === 14) return '500 m'
  if (zoom === 13) return '1 km'
  return '2 km'
}

export function MapCanvas({
  center = HCMC_CENTER,
  zoom = 13,
  minZoom = 11,
  maxZoom = 18,
  height = 520,
  onMapClick,
  children,
  className = '',
  interactive = true,
  controls = true,
  badgeText = 'TP. Hồ Chí Minh · Bản đồ thực tế',
}) {
  const containerRef = useRef(null)
  const [size, setSize] = useState({ width: 720, height })
  const [view, setView] = useState({ lat: center.lat, lng: center.lng, zoom })
  const [tileMode, setTileMode] = useState('arch')
  const dragState = useRef(null)

  useEffect(() => {
    setView((prev) => ({
      ...prev,
      lat: center.lat,
      lng: center.lng,
      zoom: zoom ?? prev.zoom,
    }))
  }, [center.lat, center.lng, zoom])

  useEffect(() => {
    const element = containerRef.current
    if (!element) return
    const update = () => {
      const rect = element.getBoundingClientRect()
      if (rect.width > 0 && rect.height > 0) {
        setSize({ width: rect.width, height: rect.height })
      }
    }
    update()
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(update)
      observer.observe(element)
      return () => observer.disconnect()
    }
  }, [])

  useEffect(() => {
    const element = containerRef.current
    if (!element || !interactive) return
    const onWheel = (event) => {
      event.preventDefault()
      const delta = event.deltaY < 0 ? 1 : -1
      setView((prev) => ({
        ...prev,
        zoom: Math.max(minZoom, Math.min(maxZoom, prev.zoom + delta)),
      }))
    }
    element.addEventListener('wheel', onWheel, { passive: false })
    return () => element.removeEventListener('wheel', onWheel)
  }, [interactive, minZoom, maxZoom])

  const centerPx = useMemo(() => project(view.lat, view.lng, view.zoom), [view.lat, view.lng, view.zoom])
  const topLeftPx = useMemo(
    () => ({ x: centerPx.x - size.width / 2, y: centerPx.y - size.height / 2 }),
    [centerPx.x, centerPx.y, size.width, size.height],
  )

  const tiles = useMemo(() => {
    const z = Math.round(view.zoom)
    const maxTile = Math.pow(2, z)
    const startX = Math.floor(topLeftPx.x / TILE_SIZE)
    const endX = Math.floor((topLeftPx.x + size.width) / TILE_SIZE)
    const startY = Math.floor(topLeftPx.y / TILE_SIZE)
    const endY = Math.floor((topLeftPx.y + size.height) / TILE_SIZE)
    const list = []
    for (let tx = startX; tx <= endX; tx += 1) {
      for (let ty = startY; ty <= endY; ty += 1) {
        if (ty < 0 || ty >= maxTile) continue
        const wrappedX = ((tx % maxTile) + maxTile) % maxTile
        const primaryUrl = tileMode === 'satellite'
          ? `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${ty}/${wrappedX}`
          : `https://tile.openstreetmap.org/${z}/${wrappedX}/${ty}.png`
        const labelOverlayUrl = tileMode === 'satellite'
          ? `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/${z}/${ty}/${wrappedX}`
          : null
        list.push({
          key: `${tileMode}-${z}-${tx}-${ty}`,
          left: tx * TILE_SIZE - topLeftPx.x,
          top: ty * TILE_SIZE - topLeftPx.y,
          primaryUrl,
          labelOverlayUrl,
        })
      }
    }
    return list
  }, [view.zoom, topLeftPx.x, topLeftPx.y, size.width, size.height, tileMode])

  const riverPath = useMemo(() => {
    return SAIGON_RIVER_POINTS.map(([lat, lng], index) => {
      const pt = project(lat, lng, view.zoom)
      const sx = pt.x - topLeftPx.x
      const sy = pt.y - topLeftPx.y
      return `${index === 0 ? 'M' : 'L'} ${sx.toFixed(1)} ${sy.toFixed(1)}`
    }).join(' ')
  }, [view.zoom, topLeftPx.x, topLeftPx.y])

  function toScreen(lat, lng) {
    const pt = project(lat, lng, view.zoom)
    return { x: pt.x - topLeftPx.x, y: pt.y - topLeftPx.y }
  }

  function handlePointerDown(event) {
    if (!interactive) return
    if (event.target.closest('button, a, .map-popover, .map-control-group, .map-style-switcher')) return
    dragState.current = {
      startX: event.clientX,
      startY: event.clientY,
      startCenter: { ...centerPx },
      moved: false,
    }
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  function handlePointerMove(event) {
    if (!dragState.current) return
    const dx = event.clientX - dragState.current.startX
    const dy = event.clientY - dragState.current.startY
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
      dragState.current.moved = true
    }
    const nextCenter = unproject(
      dragState.current.startCenter.x - dx,
      dragState.current.startCenter.y - dy,
      view.zoom,
    )
    setView((prev) => ({ ...prev, lat: nextCenter.lat, lng: nextCenter.lng }))
  }

  function handlePointerUp(event) {
    if (!dragState.current) return
    const wasMoved = dragState.current.moved
    dragState.current = null
    if (!wasMoved && onMapClick && containerRef.current) {
      const rect = containerRef.current.getBoundingClientRect()
      const clickX = event.clientX - rect.left + topLeftPx.x
      const clickY = event.clientY - rect.top + topLeftPx.y
      onMapClick(unproject(clickX, clickY, view.zoom))
    }
  }

  function changeZoom(delta) {
    setView((prev) => ({
      ...prev,
      zoom: Math.max(minZoom, Math.min(maxZoom, prev.zoom + delta)),
    }))
  }

  return (
    <div
      ref={containerRef}
      className={`an-cu-map map-mode-${tileMode} ${className}`}
      style={{ height: typeof height === 'number' ? `${height}px` : height }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      role="region"
      aria-label={badgeText}
    >
      <svg className="map-vector-underlay" viewBox={`0 0 ${size.width} ${size.height}`} preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <pattern id="map-grid-pattern" width="64" height="64" patternUnits="userSpaceOnUse">
            <path d="M 64 0 L 0 0 0 64" fill="none" stroke="#e2dfd4" strokeWidth="0.7" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#map-grid-pattern)" />
        <path d={riverPath} fill="none" stroke="#cadcd6" strokeWidth="26" strokeLinecap="round" strokeLinejoin="round" />
        <path d={riverPath} fill="none" stroke="#b8cfc7" strokeWidth="14" strokeLinecap="round" strokeLinejoin="round" />
      </svg>

      <div className="map-tile-layer" aria-hidden="true">
        {tiles.map((tile) => (
          <div key={tile.key}>
            <img
              src={tile.primaryUrl}
              alt=""
              className="map-tile"
              style={{ left: `${tile.left}px`, top: `${tile.top}px`, width: `${TILE_SIZE}px`, height: `${TILE_SIZE}px` }}
              loading="eager"
              draggable={false}
              onError={(event) => {
                event.currentTarget.style.opacity = '0'
              }}
            />
            {tile.labelOverlayUrl && (
              <img
                src={tile.labelOverlayUrl}
                alt=""
                className="map-tile map-tile-labels"
                style={{ left: `${tile.left}px`, top: `${tile.top}px`, width: `${TILE_SIZE}px`, height: `${TILE_SIZE}px` }}
                loading="eager"
                draggable={false}
              />
            )}
          </div>
        ))}
      </div>

      <div className="map-architectural-frame" aria-hidden="true" />

      {badgeText && (
        <div className="map-top-badge">
          <Compass size={15} />
          <span>{badgeText}</span>
        </div>
      )}

      {controls && (
        <div className="map-style-switcher" role="group" aria-label="Chế độ nền bản đồ">
          {[
            ['arch', 'Kiến trúc'],
            ['street', 'Bản đồ phố'],
            ['satellite', 'Vệ tinh'],
          ].map(([mode, label]) => (
            <button
              type="button"
              key={mode}
              className={tileMode === mode ? 'active' : ''}
              onClick={() => setTileMode(mode)}
            >
              {label}
            </button>
          ))}
        </div>
      )}

      {controls && (
        <div className="map-control-group" aria-label="Điều khiển phóng to thu nhỏ">
          <button type="button" className="map-ctrl-btn" title="Phóng to" aria-label="Phóng to bản đồ" onClick={() => changeZoom(1)}>
            <Plus size={16} />
          </button>
          <button type="button" className="map-ctrl-btn" title="Thu nhỏ" aria-label="Thu nhỏ bản đồ" onClick={() => changeZoom(-1)}>
            <Minus size={16} />
          </button>
          <button
            type="button"
            className="map-ctrl-btn"
            title="Về vị trí mặc định"
            aria-label="Đặt lại góc nhìn bản đồ"
            onClick={() => setView({ lat: center.lat, lng: center.lng, zoom })}
          >
            <LocateFixed size={15} />
          </button>
        </div>
      )}

      <div className="map-overlay-layer">
        {typeof children === 'function' ? children({ toScreen, view, setView }) : children}
      </div>

      <div className="map-hud-bottom" aria-hidden="true">
        <div className="map-scale-bar">
          <span className="scale-line" />
          <span>{scaleLabelForZoom(Math.round(view.zoom))}</span>
        </div>
        <div className="map-attribution">
          {view.lat.toFixed(4)}°N, {view.lng.toFixed(4)}°E · © OpenStreetMap
        </div>
      </div>
    </div>
  )
}

export function ExploreMap({
  units,
  buildingsById,
  activeUnitId,
  onSelectUnit,
  onHoverUnit,
  favorites = [],
  onToggleFavorite,
  selectedDistricts = [],
  onSelectDistrict,
}) {
  const [selectedId, setSelectedId] = useState(null)
  const [focusedCoords, setFocusedCoords] = useState(null)

  const markers = useMemo(() => {
    const grouped = new Map()
    units.forEach((unit) => {
      const building = buildingsById.get(unit.buildingId)
      if (!building) return
      const coords = getUnitLocation(unit, building)
      const meta = getBuildingMeta(building, unit)
      const key = `${coords.lat.toFixed(4)}-${coords.lng.toFixed(4)}`
      const list = grouped.get(key) || []
      list.push({ unit, building, coords, meta })
      grouped.set(key, list)
    })
    const result = []
    grouped.forEach((items) => {
      items.forEach((entry, index) => {
        const offsetAngle = index * 1.1
        const offsetRadius = index === 0 ? 0 : 0.0018 * index
        result.push({
          ...entry,
          lat: entry.coords.lat + Math.sin(offsetAngle) * offsetRadius,
          lng: entry.coords.lng + Math.cos(offsetAngle) * offsetRadius,
        })
      })
    })
    return result
  }, [units, buildingsById])

  const mapCenter = useMemo(() => {
    if (focusedCoords) {
      return focusedCoords
    }
    if (selectedId) {
      const target = markers.find((m) => m.unit.id === selectedId)
      if (target) return { lat: target.lat, lng: target.lng, zoom: 15 }
    }
    if (markers.length === 1) {
      return { lat: markers[0].lat, lng: markers[0].lng, zoom: 15 }
    }
    if (markers.length > 1) {
      const avgLat = markers.reduce((sum, m) => sum + m.lat, 0) / markers.length
      const avgLng = markers.reduce((sum, m) => sum + m.lng, 0) / markers.length
      const latSpread = Math.max(...markers.map((m) => m.lat)) - Math.min(...markers.map((m) => m.lat))
      const lngSpread = Math.max(...markers.map((m) => m.lng)) - Math.min(...markers.map((m) => m.lng))
      const spread = Math.max(latSpread, lngSpread)
      const zoom = spread < 0.022 ? 15 : spread < 0.055 ? 13 : 12
      return { lat: avgLat, lng: avgLng, zoom }
    }
    return { ...HCMC_CENTER, zoom: 12 }
  }, [markers, selectedId, focusedCoords])

  useEffect(() => {
    if (selectedId && !units.some((u) => u.id === selectedId)) {
      setSelectedId(null)
    }
  }, [units, selectedId])

  const activeMarker = markers.find((m) => m.unit.id === (selectedId || activeUnitId))
  const districts = ['TP. Thủ Đức', 'Quận 7', 'Bình Thạnh', 'Quận 3']

  return (
    <div className="explore-map-shell">
      <div className="explore-map-Chips" aria-label="Nhảy nhanh tới khu vực trên bản đồ">
        <span className="map-chip-label"><Navigation size={13} /> Khu vực:</span>
        <button
          type="button"
          className={`map-district-chip ${selectedDistricts.length === 0 && !focusedCoords ? 'active' : ''}`}
          onClick={() => {
            setSelectedId(null)
            setFocusedCoords(null)
            if (selectedDistricts.length > 0) {
              selectedDistricts.forEach((d) => onSelectDistrict?.(d))
            }
          }}
        >
          Toàn TP.HCM
        </button>
        {districts.map((district) => {
          const active = selectedDistricts.includes(district)
          return (
            <button
              type="button"
              key={district}
              className={`map-district-chip ${active ? 'active' : ''}`}
              onClick={() => {
                setSelectedId(null)
                setFocusedCoords(null)
                onSelectDistrict?.(district)
              }}
            >
              {district}
            </button>
          )
        })}
      </div>

      <MapCanvas
        center={{ lat: mapCenter.lat, lng: mapCenter.lng }}
        zoom={mapCenter.zoom}
        height={550}
        badgeText={`Bản đồ căn hộ TP.HCM · ${units.length} căn đang mở`}
        onMapClick={() => setSelectedId(null)}
      >
        {({ toScreen }) => (
          <>
            {markers.map(({ unit, building, meta, lat, lng }) => {
              const pos = toScreen(lat, lng)
              const isHighlighted = activeUnitId === unit.id || selectedId === unit.id
              const isSaved = favorites.includes(unit.id)
              return (
                <button
                  type="button"
                  key={unit.id}
                  className={`map-arch-marker ${isHighlighted ? 'is-active' : ''} ${isSaved ? 'is-saved' : ''}`}
                  style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
                  onMouseEnter={() => onHoverUnit?.(unit.id)}
                  onMouseLeave={() => onHoverUnit?.(null)}
                  onClick={(event) => {
                    event.stopPropagation()
                    setFocusedCoords(null)
                    setSelectedId((prev) => (prev === unit.id ? null : unit.id))
                    onSelectUnit?.(unit.id)
                  }}
                  aria-label={`${unit.title} – ${money(unit.price)} mỗi tháng tại ${building.district}`}
                >
                  <span className="arch-marker-card">
                    <span className="arch-marker-building">{meta.shortName}</span>
                    <span className="arch-marker-price">{shortMillion(unit.price)}</span>
                  </span>
                  <span className="arch-marker-stem" />
                  <span className="arch-marker-anchor" />
                </button>
              )
            })}

            {activeMarker && (
              <article className="map-popover" aria-label={`Xem nhanh ${activeMarker.unit.title}`}>
                <div className="map-popover-image">
                  <img src={unitImage(activeMarker.unit)} alt={activeMarker.unit.title} />
                  <Status tone="green">Còn trống</Status>
                  {onToggleFavorite && (
                    <button
                      type="button"
                      className={`favorite ${favorites.includes(activeMarker.unit.id) ? 'is-saved' : ''}`}
                      aria-label="Lưu căn hộ"
                      onClick={() => onToggleFavorite(activeMarker.unit.id)}
                    >
                      <Heart size={16} />
                    </button>
                  )}
                </div>
                <div className="map-popover-body">
                  <div className="card-location">
                    <MapPin size={13} /> {activeMarker.building.name} · {activeMarker.building.district}
                  </div>
                  <h3>
                    <Link to={`/can-ho/${activeMarker.unit.id}`}>{activeMarker.unit.title}</Link>
                  </h3>
                  <div className="features">
                    <span><BedDouble size={14} /> {activeMarker.unit.beds} PN</span>
                    <span><Bath size={14} /> {activeMarker.unit.baths} WC</span>
                    <span><Maximize size={14} /> {activeMarker.unit.area} m²</span>
                  </div>
                  <div className="map-popover-footer">
                    <div>
                      <strong>{money(activeMarker.unit.price)}</strong>
                      <span> / tháng</span>
                    </div>
                    <Link className="button small primary" to={`/can-ho/${activeMarker.unit.id}`}>
                      Chi tiết <ArrowRight size={14} />
                    </Link>
                  </div>
                </div>
              </article>
            )}
          </>
        )}
      </MapCanvas>

      {markers.length > 0 && (
        <div className="map-building-quickbar" aria-label="Phóng cận cảnh từng tòa nhà">
          <span className="quickbar-label"><Building2 size={13} /> Xem cận cảnh:</span>
          {markers.map(({ unit, building, meta, lat, lng }) => (
            <button
              type="button"
              key={unit.id}
              className={`quickbar-item ${selectedId === unit.id ? 'active' : ''}`}
              onClick={() => {
                setSelectedId(unit.id)
                setFocusedCoords({ lat, lng, zoom: 16 })
              }}
            >
              <strong>{meta.shortName}</strong>
              <span>{building.district}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export function NeighborhoodMap({ building, unit }) {
  const meta = useMemo(() => getBuildingMeta(building, unit), [building, unit])
  const [activePoi, setActivePoi] = useState(meta.pois[0])

  useEffect(() => {
    setActivePoi(meta.pois[0])
  }, [meta])

  return (
    <article className="panel location-panel">
      <div className="location-panel-head">
        <div>
          <span className="location-kicker"><Sparkles size={14} /> VỊ TRÍ & KẾT NỐI XUNG QUANH</span>
          <h2>{building.name}</h2>
          <p className="location-sub">{building.address} · <em>{meta.areaNote}</em></p>
        </div>
        <a
          className="button outline small"
          href={googleMapsUrl(`${building.name}, ${building.address}`, meta)}
          target="_blank"
          rel="noreferrer"
        >
          <ExternalLink size={15} /> Mở Google Maps
        </a>
      </div>

      <div className="neighborhood-layout">
        <MapCanvas
          center={{ lat: meta.lat, lng: meta.lng }}
          zoom={15}
          height={350}
          badgeText={`${building.district} · Bán kính tiện ích 1,5 km`}
        >
          {({ toScreen }) => {
            const homePos = toScreen(meta.lat, meta.lng)
            return (
              <>
                <div
                  className="map-radius-ring"
                  style={{ left: `${homePos.x}px`, top: `${homePos.y}px` }}
                  aria-hidden="true"
                />

                {meta.pois.map((poi) => {
                  const pos = toScreen(poi.lat, poi.lng)
                  const isCurrent = activePoi?.name === poi.name
                  return (
                    <button
                      type="button"
                      key={poi.name}
                      className={`map-poi-pin ${isCurrent ? 'is-active' : ''}`}
                      style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
                      onClick={() => setActivePoi(poi)}
                      title={`${poi.name} (${poi.time})`}
                    >
                      <TrainFront size={13} />
                      <span>{poi.time}</span>
                    </button>
                  )
                })}

                <div
                  className="map-sanctuary-pin"
                  style={{ left: `${homePos.x}px`, top: `${homePos.y}px` }}
                >
                  <div className="sanctuary-pin-pulse" />
                  <div className="sanctuary-pin-core">
                    <Building2 size={16} />
                    <div>
                      <strong>{building.name}</strong>
                      <span>{unit ? `${shortMillion(unit.price)}/tháng` : building.district}</span>
                    </div>
                  </div>
                </div>
              </>
            )
          }}
        </MapCanvas>

        <div className="poi-list" aria-label="Tiện ích và kết nối lân cận">
          <div className="poi-list-header">
            <strong>Tiện ích & Kết nối thực tế</strong>
            <span>Bấm vào từng điểm để định vị trên bản đồ</span>
          </div>
          {meta.pois.map((poi) => {
            const selected = activePoi?.name === poi.name
            return (
              <button
                type="button"
                key={poi.name}
                className={`poi-card ${selected ? 'is-active' : ''}`}
                onClick={() => setActivePoi(poi)}
              >
                <div className="poi-card-main">
                  <span className="poi-category">{poi.category}</span>
                  <strong>{poi.name}</strong>
                </div>
                <div className="poi-card-distance">
                  <b>{poi.time}</b>
                  <span>{poi.distance}</span>
                </div>
              </button>
            )
          })}
        </div>
      </div>
    </article>
  )
}

export function AppointmentMiniMap({ contract, building }) {
  const coords = getBuildingLocation(building)
  const placeLabel = contract.location || `Sảnh lễ tân ${contract.propertyName}`

  return (
    <div className="appointment-minimap-wrap">
      <MapCanvas
        center={coords}
        zoom={16}
        height={195}
        controls={false}
        badgeText="Điểm hẹn ký & bàn giao"
      >
        {({ toScreen }) => {
          const pos = toScreen(coords.lat, coords.lng)
          return (
            <div className="map-sanctuary-pin compact" style={{ left: `${pos.x}px`, top: `${pos.y}px` }}>
              <div className="sanctuary-pin-pulse" />
              <div className="sanctuary-pin-core">
                <MapPin size={15} />
                <div>
                  <strong>{contract.propertyName}</strong>
                  <span>Căn {contract.room}</span>
                </div>
              </div>
            </div>
          )
        }}
      </MapCanvas>
      <div className="appointment-minimap-bar">
        <span>Tọa độ: {coords.lat.toFixed(4)}°N, {coords.lng.toFixed(4)}°E</span>
        <a
          href={googleMapsUrl(placeLabel, coords)}
          target="_blank"
          rel="noreferrer"
          className="text-link"
        >
          Chỉ đường <ExternalLink size={14} />
        </a>
      </div>
    </div>
  )
}

export function OwnerPortfolioMap({ buildings, units, db, selectedBuildingId, onSelectBuilding }) {
  const center = useMemo(() => {
    if (selectedBuildingId) {
      const found = buildings.find((b) => b.id === selectedBuildingId)
      if (found) return { ...getBuildingLocation(found), zoom: 15 }
    }
    if (buildings.length > 0) {
      const coords = buildings.map(getBuildingLocation)
      const lat = coords.reduce((sum, c) => sum + c.lat, 0) / coords.length
      const lng = coords.reduce((sum, c) => sum + c.lng, 0) / coords.length
      return { lat, lng, zoom: 13 }
    }
    return { ...HCMC_CENTER, zoom: 12 }
  }, [buildings, selectedBuildingId])

  return (
    <div className="owner-map-card panel">
      <div className="owner-map-head">
        <div>
          <h2><MapPin size={20} /> Bản đồ phân bổ tòa nhà đang quản lý</h2>
          <p className="subtle">Bấm vào ghim tòa nhà trên bản đồ để lọc nhanh danh sách căn hộ bên dưới.</p>
        </div>
        {selectedBuildingId && (
          <button type="button" className="button small outline" onClick={() => onSelectBuilding('')}>
            Xem tất cả tòa nhà
          </button>
        )}
      </div>
      <MapCanvas
        center={{ lat: center.lat, lng: center.lng }}
        zoom={center.zoom}
        height={310}
        badgeText={`Danh mục ${buildings.length} tòa nhà tại TP.HCM`}
      >
        {({ toScreen }) =>
          buildings.map((building) => {
            const coords = getBuildingLocation(building)
            const pos = toScreen(coords.lat, coords.lng)
            const buildingUnits = units.filter((u) => u.buildingId === building.id)
            const readyCount = buildingUnits.filter((u) => unitStatus(db, u) === 'ready').length
            const active = selectedBuildingId === building.id
            return (
              <button
                type="button"
                key={building.id}
                className={`map-building-pin ${active ? 'is-active' : ''}`}
                style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
                onClick={() => onSelectBuilding(active ? '' : building.id)}
              >
                <Building2 size={15} />
                <div className="building-pin-text">
                  <strong>{building.name}</strong>
                  <span>{readyCount} trống / {buildingUnits.length} căn</span>
                </div>
              </button>
            )
          })
        }
      </MapCanvas>
    </div>
  )
}

export function BuildingCoordPicker({
  defaultBuilding,
  defaultUnit,
  districtValue = '',
  addressValue = '',
  nameValue = '',
  onResolvedAddress,
  label = 'Định vị chính xác trên bản đồ',
}) {
  const initial = useMemo(
    () => getUnitLocation(defaultUnit, defaultBuilding),
    [defaultUnit, defaultBuilding],
  )
  const [coords, setCoords] = useState(initial)
  const [mapZoom, setMapZoom] = useState(16)
  const [searching, setSearching] = useState(false)
  const [statusMsg, setStatusMsg] = useState('')
  const [suggestions, setSuggestions] = useState([])
  const [reverseInfo, setReverseInfo] = useState(null)
  const lastQueryRef = useRef('')

  useEffect(() => {
    const next = getUnitLocation(defaultUnit, defaultBuilding)
    setCoords(next)
  }, [defaultBuilding?.id, defaultUnit?.id])

  useEffect(() => {
    const trimmedAddr = String(addressValue || '').trim()
    const trimmedDist = String(districtValue || '').trim()
    if (!trimmedAddr && !trimmedDist) return

    const initialQuery = `${defaultBuilding?.address || ''}|${defaultBuilding?.district || ''}`
    const currentQuery = `${trimmedAddr}|${trimmedDist}`
    if (currentQuery === initialQuery && !lastQueryRef.current) {
      lastQueryRef.current = currentQuery
      return
    }

    const timer = setTimeout(async () => {
      if (trimmedAddr.length >= 4) {
        setSearching(true)
        const found = await geocodeAddressInHCMC(trimmedAddr, trimmedDist)
        setSearching(false)
        if (found.length > 0) {
          setCoords({ lat: found[0].lat, lng: found[0].lng })
          setMapZoom(17)
          setSuggestions(found.slice(0, 3))
          setStatusMsg(`Đã cắm ghim theo địa chỉ: ${found[0].label.split(',').slice(0, 3).join(',')}`)
        }
      } else if (!trimmedAddr && DISTRICT_COORDS[trimmedDist]) {
        setCoords(DISTRICT_COORDS[trimmedDist])
        setMapZoom(14)
        setStatusMsg(`Đang hiển thị trung tâm ${trimmedDist}`)
      }
    }, 650)

    return () => clearTimeout(timer)
  }, [addressValue, districtValue, defaultBuilding?.address, defaultBuilding?.district])

  async function locateNow() {
    const queryAddr = String(addressValue || nameValue || defaultBuilding?.address || '').trim()
    const queryDist = String(districtValue || defaultBuilding?.district || '').trim()
    if (!queryAddr && !queryDist) {
      setStatusMsg('Nhập địa chỉ hoặc khu vực để định vị chính xác.')
      return
    }
    setSearching(true)
    setReverseInfo(null)
    const found = await geocodeAddressInHCMC(queryAddr, queryDist)
    setSearching(false)
    if (found.length > 0) {
      setCoords({ lat: found[0].lat, lng: found[0].lng })
      setMapZoom(17)
      setSuggestions(found.slice(0, 4))
      setStatusMsg(`Đã định vị chính xác: ${found[0].label.split(',').slice(0, 3).join(',')}`)
    } else {
      setStatusMsg('Không tìm thấy địa chỉ trùng khớp, bạn có thể click trực tiếp lên bản đồ.')
    }
  }

  function locateMyGPS() {
    if (!navigator.geolocation) {
      setStatusMsg('Trình duyệt không hỗ trợ GPS.')
      return
    }
    setSearching(true)
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        const picked = {
          lat: Number(pos.coords.latitude.toFixed(5)),
          lng: Number(pos.coords.longitude.toFixed(5)),
        }
        setCoords(picked)
        setMapZoom(17)
        setSearching(false)
        setStatusMsg('Đã cắm ghim tại vị trí GPS hiện tại của bạn.')
        const rev = await reverseGeocodeHCMC(picked.lat, picked.lng)
        if (rev) setReverseInfo(rev)
      },
      () => {
        setSearching(false)
        setStatusMsg('Không thể lấy GPS. Hãy bấm "Định vị theo địa chỉ" hoặc click bản đồ.')
      },
      { enableHighAccuracy: true, timeout: 6000 },
    )
  }

  async function handlePinClick(picked) {
    setCoords(picked)
    setMapZoom((prev) => Math.max(prev, 16))
    setStatusMsg(`Đã ghim thủ công tại ${picked.lat.toFixed(5)}°N, ${picked.lng.toFixed(5)}°E`)
    const rev = await reverseGeocodeHCMC(picked.lat, picked.lng)
    if (rev) {
      setReverseInfo(rev)
    }
  }

  const displayTitle = nameValue || defaultBuilding?.name || 'Vị trí đã ghim'
  const displaySub = addressValue || defaultBuilding?.address || 'Click bản đồ để tinh chỉnh'

  return (
    <div className="coord-picker">
      <div className="coord-picker-head">
        <span><MapPin size={15} /> {label}</span>
        <span className="coord-badge">{coords.lat.toFixed(5)}°N, {coords.lng.toFixed(5)}°E</span>
      </div>

      <div className="coord-picker-toolbar">
        <button
          type="button"
          className="button small outline coord-locate-btn"
          onClick={locateNow}
          disabled={searching}
        >
          {searching ? <Loader2 size={14} className="spin" /> : <Search size={14} />}
          <span>{searching ? 'Đang tìm tọa độ...' : 'Định vị theo địa chỉ đã nhập'}</span>
        </button>
        <button
          type="button"
          className="button small outline"
          onClick={locateMyGPS}
          title="Lấy vị trí GPS hiện tại"
        >
          <Crosshair size={14} /> GPS hiện tại
        </button>
      </div>

      {suggestions.length > 1 && (
        <div className="coord-suggestions" role="list" aria-label="Gợi ý tọa độ địa chỉ">
          {suggestions.map((item, idx) => {
            const isSelected = Math.abs(coords.lat - item.lat) < 0.0002 && Math.abs(coords.lng - item.lng) < 0.0002
            return (
              <button
                type="button"
                key={`${item.lat}-${item.lng}-${idx}`}
                className={`coord-suggestion-item ${isSelected ? 'active' : ''}`}
                onClick={() => {
                  setCoords({ lat: item.lat, lng: item.lng })
                  setMapZoom(17)
                  setStatusMsg(`Đã chọn điểm ghim: ${item.label.split(',').slice(0, 3).join(',')}`)
                }}
              >
                <MapPin size={13} />
                <span>{item.label}</span>
                {isSelected && <Check size={13} />}
              </button>
            )
          })}
        </div>
      )}

      {statusMsg && (
        <div className="coord-status-bar">
          <span>{statusMsg}</span>
          {reverseInfo && onResolvedAddress && (
            <button
              type="button"
              className="coord-apply-btn"
              onClick={() => {
                onResolvedAddress(reverseInfo)
                setReverseInfo(null)
              }}
            >
              Điền địa chỉ này vào form
            </button>
          )}
        </div>
      )}

      <input type="hidden" name="lat" value={coords.lat} />
      <input type="hidden" name="lng" value={coords.lng} />

      <MapCanvas
        center={coords}
        zoom={mapZoom}
        height={235}
        onMapClick={handlePinClick}
        badgeText="Click trực tiếp lên mặt đường / tòa nhà để cắm ghim chính xác"
      >
        {({ toScreen }) => {
          const pos = toScreen(coords.lat, coords.lng)
          return (
            <div className="map-sanctuary-pin compact" style={{ left: `${pos.x}px`, top: `${pos.y}px` }}>
              <div className="sanctuary-pin-pulse" />
              <div className="sanctuary-pin-core">
                <MapPin size={15} />
                <div>
                  <strong>{displayTitle}</strong>
                  <span>{displaySub.slice(0, 34)}</span>
                </div>
              </div>
            </div>
          )
        }}
      </MapCanvas>
    </div>
  )
}
