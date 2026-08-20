import http from 'http'
import crypto from 'crypto'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { URL } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const PORT = Number(process.env.PORT || 8080)
const JWT_SECRET = process.env.JWT_SECRET || 'kiuda-dev-secret-change-me'

// .env 간단 로드 (GEMINI_API_KEY=... 한 줄 형식)
try {
  const envPath = path.join(__dirname, '.env')
  if (fs.existsSync(envPath)) {
    for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/)
      if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '')
    }
  }
} catch {}

const GEMINI_API_KEY = process.env.GEMINI_API_KEY || ''
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-2.0-flash'
fs.mkdirSync(path.join(__dirname, 'uploads'), { recursive: true })

const SYMPTOM_MAP = {
  1: '잎 반점', 2: '잎 황화', 3: '시들음', 4: '흰가루', 5: '벌레 구멍', 6: '줄기 갈라짐',
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
}
function signJwt(payload, expSec = 604800) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const body = b64url(JSON.stringify({ ...payload, exp: Math.floor(Date.now() / 1000) + expSec }))
  const data = `${header}.${body}`
  const sig = crypto.createHmac('sha256', JWT_SECRET).update(data).digest('base64')
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${data}.${sig}`
}
function verifyJwt(token) {
  const parts = token.split('.')
  if (parts.length !== 3) throw new Error('bad token')
  const data = `${parts[0]}.${parts[1]}`
  const sig = crypto.createHmac('sha256', JWT_SECRET).update(data).digest('base64')
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  if (sig !== parts[2]) throw new Error('bad sig')
  const payload = JSON.parse(Buffer.from(parts[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString())
  if (payload.exp && payload.exp < Math.floor(Date.now() / 1000)) throw new Error('expired')
  return payload
}
function hashPw(pw) {
  return crypto.createHash('sha256').update(pw + JWT_SECRET).digest('hex')
}


async function loadImageAsBase64(imageUrl) {
  if (!imageUrl) return null
  try {
    // 로컬 업로드 파일
    if (imageUrl.includes('/uploads/')) {
      const key = imageUrl.split('/uploads/').pop()
      const filePath = path.join(__dirname, 'uploads', key)
      if (fs.existsSync(filePath)) {
        const buf = fs.readFileSync(filePath)
        return { mimeType: 'image/jpeg', data: buf.toString('base64') }
      }
    }
    // http(s) URL fetch
    if (imageUrl.startsWith('http://') || imageUrl.startsWith('https://')) {
      const res = await fetch(imageUrl)
      if (!res.ok) return null
      const buf = Buffer.from(await res.arrayBuffer())
      const ct = res.headers.get('content-type') || 'image/jpeg'
      return { mimeType: ct.split(';')[0], data: buf.toString('base64') }
    }
  } catch (e) {
    console.error('loadImageAsBase64:', e.message)
  }
  return null
}

function extractJson(text) {
  if (!text) return null
  const cleaned = text.replace(/```json\\s*/gi, '').replace(/```/g, '').trim()
  try { return JSON.parse(cleaned) } catch {}
  const m = cleaned.match(/\\{[\\s\\S]*\\}/)
  if (m) {
    try { return JSON.parse(m[0]) } catch {}
  }
  return null
}

async function diagnoseWithGemini({ imageUrl, symptomTagIds = [], question }) {
  if (!GEMINI_API_KEY) {
    return { usedGemini: false, error: 'GEMINI_API_KEY 없음' }
  }

  const symptoms = (symptomTagIds || []).map((id) => SYMPTOM_MAP[id] || String(id)).filter(Boolean)
  const prompt = `당신은 식물 병해 보조 진단 전문가입니다. 제공된 작물 사진을 보고 가능한 병해/생리장해를 추정하세요.
주의: 확진이 아니며 참고용입니다. 농약 처방은 전문가 확인이 필요합니다.

선택한 증상 태그: ${symptoms.length ? symptoms.join(', ') : '없음'}
${question ? '사용자 질문: ' + question : ''}

반드시 아래 JSON만 출력하세요. 다른 설명 문장 없이 JSON만:
{
  "diagnosisName": "진단명 (한글)",
  "confidence": 0.0,
  "reason": "진단 이유를 2~4문장으로",
  "managementMethods": ["관리법1", "관리법2", "관리법3"]
}
confidence는 0과 1 사이 숫자입니다. 사진이 흐리거나 작물이 아니면 diagnosisName을 "진단 불가"로 하고 confidence를 낮게 주세요.`

  const parts = [{ text: prompt }]
  const img = await loadImageAsBase64(imageUrl)
  if (img) {
    parts.push({ inline_data: { mime_type: img.mimeType, data: img.data } })
  } else {
    parts[0].text += '\\n(참고: 이미지 파일을 읽지 못했습니다. 증상 태그만으로 일반적인 가능성을 제시하세요.)'
  }

  const url = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${encodeURIComponent(GEMINI_API_KEY)}`
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ role: 'user', parts }],
      generationConfig: {
        temperature: 0.3,
        maxOutputTokens: 1024,
      },
    }),
  })

  const raw = await res.json()
  if (!res.ok) {
    console.error('Gemini error:', JSON.stringify(raw).slice(0, 500))
    return { usedGemini: false, error: raw?.error?.message || `Gemini HTTP ${res.status}` }
  }

  const text = raw?.candidates?.[0]?.content?.parts?.map((p) => p.text || '').join('') || ''
  const parsed = extractJson(text)
  if (!parsed) {
    return {
      usedGemini: true,
      result: {
        diagnosisName: '분석 결과 파싱 실패',
        confidence: 0.3,
        reason: text.slice(0, 400) || '모델 응답을 해석하지 못했습니다.',
        managementMethods: ['사진을 다시 촬영해 주세요', '잎이 화면 중앙에 오도록 찍어 주세요'],
      },
    }
  }

  let confidence = Number(parsed.confidence)
  if (Number.isNaN(confidence)) confidence = 0.5
  if (confidence > 1) confidence = confidence / 100
  confidence = Math.max(0, Math.min(1, confidence))

  const methods = Array.isArray(parsed.managementMethods)
    ? parsed.managementMethods.map(String)
    : []

  return {
    usedGemini: true,
    result: {
      diagnosisName: String(parsed.diagnosisName || '알 수 없음'),
      confidence,
      reason: String(parsed.reason || ''),
      managementMethods: methods.length ? methods : ['통풍 개선', '물 주기 조절', '병든 부위 제거'],
    },
  }
}

