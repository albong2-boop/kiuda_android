/**
 * 키:우다 개발용 API 서버
 * - 메모리 DB (재시작 시 초기화)
 * - Android / 웹 Phase 1 엔드포인트 전부 포함
 * - 실행: npm install && npm start  →  http://localhost:8080
 */
import express from 'express'
import cors from 'cors'
import bcrypt from 'bcryptjs'
import jwt from 'jsonwebtoken'
import { randomUUID } from 'crypto'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const app = express()
const PORT = process.env.PORT || 8080
const JWT_SECRET = process.env.JWT_SECRET || 'kiuda-dev-secret-change-me'

app.use(cors())
app.use(express.json({ limit: '10mb' }))
app.use('/uploads', express.static(path.join(__dirname, 'uploads')))

// ensure uploads dir
fs.mkdirSync(path.join(__dirname, 'uploads'), { recursive: true })

// ===== In-memory store =====
const users = new Map() // email -> user
const plants = new Map() // userId -> UserPlant[]
const checklists = new Map()
const diagnoses = new Map()

// seed demo user
const seedHash = bcrypt.hashSync('password123', 10)
users.set('demo@kiuda.com', {
  id: 1,
  email: 'demo@kiuda.com',
  username: 'demo@kiuda.com',
  passwordHash: seedHash,
  name: '데모유저',
  nickname: '초록이',
  role: 'USER',
})
plants.set(1, [
  { id: 1, name: '방울토마토', plantType: '토마토', nickname: '토토', imageUrl: null, location: '베란다', createdAt: new Date().toISOString() },
  { id: 2, name: '청양고추', plantType: '고추', nickname: '맵이', imageUrl: null, location: '텃밭', createdAt: new Date().toISOString() },
])
checklists.set(1, [
  { id: 1, title: '오늘 물 주기', completed: false, plantId: 1, dueDate: new Date().toISOString().slice(0, 10) },
  { id: 2, title: '잎 상태 확인', completed: false, plantId: 1, dueDate: new Date().toISOString().slice(0, 10) },
  { id: 3, title: '비료 주기', completed: false, plantId: 2, dueDate: new Date().toISOString().slice(0, 10) },
])

let nextUserId = 2
let nextPlantId = 3
let nextCheckId = 4
let nextDiagId = 1

function signToken(user) {
  return jwt.sign({ userId: user.id, email: user.email, username: user.username }, JWT_SECRET, { expiresIn: '7d' })
}

function auth(req, res, next) {
  const h = req.headers.authorization
  if (!h?.startsWith('Bearer ')) return res.status(401).json({ message: '인증이 필요합니다.' })
  try {
    req.user = jwt.verify(h.slice(7), JWT_SECRET)
    next()
  } catch {
    return res.status(401).json({ message: '토큰이 유효하지 않습니다.' })
  }
}

// ===== Auth =====
app.post('/api/auth/login', async (req, res) => {
  const { username, email, password } = req.body || {}
  const key = (email || username || '').trim().toLowerCase()
  if (!key || !password) return res.status(400).json({ message: '이메일과 비밀번호를 입력해주세요.' })
  const user = users.get(key)
  if (!user || !(await bcrypt.compare(password, user.passwordHash))) {
    return res.status(401).json({ message: '아이디 또는 비밀번호가 올바르지 않습니다.' })
  }
  const token = signToken(user)
  res.json({
    jwt: token,
    token,
    username: user.username,
    name: user.name,
    nickname: user.nickname,
    role: user.role,
    message: '로그인 성공',
  })
})

app.post('/api/auth/signup', async (req, res) => {
  const { username, email, password, name, nickname } = req.body || {}
  const key = (email || username || '').trim().toLowerCase()
  if (!key || !password || !name) {
    return res.status(400).json({ message: '이메일, 비밀번호, 이름은 필수입니다.' })
  }
  if (password.length < 8) return res.status(400).json({ message: '비밀번호는 8자 이상이어야 합니다.' })
  if (users.has(key)) return res.status(409).json({ message: '이미 사용 중인 이메일입니다.' })

  const id = nextUserId++
  const user = {
    id,
    email: key,
    username: key,
    passwordHash: await bcrypt.hash(password, 10),
    name,
    nickname: nickname || name,
    role: 'USER',
  }
  users.set(key, user)
  plants.set(id, [])
  checklists.set(id, [
    { id: nextCheckId++, title: '첫 작물 등록하기', completed: false, plantId: null, dueDate: new Date().toISOString().slice(0, 10) },
  ])
  const token = signToken(user)
  res.status(201).json({
    jwt: token,
    token,
    username: user.username,
    name: user.name,
    nickname: user.nickname,
    role: user.role,
    message: '회원가입 성공',
  })
})

