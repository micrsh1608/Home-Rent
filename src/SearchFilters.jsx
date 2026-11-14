import { useState } from 'react'

export const formatBudget = new Intl.NumberFormat('vi-VN').format
const budgetStep = 100000
const budgetPresets = [
  { label: 'Đến 8 triệu', min: 0, max: 8000000 },
  { label: '8–12 triệu', min: 8000000, max: 12000000 },
  { label: '12–16 triệu', min: 12000000, max: 16000000 },
  { label: 'Từ 16 triệu', min: 16000000, max: null },
]

function BudgetAmount({ label, value, onCommit }) {
  const [editing, setEditing] = useState(null)

  return <label className="budget-amount">
    <input
      type="text" inputMode="numeric" aria-label={label}
      value={editing ?? formatBudget(value)}
      onChange={(event) => setEditing(event.target.value.replace(/[^0-9.\s]/g, ''))}
      onBlur={() => {
        const digits = editing?.replace(/\D/g, '')
        if (digits) onCommit(Number(digits))
        setEditing(null)
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          event.currentTarget.blur()
        }
      }}
    />
    <span aria-hidden="true">VND</span>
  </label>
}

export function BudgetFilter({ minimum, maximum, ceiling, onMinimum, onMaximum, onPreset }) {
  const chooseAmount = (update) => (event) => {
    update(Math.round(Number(event.target.value) / budgetStep) * budgetStep)
  }
  const moveWithKeyboard = (value, lower, upper, update) => (event) => {
    const positions = {
      ArrowLeft: value - budgetStep, ArrowDown: value - budgetStep,
      ArrowRight: value + budgetStep, ArrowUp: value + budgetStep,
      PageDown: value - 10 * budgetStep, PageUp: value + 10 * budgetStep,
      Home: lower, End: upper,
    }
    if (Object.hasOwn(positions, event.key)) {
      event.preventDefault()
      update(Math.max(lower, Math.min(positions[event.key], upper)))
    }
  }

  return <div className="budget-panel">
    <div className="budget-filter">
      <div className="budget-slider" data-min-on-top={maximum === ceiling}>
        <div className="budget-track" aria-hidden="true">
          <div className="budget-track-fill" style={{ left: `${minimum / ceiling * 100}%`, right: `${(ceiling - maximum) / ceiling * 100}%` }} />
        </div>
        <input className="budget-range budget-range-min" type="range" min="0" max={ceiling} step="any" value={minimum}
          aria-label="Kéo để chọn ngân sách tối thiểu" aria-valuetext={`${formatBudget(minimum)} VND`}
          onChange={chooseAmount(onMinimum)} onKeyDown={moveWithKeyboard(minimum, 0, maximum, onMinimum)} />
        <input className="budget-range budget-range-max" type="range" min="0" max={ceiling} step="any" value={maximum}
          aria-label="Kéo để chọn ngân sách tối đa" aria-valuetext={`${formatBudget(maximum)} VND`}
          onChange={chooseAmount(onMaximum)} onKeyDown={moveWithKeyboard(maximum, minimum, ceiling, onMaximum)} />
      </div>
      <div className="budget-amounts">
        <BudgetAmount label="Ngân sách tối thiểu (VND)" value={minimum} onCommit={onMinimum} />
        <span aria-hidden="true">–</span>
        <BudgetAmount label="Ngân sách tối đa (VND)" value={maximum} onCommit={onMaximum} />
      </div>
    </div>
    <div className="budget-presets" role="group" aria-labelledby="budget-presets-label">
      <div className="budget-presets-heading">
        <strong id="budget-presets-label">Chọn nhanh ngân sách</strong>
        <button type="button" className="budget-reset" onClick={() => onPreset(0, null)}>Mọi mức giá</button>
      </div>
      <div className="budget-preset-list">
        {budgetPresets.map(({ label, min, max }) => <button
          type="button" className="budget-preset" key={label}
          aria-pressed={minimum === min && maximum === (max ?? ceiling)}
          onClick={() => onPreset(min, max)}
        >{label}</button>)}
      </div>
    </div>
  </div>
}

export function DistrictFilter({ districts, counts, selected, onToggle }) {
  const [showAll, setShowAll] = useState(false)
  const previewCount = 5
  const displayed = showAll ? districts : districts.slice(0, previewCount)

  return <div className="district-filter">
    <div className="district-list" id="district-list">
      {displayed.map((name) => <label className="district-choice" key={name}>
        <input type="checkbox" checked={selected.includes(name)} onChange={() => onToggle(name)} />
        <span>{name} <span className="district-count">({counts.get(name) ?? 0})</span></span>
      </label>)}
    </div>
    {districts.length > previewCount && <button type="button" className="district-more" aria-controls="district-list"
      aria-expanded={showAll} onClick={() => setShowAll(!showAll)}>{showAll ? 'Thu gọn' : 'Xem tất cả'}</button>}
  </div>
}