const users = new Map()
const plants = new Map()
const checklists = new Map()
const diagnoses = new Map()
let nextUserId = 2, nextCheckId = 4, nextDiagId = 1

users.set('demo@kiuda.com', {
  id: 1, email: 'demo@kiuda.com', username: 'demo@kiuda.com',
  passwordHash: hashPw('password123'), name: '데모유저', nickname: '초록이', role: 'USER',
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

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const raw = Buffer.concat(chunks)
      const ct = req.headers['content-type'] || ''
      if (ct.includes('application/json')) {
        try { resolve(raw.length ? JSON.parse(raw.toString('utf8')) : {}) } catch { resolve({}) }
      } else resolve(raw)
    })
    req.on('error', reject)
  })
}
function send(res, status, data) {
  const body = typeof data === 'string' ? data : JSON.stringify(data)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS',
  })
  res.end(body)
}
function auth(req) {
  const h = req.headers.authorization || ''
  if (!h.startsWith('Bearer ')) return null
  try { return verifyJwt(h.slice(7)) } catch { return null }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') return send(res, 204, '')
  const u = new URL(req.url || '/', `http://localhost:${PORT}`)
  const p = u.pathname
  const method = req.method || 'GET'
  try {
    // 루트: 브라우저에서 열었을 때 안내 페이지
    if (method === 'GET' && (p === '/' || p === '/api' || p === '/api/')) {
      const html = `<!DOCTYPE html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>키:우다 API 서버</title>
<style>
  body{font-family:system-ui,sans-serif;background:#eef3ea;color:#2c3a28;margin:0;padding:40px 20px;line-height:1.6}
  .card{max-width:560px;margin:0 auto;background:#fff;border-radius:16px;padding:28px;box-shadow:0 8px 24px rgba(0,0,0,.06)}
  h1{margin:0 0 8px;font-size:1.5rem} .ok{color:#56b968;font-weight:700}
  code{background:#f0f5ec;padding:2px 8px;border-radius:6px;font-size:.9rem}
  ul{padding-left:1.2rem} a{color:#4f7a3e}
</style></head><body><div class="card">
  <h1>🌱 키:우다 개발 API 서버</h1>
  <p class="ok">● 서버 정상 동작 중</p>
  <p>이 주소는 <b>REST API 전용</b>입니다. 웹 페이지가 아니라 Android/프론트가 호출하는 서버입니다.</p>
  <p><b>헬스 체크:</b> <a href="/api/health"><code>/api/health</code></a></p>
  <p><b>데모 계정:</b> <code>demo@kiuda.com</code> / <code>password123</code></p>
  <p><b>Android 에뮬레이터 Base URL:</b><br><code>http://10.0.2.2:8080/api/</code></p>
  <p><b>PC 브라우저 / 실기기:</b><br><code>http://localhost:8080/api/</code> 또는 PC의 LAN IP</p>
  <hr style="border:none;border-top:1px solid #d5e6c8;margin:20px 0">
  <p style="font-size:.9rem;color:#5c6b55">주요 경로: /api/auth/login · /api/auth/signup · /api/plants · /api/ai/diagnose</p>
</div></body></html>`
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
      })
      return res.end(html)
    }

    if (method === 'GET' && p === '/api/health') return send(res, 200, { ok: true, service: 'kiuda-dev-server' })

    if (method === 'POST' && p === '/api/auth/login') {
      const body = await readBody(req)
      const key = String(body.email || body.username || '').trim().toLowerCase()
      const user = users.get(key)
      if (!user || user.passwordHash !== hashPw(body.password || '')) {
        return send(res, 401, { message: '아이디 또는 비밀번호가 올바르지 않습니다.' })
      }
      const token = signJwt({ userId: user.id, email: user.email, username: user.username })
      return send(res, 200, { jwt: token, token, username: user.username, name: user.name, nickname: user.nickname, role: user.role, message: '로그인 성공' })
    }

    if (method === 'POST' && p === '/api/auth/signup') {
      const body = await readBody(req)
      const key = String(body.email || body.username || '').trim().toLowerCase()
      const name = String(body.name || '').trim()
      if (!key || !body.password || !name) return send(res, 400, { message: '이메일, 비밀번호, 이름은 필수입니다.' })
      if (String(body.password).length < 8) return send(res, 400, { message: '비밀번호는 8자 이상이어야 합니다.' })
      if (users.has(key)) return send(res, 409, { message: '이미 사용 중인 이메일입니다.' })
      const id = nextUserId++
      const user = { id, email: key, username: key, passwordHash: hashPw(body.password), name, nickname: body.nickname || name, role: 'USER' }
      users.set(key, user)
      plants.set(id, [])
      checklists.set(id, [{ id: nextCheckId++, title: '첫 작물 등록하기', completed: false, plantId: null, dueDate: new Date().toISOString().slice(0, 10) }])
      const token = signJwt({ userId: id, email: key, username: key })
      return send(res, 201, { jwt: token, token, username: key, name, nickname: user.nickname, role: 'USER', message: '회원가입 성공' })
    }

    if (method === 'POST' && p === '/api/auth/check-email') {
      const body = await readBody(req)
      const email = String(body.email || '').trim().toLowerCase()
      return send(res, 200, { available: email.length > 0 && !users.has(email) })
    }

    const needAuth = p.startsWith('/api/') && !p.startsWith('/api/auth/') && p !== '/api/health'
    let userPayload = null
    if (needAuth) {
      userPayload = auth(req)
      if (!userPayload) return send(res, 401, { message: '인증이 필요합니다.' })
    }

    if (method === 'GET' && p === '/api/user/me') {
      const user = [...users.values()].find((x) => x.id === userPayload.userId)
      if (!user) return send(res, 404, { message: '유저 없음' })
      return send(res, 200, { username: user.username, name: user.name, nickname: user.nickname, role: user.role })
    }
    if (method === 'GET' && p === '/api/plants') return send(res, 200, plants.get(userPayload.userId) || [])
    if (method === 'GET' && p === '/api/checklist') return send(res, 200, checklists.get(userPayload.userId) || [])
    if (method === 'PUT' && p.startsWith('/api/checklist/') && p.endsWith('/complete')) {
      const id = Number(p.split('/')[3])
      const list = checklists.get(userPayload.userId) || []
      const item = list.find((c) => c.id === id)
      if (!item) return send(res, 404, { message: '항목 없음' })
      item.completed = true
      return send(res, 200, item)
    }
    if (method === 'GET' && p === '/api/weather') return send(res, 200, { temperature: 28, condition: '맑음', humidity: 55, location: '서울' })
    if (method === 'GET' && p === '/api/pest-alerts') return send(res, 200, [{ id: 1, title: '토마토 잎곰팡이 주의', level: 'MEDIUM', description: '습도가 높을 때 발생하기 쉽습니다.' }])
    if (method === 'GET' && p === '/api/symptoms') {
      return send(res, 200, [
        { id: 1, name: '잎 반점', category: '잎' }, { id: 2, name: '잎 황화', category: '잎' },
        { id: 3, name: '시들음', category: '전체' }, { id: 4, name: '흰가루', category: '잎' },
        { id: 5, name: '벌레 구멍', category: '잎' }, { id: 6, name: '줄기 갈라짐', category: '줄기' },
      ])
    }
    if (method === 'POST' && p === '/api/upload/presigned') {
      const body = await readBody(req)
      const fileName = body.fileName || `${crypto.randomUUID()}.jpg`
      const key = `uploads/${Date.now()}_${fileName}`
      return send(res, 200, {
        uploadUrl: `http://localhost:${PORT}/api/upload/direct?key=${encodeURIComponent(key)}`,
        fileUrl: `http://localhost:${PORT}/${key}`, key,
      })
    }
    if (method === 'PUT' && p === '/api/upload/direct') {
      const key = u.searchParams.get('key') || `uploads/${Date.now()}.jpg`
      const raw = await readBody(req)
      const dest = path.join(__dirname, key)
      fs.mkdirSync(path.dirname(dest), { recursive: true })
      fs.writeFileSync(dest, Buffer.isBuffer(raw) ? raw : Buffer.from(String(raw)))
      return send(res, 200, { ok: true, key })
    }
    if (method === 'POST' && p === '/api/ai/diagnose') {
      const body = await readBody(req)
      const id = nextDiagId++
      const gemini = await diagnoseWithGemini({
        imageUrl: body.imageUrl,
        symptomTagIds: body.symptomTagIds || body.symptomIds || [],
        question: body.question,
      })

      let result
      if (gemini.usedGemini && gemini.result) {
        result = {
          id,
          diagnosisName: gemini.result.diagnosisName,
          confidence: gemini.result.confidence,
          reason: gemini.result.reason,
          managementMethods: gemini.result.managementMethods,
          steps: gemini.result.managementMethods.map((m, i) => ({
            step: i + 1, title: `단계 ${i + 1}`, description: m,
          })),
          imageUrl: body.imageUrl || null,
          provider: 'gemini',
          model: GEMINI_MODEL,
        }
      } else {
        // API 키 없거나 실패 시 안전한 데모 결과
        result = {
          id,
          diagnosisName: '토마토 잎곰팡이병 (데모)',
          confidence: 0.87,
          reason: (gemini.error ? `[Gemini 미사용: ${gemini.error}] ` : '') +
            '업로드된 이미지의 잎 반점·변색 패턴과 선택한 증상 태그가 토마토 잎곰팡이병(Leaf Mold)과 유사합니다. .env에 GEMINI_API_KEY를 설정하면 실제 AI 진단이 동작합니다.',
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
          imageUrl: body.imageUrl || null,
          provider: 'demo',
        }
      }
      diagnoses.set(id, result)
      return send(res, 200, result)
    }
    if (method === 'GET' && p.startsWith('/api/ai/diagnose/')) {
      const id = Number(p.split('/').pop())
      const r = diagnoses.get(id)
      if (!r) return send(res, 404, { message: '결과 없음' })
      return send(res, 200, r)
    }
    send(res, 404, { message: `Not found: ${method} ${p}` })
  } catch (e) {
    send(res, 500, { message: e.message || 'server error' })
  }
})

server.listen(PORT, () => {
  console.log(`키:우다 개발 서버 http://localhost:${PORT}`)
  console.log(`데모: demo@kiuda.com / password123`)
  console.log(`Gemini: ${GEMINI_API_KEY ? 'ON (' + GEMINI_MODEL + ')' : 'OFF (.env에 GEMINI_API_KEY 설정)'}`)
})
