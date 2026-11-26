import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, ArrowRight, Building2, CheckCircle2, Eye, EyeOff, Mail, ShieldCheck, UserRound } from 'lucide-react'
import { backendApi, people } from './demo'
import { Field, Notice, useDemo } from './shared'

function PasswordField({ label = 'Mật khẩu', ...props }) {
  const [visible, setVisible] = useState(false)
  return <Field label={label}><span className="password-input">
    <input {...props} type={visible ? 'text' : 'password'} required />
    <button type="button" className="icon-btn" aria-label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</button>
  </span></Field>
}

const timedDelivery = (delivery) => ({ ...delivery, resendAt: Date.now() + delivery.resendAfterSeconds * 1000 })

function OtpStep({ delivery, email, busy, error, onVerify, onResend, onBack, children }) {
  const [code, setCode] = useState('')
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    setCode('')
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [delivery.challengeId])
  const seconds = Math.max(0, Math.ceil((delivery.resendAt - now) / 1000))
  const expired = now >= new Date(delivery.expiresAt).getTime()
  return <form className="stack" onSubmit={(event) => { event.preventDefault(); onVerify(code) }}>
    <div className="otp-heading"><span className="step-icon"><Mail size={23} /></span><div><h2>Kiểm tra email của bạn</h2><p>Mã gồm 6 chữ số gửi tới <strong>{email}</strong>. Hãy kiểm tra cả thư rác.</p></div></div>
    <Field label="Mã xác nhận OTP"><input className="otp-input" name="otp" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="000000" value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} required autoFocus disabled={busy} /></Field>
    <p className="subtle">{expired ? 'Mã đã hết hạn. Vui lòng yêu cầu mã mới.' : 'Mã có hiệu lực trong 10 phút và chỉ dùng một lần.'}</p>
    {children}
    {error && <Notice error>{error}</Notice>}
    <button className="button primary wide" disabled={busy || expired} type="submit">{busy ? 'Đang xác nhận…' : 'Xác nhận'} <ShieldCheck size={18} /></button>
    <div className="auth-secondary-actions"><button className="text-button" type="button" disabled={busy || seconds > 0} onClick={onResend}>{seconds > 0 ? `Gửi lại mã sau ${seconds}s` : 'Gửi lại mã'}</button><button className="text-button" type="button" disabled={busy} onClick={onBack}>Quay lại</button></div>
  </form>
}

