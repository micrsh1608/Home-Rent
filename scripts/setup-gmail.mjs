import { writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline/promises'
import { Writable } from 'node:stream'

const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const configPath = resolve(rootDir, 'apartment-rental-backend', 'smtp.local.properties')

if (!process.stdin.isTTY || !process.stdout.isTTY) {
  console.error('Hãy chạy npm.cmd run setup:email trong Terminal/PowerShell trên máy để nhập Gmail và mật khẩu ứng dụng.')
  process.exit(1)
}

let hidden = false
const output = new Writable({
  write(chunk, encoding, callback) {
    if (!hidden) process.stdout.write(chunk, encoding)
    callback()
  },
})
output.isTTY = true
output.columns = process.stdout.columns || 80
const prompt = createInterface({ input: process.stdin, output, terminal: true })

try {
  console.log('Thiết lập Gmail gửi OTP cho An Cư')
  console.log('1. Bật Xác minh 2 bước trong tài khoản Google gửi thư.')
  console.log('2. Tạo mật khẩu ứng dụng tại https://myaccount.google.com/apppasswords')
  console.log('Thông tin được lưu cục bộ trong tệp đã được loại khỏi Git.\n')
  const email = (await prompt.question('Gmail dùng để gửi OTP: ')).trim().toLowerCase()
  if (!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@(gmail\.com|googlemail\.com)$/.test(email)) {
    throw new Error('Vui lòng nhập địa chỉ Gmail đầy đủ, ví dụ tenban@gmail.com.')
  }
  process.stdout.write('Mật khẩu ứng dụng Google gồm 16 ký tự (nội dung sẽ được ẩn): ')
  hidden = true
  let secret = await prompt.question('')
  hidden = false
  process.stdout.write('\n')
  secret = secret.replace(/\s/g, '')
  if (!/^[a-zA-Z0-9]{16}$/.test(secret)) {
    throw new Error('Mật khẩu ứng dụng Google cần 16 ký tự. Hãy tạo mã tại trang Mật khẩu ứng dụng của Google.')
  }
  const config = [
    '# Local Gmail SMTP credentials. Do not commit or share this file.',
    'spring.mail.host=smtp.gmail.com',
    'spring.mail.port=587',
    `spring.mail.username=${email}`,
    `spring.mail.password=${secret}`,
    `ancu.mail.from=${email}`,
    'spring.mail.properties.mail.smtp.auth=true',
    'spring.mail.properties.mail.smtp.starttls.enable=true',
    'spring.mail.properties.mail.smtp.starttls.required=true',
    'spring.mail.properties.mail.smtp.ssl.enable=false',
    '',
  ].join('\n')
  writeFileSync(configPath, config, { encoding: 'utf8', mode: 0o600 })
  secret = ''
  console.log('\nĐã lưu cấu hình Gmail. Chưa gửi email thử nghiệm.')
  console.log('Dừng và khởi động lại backend để nạp cấu hình, sau đó thử Gửi mã xác nhận trên website.')
  console.log('Dùng npm.cmd run dev:sqlserver nếu cơ sở dữ liệu SQL Server của bạn đã sẵn sàng.')
} catch (error) {
  hidden = false
  console.error(`\nChưa hoàn tất thiết lập: ${error.message}`)
  process.exitCode = 1
} finally {
  hidden = false
  prompt.close()
}