app.post('/api/auth/check-email', (req, res) => {
  const email = (req.body?.email || '').trim().toLowerCase()
  res.json({ available: email.length > 0 && !users.has(email) })
})

app.get('/api/user/me', auth, (req, res) => {
  const user = [...users.values()].find((u) => u.id === req.user.userId)
  if (!user) return res.status(404).json({ message: '유저 없음' })
  res.json({ username: user.username, name: user.name, nickname: user.nickname, role: user.role })
})

// ===== Dashboard =====
app.get('/api/plants', auth, (req, res) => {
  res.json(plants.get(req.user.userId) || [])
})

app.get('/api/checklist', auth, (req, res) => {
  res.json(checklists.get(req.user.userId) || [])
})

app.put('/api/checklist/:id/complete', auth, (req, res) => {
  const list = checklists.get(req.user.userId) || []
  const item = list.find((c) => c.id === Number(req.params.id))
  if (!item) return res.status(404).json({ message: '항목 없음' })
  item.completed = true
  res.json(item)
})

app.get('/api/weather', auth, (_req, res) => {
  res.json({ temperature: 28, condition: '맑음', humidity: 55, location: '서울' })
})

app.get('/api/pest-alerts', auth, (_req, res) => {
  res.json([
    { id: 1, title: '토마토 잎곰팡이 주의', level: 'MEDIUM', description: '습도가 높을 때 발생하기 쉽습니다.' },
  ])
})

// ===== AI / Upload =====
app.get('/api/symptoms', auth, (_req, res) => {
  res.json([
    { id: 1, name: '잎 반점', category: '잎' },
    { id: 2, name: '잎 황화', category: '잎' },
    { id: 3, name: '시들음', category: '전체' },
    { id: 4, name: '흰가루', category: '잎' },
    { id: 5, name: '벌레 구멍', category: '잎' },
    { id: 6, name: '줄기 갈라짐', category: '줄기' },
  ])
})

app.post('/api/upload/presigned', auth, (req, res) => {
  const fileName = req.body?.fileName || `${randomUUID()}.jpg`
  const key = `uploads/${Date.now()}_${fileName}`
  // 개발용: 클라이언트가 직접 PUT 하는 대신 동일 서버로 업로드하도록 안내
  // 실제 클라우드라면 S3 presigned URL
  const uploadUrl = `http://localhost:${PORT}/api/upload/direct?key=${encodeURIComponent(key)}`
  const fileUrl = `http://localhost:${PORT}/${key}`
  res.json({ uploadUrl, fileUrl, key })
})

app.put('/api/upload/direct', auth, express.raw({ type: '*/*', limit: '15mb' }), (req, res) => {
  const key = req.query.key || `uploads/${Date.now()}.jpg`
  const dest = path.join(__dirname, key)
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  fs.writeFileSync(dest, req.body)
  res.json({ ok: true, key })
})

app.post('/api/ai/diagnose', auth, (req, res) => {
  const { imageUrl, symptomTagIds, question } = req.body || {}
  const id = nextDiagId++
  const result = {
    id,
    diagnosisName: '토마토 잎곰팡이병',
    confidence: 0.87,
    reason:
      '업로드된 이미지의 잎 반점·변색 패턴과 선택한 증상 태그가 토마토 잎곰팡이병(Leaf Mold)과 유사합니다.' +
      (question ? ` (질문: ${question})` : ''),
    managementMethods: [
      '감염된 잎을 즉시 제거하세요',
      '통풍이 잘 되도록 가지치기를 하세요',
      '물 주기 시 잎에 물이 닿지 않게 하세요',
      '필요 시 등록 살균제를 안내서대로 사용하세요',
    ],
    steps: [
      { step: 1, title: '감염 잎 제거', description: '병든 잎은 분리 폐기' },
      { step: 2, title: '환경 개선', description: '습도·통풍 조절' },
    ],
    imageUrl: imageUrl || null,
  }
  diagnoses.set(id, result)
  res.json(result)
})

app.get('/api/ai/diagnose/:id', auth, (req, res) => {
  const r = diagnoses.get(Number(req.params.id))
  if (!r) return res.status(404).json({ message: '결과 없음' })
  res.json(r)
})

app.get('/api/health', (_req, res) => res.json({ ok: true, service: 'kiuda-dev-server' }))

app.listen(PORT, () => {
  console.log(`🌱 키:우다 개발 서버 http://localhost:${PORT}`)
  console.log(`   데모 계정: demo@kiuda.com / password123`)
})