export function Authentication({ mode = 'login' }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { db, login, loginWithCredentials, completeRegistration, notify } = useDemo()
  const [role, setRole] = useState(location.state?.role ?? 'tenant')
  const [fields, setFields] = useState({ name: '', email: location.state?.email ?? '', password: '', confirm: '' })
  const [delivery, setDelivery] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const set = (key) => (event) => setFields((previous) => ({ ...previous, [key]: event.target.value }))
  const title = mode === 'register' ? 'Tạo tài khoản' : mode === 'reset' ? 'Quên mật khẩu?' : 'Đăng nhập'
  const description = mode === 'register'
    ? 'Tạo tài khoản khách thuê để tìm tổ ấm, đặt lịch ký và quản lý hợp đồng của bạn.'
    : mode === 'reset' ? 'Nhập email đã xác thực để nhận mã đặt lại mật khẩu.' : 'Chào mừng bạn trở lại. Đăng nhập để tiếp tục hành trình tìm nơi an cư.'

  async function run(action) {
    setError('')
    setBusy(true)
    try { await action() } catch (problem) { setError(problem.message) } finally { setBusy(false) }
  }

  function goToAccount(user) {
    const targetRole = user.role === 'owner' ? 'owner' : 'tenant'
    notify(`Xin chào, ${user.name}.`)
    navigate(location.state?.role === targetRole ? location.state?.from ?? `/${targetRole}` : `/${targetRole}`, { replace: true })
  }

  async function requestOtp() {
    if (mode === 'register' && fields.password !== fields.confirm) throw new Error('Hai mật khẩu chưa trùng khớp.')
    const result = mode === 'register'
      ? await backendApi.requestRegistration({ name: fields.name.trim(), email: fields.email.trim(), password: fields.password })
      : await backendApi.requestPasswordReset({ email: fields.email.trim() })
    setDelivery(timedDelivery(result))
  }

  function submit(event) {
    event.preventDefault()
    run(async () => {
      if (mode === 'login') goToAccount(await loginWithCredentials(fields.email, fields.password))
      else await requestOtp()
    })
  }

  function verify(code) {
    run(async () => {
      if (mode === 'register') {
        await completeRegistration(delivery.challengeId, code)
        setFields({ name: '', email: '', password: '', confirm: '' })
        notify('Email đã được xác thực. Chào mừng bạn đến với An Cư!')
        navigate('/tenant/ho-so', { replace: true })
      } else {
        if (fields.password !== fields.confirm) throw new Error('Hai mật khẩu chưa trùng khớp.')
        await backendApi.confirmPasswordReset({ challengeId: delivery.challengeId, code, newPassword: fields.password })
        await login(null)
        navigate('/dang-nhap', { replace: true, state: { email: fields.email, message: 'Đã đặt lại mật khẩu. Hãy đăng nhập bằng mật khẩu mới.' } })
      }
    })
  }

  const demos = (db.accounts || people).filter((person) => people.some((sample) => sample.id === person.id))
  return <section className="frame login-page">
    <div className="login-image"><div className="login-image-caption"><span>AN CƯ · KHÔNG GIAN SỐNG CỦA BẠN</span><h2>Một tài khoản.<br />Một nơi để trở về.</h2><p>Lưu căn hộ yêu thích và quản lý hành trình thuê nhà trong một không gian riêng.</p></div></div>
    <div className="login-content">
      <Link className="back" to="/"><ArrowLeft size={17} /> Khám phá</Link>
      <h1>{title}</h1><p>{description}</p>
      {mode !== 'reset' && <div className="tabs auth-tabs" aria-label="Truy cập tài khoản"><Link className={mode === 'login' ? 'current' : ''} to="/dang-nhap" state={location.state}>Đăng nhập</Link><Link className={mode === 'register' ? 'current' : ''} to="/dang-ky" state={location.state}>Tạo tài khoản</Link></div>}
      {mode === 'login' && location.state?.message && <Notice>{location.state.message}</Notice>}
      {delivery ? <OtpStep delivery={delivery} email={fields.email.trim()} busy={busy} error={error} onVerify={verify} onResend={() => run(requestOtp)} onBack={() => { setDelivery(null); setError('') }}>
        {mode === 'reset' && <><PasswordField label="Mật khẩu mới" name="newPassword" minLength={8} maxLength={72} autoComplete="new-password" value={fields.password} onChange={set('password')} disabled={busy} /><PasswordField label="Nhập lại mật khẩu mới" name="confirmPassword" minLength={8} maxLength={72} autoComplete="new-password" value={fields.confirm} onChange={set('confirm')} disabled={busy} /></>}
      </OtpStep> : <form className="stack auth-form" onSubmit={submit}>
        {mode === 'register' && <Field label="Họ và tên"><input name="name" autoComplete="name" placeholder="Nhập họ tên của bạn" maxLength={150} value={fields.name} onChange={set('name')} required disabled={busy} /></Field>}
        <Field label={mode === 'login' ? 'Email hoặc mã tài khoản' : 'Địa chỉ email'}><input name="email" type={mode === 'login' ? 'text' : 'email'} autoComplete="username" placeholder={mode === 'login' ? 'Email của bạn' : 'Email thật có thể nhận mã OTP'} maxLength={150} value={fields.email} onChange={set('email')} required disabled={busy} /></Field>
        {mode !== 'reset' && <PasswordField name="password" autoComplete={mode === 'register' ? 'new-password' : 'current-password'} minLength={mode === 'register' ? 8 : undefined} maxLength={72} placeholder={mode === 'register' ? 'Ít nhất 8 ký tự' : 'Nhập mật khẩu'} value={fields.password} onChange={set('password')} disabled={busy} />}
        {mode === 'register' && <PasswordField label="Nhập lại mật khẩu" name="confirmPassword" autoComplete="new-password" minLength={8} maxLength={72} value={fields.confirm} onChange={set('confirm')} disabled={busy} />}
        {mode === 'login' && <Link className="text-button forgot-link" to="/quen-mat-khau">Quên mật khẩu?</Link>}
        {mode === 'register' && <p className="auth-security-hint"><ShieldCheck size={17} /> Email sẽ được xác thực bằng mã OTP trước khi tạo tài khoản.</p>}
        {error && <Notice error>{error}</Notice>}
        <button className="button primary wide" type="submit" disabled={busy}>{busy ? 'Đang xử lý…' : mode === 'login' ? 'Đăng nhập' : 'Gửi mã xác nhận'} <ArrowRight size={18} /></button>
      </form>}
      {mode === 'reset' && <Link className="back auth-back" to="/dang-nhap"><ArrowLeft size={17} /> Về đăng nhập</Link>}
      {mode === 'register' && <p className="auth-footnote">Đã có tài khoản? <Link to="/dang-nhap" state={location.state}>Đăng nhập</Link></p>}
      {mode === 'login' && <section className="demo-accounts" aria-label="Tài khoản demo">
        <div className="auth-divider"><span>Trải nghiệm tài khoản demo</span></div>
        <p className="subtle">Tài khoản mẫu dùng chung để khám phá tính năng. Mật khẩu <code>AnCu@123</code>.</p>
        <div className="tabs" aria-label="Vai trò demo">{[['tenant', 'Khách thuê', UserRound], ['owner', 'Chủ nhà', Building2]].map(([key, label, Icon]) => <button type="button" className={role === key ? 'current' : ''} aria-pressed={role === key} key={key} disabled={busy} onClick={() => setRole(key)}><Icon size={17} /> {label}</button>)}</div>
        <div className="accounts">{demos.filter((person) => person.role === role).map((person) => <button type="button" key={person.id} disabled={busy} onClick={() => run(async () => goToAccount(await login(person.id)))}><span className="avatar">{person.name.split(' ').at(-1)[0]}</span><span>{person.name}<small>{person.email}</small></span><span className="demo-badge">Demo</span></button>)}</div>
      </section>}
    </div>
  </section>
}

export function ProfilePage() {
  const { refreshFromBackend, login, notify } = useDemo()
  const navigate = useNavigate()
  const [profile, setProfile] = useState(null)
  const [fields, setFields] = useState({ name: '', phone: '', address: '', dateOfBirth: '' })
  const [passwords, setPasswords] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [delivery, setDelivery] = useState(null)
  const [error, setError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    backendApi.getProfile().then((data) => {
      if (!active) return
      setProfile(data)
      setFields({ name: data.name, phone: data.phone ?? '', address: data.address ?? '', dateOfBirth: data.dateOfBirth ?? '' })
    }).catch((problem) => { if (active) setError(problem.message) })
    return () => { active = false }
  }, [])
  const set = (key) => (event) => setFields((previous) => ({ ...previous, [key]: event.target.value }))
  const setPassword = (key) => (event) => setPasswords((previous) => ({ ...previous, [key]: event.target.value }))

  async function saveProfile(event) {
    event.preventDefault()
    setError('')
    setSaving(true)
    try {
      const data = await backendApi.updateProfile({ ...fields, dateOfBirth: fields.dateOfBirth || null })
      setProfile(data)
      await refreshFromBackend()
      notify('Đã lưu hồ sơ của bạn.')
    } catch (problem) { setError(problem.message) } finally { setSaving(false) }
  }

  async function requestChange() {
    if (passwords.newPassword !== passwords.confirm) throw new Error('Hai mật khẩu mới chưa trùng khớp.')
    setDelivery(timedDelivery(await backendApi.requestPasswordChange({ currentPassword: passwords.currentPassword, newPassword: passwords.newPassword })))
  }

  async function runPassword(action) {
    setPasswordError('')
    setBusy(true)
    try { await action() } catch (problem) { setPasswordError(problem.message) } finally { setBusy(false) }
  }

  async function verifyChange(code) {
    await backendApi.confirmPasswordChange({ challengeId: delivery.challengeId, code })
    setPasswords({ currentPassword: '', newPassword: '', confirm: '' })
    await login(null)
    navigate('/dang-nhap', { replace: true, state: { email: profile.email, message: 'Đã đổi mật khẩu và kết thúc các phiên đăng nhập cũ. Vui lòng đăng nhập lại.' } })
  }

  return <>
    <div className="page-head"><div><h1>Hồ sơ & bảo mật</h1><p>Cập nhật thông tin cá nhân và giữ tài khoản của bạn an toàn.</p></div></div>
    {!profile ? <Notice error={!!error}>{error || 'Đang tải hồ sơ…'}</Notice> : <div className="profile-grid">
      <section className="panel profile-panel">
        <div className="profile-summary"><span className="avatar">{profile.name.split(' ').at(-1)[0]}</span><div><h2>{profile.name}</h2><p>{profile.role === 'OWNER' ? 'Chủ nhà' : 'Khách thuê'} · {profile.demo ? 'Tài khoản demo' : 'Tài khoản cá nhân'}</p></div></div>
        {profile.demo && <Notice>Tài khoản demo dùng chung để trải nghiệm. Hãy <Link to="/dang-ky" className="text-button">tạo tài khoản riêng</Link> để lưu hồ sơ và thay đổi mật khẩu.</Notice>}
        <form className="stack" onSubmit={saveProfile}>
          <Field label="Họ và tên"><input name="name" autoComplete="name" maxLength={150} value={fields.name} onChange={set('name')} required disabled={saving || profile.demo} /></Field>
          <Field label="Email đăng nhập"><input type="email" value={profile.email} readOnly autoComplete="email" /></Field>
          <p className="email-verified">{profile.emailVerified ? <><CheckCircle2 size={16} /> Email đã xác thực</> : 'Email mẫu dành cho tài khoản demo.'}</p>
          <div className="profile-fields"><Field label="Số điện thoại"><input name="phone" type="tel" autoComplete="tel" placeholder="Thêm số điện thoại" maxLength={30} value={fields.phone} onChange={set('phone')} disabled={saving || profile.demo} /></Field><Field label="Ngày sinh"><input name="dateOfBirth" type="date" autoComplete="bday" value={fields.dateOfBirth} onChange={set('dateOfBirth')} disabled={saving || profile.demo} /></Field></div>
          <Field label="Địa chỉ liên hệ"><textarea name="address" autoComplete="street-address" maxLength={300} rows={3} placeholder="Thêm địa chỉ liên hệ của bạn" value={fields.address} onChange={set('address')} disabled={saving || profile.demo} /></Field>
          {error && <Notice error>{error}</Notice>}
          <button className="button primary" type="submit" disabled={saving || profile.demo}>{saving ? 'Đang lưu…' : 'Lưu thay đổi'}</button>
        </form>
      </section>
      <section className="panel profile-panel security-panel">
        <div className="security-title"><span className="step-icon"><ShieldCheck size={23} /></span><div><h2>Bảo mật tài khoản</h2><p>Đổi mật khẩu bằng xác nhận qua email.</p></div></div>
        {profile.demo ? <Notice>Tài khoản demo sử dụng mật khẩu chung <code>AnCu@123</code>.</Notice> : delivery ? <OtpStep delivery={delivery} email={profile.email} error={passwordError} busy={busy} onVerify={(code) => runPassword(() => verifyChange(code))} onResend={() => runPassword(requestChange)} onBack={() => { setDelivery(null); setPasswordError('') }} /> : <form className="stack" onSubmit={(event) => { event.preventDefault(); runPassword(requestChange) }}>
          <PasswordField label="Mật khẩu hiện tại" name="currentPassword" autoComplete="current-password" value={passwords.currentPassword} onChange={setPassword('currentPassword')} disabled={busy} />
          <PasswordField label="Mật khẩu mới" name="newPassword" autoComplete="new-password" placeholder="Ít nhất 8 ký tự" minLength={8} maxLength={72} value={passwords.newPassword} onChange={setPassword('newPassword')} disabled={busy} />
          <PasswordField label="Nhập lại mật khẩu mới" name="confirmPassword" autoComplete="new-password" minLength={8} maxLength={72} value={passwords.confirm} onChange={setPassword('confirm')} disabled={busy} />
          <p className="subtle">Mật khẩu chỉ thay đổi sau khi bạn xác nhận đúng mã OTP gửi đến email đã đăng ký.</p>
          {passwordError && <Notice error>{passwordError}</Notice>}
          <button className="button primary" type="submit" disabled={busy}>{busy ? 'Đang gửi…' : 'Gửi mã đổi mật khẩu'} <Mail size={17} /></button>
        </form>}
      </section>
    </div>}
  </>
}
