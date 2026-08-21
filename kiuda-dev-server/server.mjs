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
const GEMINI_PREDICT_MODEL = process.env.GEMINI_PREDICT_MODEL || process.env.GEMINI_MODEL || 'gemini-2.0-flash'
const GEMINI_CACHE_TTL_MS = Number(process.env.GEMINI_CACHE_TTL_MS || 10 * 60 * 1000) // 10분

/** 간단한 메모리 캐시 (predict/diagnose 중복 호출 방지) */
const aiCache = new Map()
function cacheGet(key) {
  const hit = aiCache.get(key)
  if (!hit) return null
  if (Date.now() - hit.at > GEMINI_CACHE_TTL_MS) {
    aiCache.delete(key)
    return null
  }
  return hit.data
}
function cacheSet(key, data) {
  aiCache.set(key, { at: Date.now(), data })
  // 간단 상한
  if (aiCache.size > 200) {
    const oldest = [...aiCache.entries()].sort((a, b) => a[1].at - b[1].at)[0]
    if (oldest) aiCache.delete(oldest[0])
  }
}
function cacheKey(parts) {
  return crypto.createHash('sha256').update(parts.filter(Boolean).join('|')).digest('hex')
}
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


async function loadImageAsBase64(imageUrl, inlineBase64) {
  // 1) 앱에서 직접 보낸 base64 (가장 확실)
  if (inlineBase64 && typeof inlineBase64 === 'string' && inlineBase64.length > 100) {
    const cleaned = inlineBase64.replace(/^data:image\/[a-zA-Z+]+;base64,/, '')
    return { mimeType: 'image/jpeg', data: cleaned }
  }
  if (!imageUrl) return null
  try {
    // 2) 로컬 uploads 파일
    let rel = null
    if (imageUrl.includes('/uploads/')) {
      rel = imageUrl.split('/uploads/').pop().split('?')[0]
    } else if (imageUrl.startsWith('uploads/')) {
      rel = imageUrl.split('?')[0].replace(/^uploads\//, '')
    }
    if (rel) {
      const filePath = path.join(__dirname, 'uploads', decodeURIComponent(rel))
      if (fs.existsSync(filePath)) {
        const buf = fs.readFileSync(filePath)
        if (buf.length > 0) {
          console.log('[img] local', filePath, buf.length, 'bytes')
          return { mimeType: 'image/jpeg', data: buf.toString('base64') }
        }
      } else {
        console.warn('[img] missing file', filePath)
      }
    }
    // 3) HTTP fetch (localhost → 자기 자신 파일로 재시도)
    if (imageUrl.startsWith('http://') || imageUrl.startsWith('https://')) {
      try {
        const res = await fetch(imageUrl)
        if (res.ok) {
          const buf = Buffer.from(await res.arrayBuffer())
          if (buf.length > 0) {
            const ct = res.headers.get('content-type') || 'image/jpeg'
            console.log('[img] fetched', imageUrl, buf.length, 'bytes')
            return { mimeType: ct.split(';')[0], data: buf.toString('base64') }
          }
        }
      } catch (e) {
        console.warn('[img] fetch fail', e.message)
      }
    }
  } catch (e) {
    console.error('loadImageAsBase64:', e.message)
  }
  return null
}

function extractJson(text) {
  if (!text) return null
  let cleaned = String(text)
    .replace(/```json\s*/gi, '')
    .replace(/```\s*/g, '')
    .replace(/[\u201C\u201D\u201E\u201F]/g, '"')
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/,\s*([}\]])/g, '$1') // trailing comma
    .trim()

  const tryParse = (s) => {
    try { return JSON.parse(s) } catch { return null }
  }

  let obj = tryParse(cleaned)
  if (obj) return obj

  const start = cleaned.indexOf('{')
  const end = cleaned.lastIndexOf('}')
  if (start >= 0 && end > start) {
    let slice = cleaned.slice(start, end + 1)
    obj = tryParse(slice)
    if (obj) return obj
    // 잘린 JSON 복구
    let fixed = slice
    if ((fixed.match(/"/g) || []).length % 2 === 1) fixed += '"'
    const openSq = (fixed.match(/\[/g) || []).length - (fixed.match(/\]/g) || []).length
    const openBr = (fixed.match(/\{/g) || []).length - (fixed.match(/\}/g) || []).length
    fixed += ']'.repeat(Math.max(0, openSq))
    fixed += '}'.repeat(Math.max(0, openBr))
    fixed = fixed.replace(/,\s*([}\]])/g, '$1')
    obj = tryParse(fixed)
    if (obj) return obj
  }

  // 최후: 필드 단위 정규식 추출 (모델이 JSON을 약간 깨뜨린 경우)
  const pickStr = (key) => {
    const re = new RegExp('"' + key + '"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)"', 'i')
    const m = cleaned.match(re)
    return m ? m[1].replace(/\\n/g, '\n').replace(/\\"/g, '"') : null
  }
  const pickNum = (key) => {
    const re = new RegExp('"' + key + '"\\s*:\\s*([0-9.]+)', 'i')
    const m = cleaned.match(re)
    return m ? Number(m[1]) : null
  }
  const pickArr = (key) => {
    const re = new RegExp('"' + key + '"\\s*:\\s*\\[([\\s\\S]*?)\\]', 'i')
    const m = cleaned.match(re)
    if (!m) return null
    const items = []
    const reItem = /"((?:\\.|[^"\\])*)"/g
    let im
    while ((im = reItem.exec(m[1]))) items.push(im[1].replace(/\\"/g, '"'))
    return items.length ? items : null
  }

  const diagnosisName = pickStr('diagnosisName')
  const reason = pickStr('reason')
  const greeting = pickStr('greeting')
  const closing = pickStr('closing')
  const confidence = pickNum('confidence')
  const methods = pickArr('managementMethods') || pickArr('managementMethod')

  if (diagnosisName || reason) {
    return {
      greeting: greeting || undefined,
      diagnosisName: diagnosisName || '진단 결과',
      confidence: confidence != null ? confidence : 0.6,
      reason: reason || '',
      managementMethods: methods || [],
      closing: closing || undefined,
    }
  }
  return null
}


async function callGemini({ model, parts, temperature = 0.3, maxOutputTokens = 1024 }) {
  if (!GEMINI_API_KEY) throw new Error('GEMINI_API_KEY 없음')
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(GEMINI_API_KEY)}`
  let lastErr = null
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ role: 'user', parts }],
        generationConfig: { temperature, maxOutputTokens },
      }),
    })
    const raw = await res.json()
    if (res.ok) {
      const text = raw?.candidates?.[0]?.content?.parts?.map((p) => p.text || '').join('') || ''
      return { text, raw }
    }
    const msg = raw?.error?.message || `Gemini HTTP ${res.status}`
    lastErr = new Error(msg)
    // 429 / resource exhausted → 짧은 백오프
    if (res.status === 429 || /quota|rate|resource/i.test(msg)) {
      await new Promise((r) => setTimeout(r, (attempt + 1) * 1000))
      continue
    }
    throw lastErr
  }
  throw lastErr || new Error('Gemini 호출 실패')
}

async function diagnoseWithGemini({ imageUrl, symptomTagIds = [], question, imageBase64 }) {
  if (!GEMINI_API_KEY) {
    return { usedGemini: false, error: 'GEMINI_API_KEY 없음' }
  }

  const symptoms = (symptomTagIds || []).map((id) => SYMPTOM_MAP[id] || String(id)).filter(Boolean)
  const userQ = question || (symptoms.length ? symptoms.join(', ') : '')
  const key = cacheKey(['diag', imageUrl, userQ, GEMINI_MODEL])
  const cached = cacheGet(key)
  if (cached) {
    return { usedGemini: true, result: cached, cached: true }
  }

  const prompt = `당신은 "온새미"입니다. 식물 질병 분석 전문가이며, 모바일 앱 사용자에게 친근하고 차분하게 설명합니다.
역할: 작물 사진과 사용자 질문을 보고 가능한 병해·생리장해를 추정하고, 바로 실천할 수 있는 해결책을 제시합니다.
말투: 존댓말, 짧고 읽기 쉬운 문장, 이모지는 최소.
주의: 확진이 아닙니다. 농약·처치는 전문가 확인을 안내합니다.

사용자 선택/질문: ${userQ || '없음'}

마크다운 코드블록 없이, 순수 JSON 객체만 출력하세요. 다른 문장 금지:
{
  "greeting": "한두 문장 인사 (온새미 소개 포함)",
  "diagnosisName": "추정 진단명 (한글, 간결하게)",
  "confidence": 0.0,
  "reason": "분석 내용 3~5문장. 문단으로 읽기 쉽게. 전문용어는 쉽게 풀어 쓰기.",
  "managementMethods": ["실천 팁1", "실천 팁2", "실천 팁3", "실천 팁4"],
  "closing": "따뜻한 마무리 한두 문장 + 전문가 확인 안내"
}
confidence는 0~1. 사진이 부적절하면 diagnosisName을 "진단이 어려워요"로 하고 confidence를 낮추세요.`

  const parts = [{ text: prompt }]
  const img = await loadImageAsBase64(imageUrl, imageBase64)
  if (img) {
    parts.push({ inline_data: { mime_type: img.mimeType, data: img.data } })
    console.log('[diag] image attached', img.data.length, 'b64 chars')
  } else {
    console.warn('[diag] NO IMAGE — Gemini will not see the photo')
    parts[0].text += '\n(참고: 이미지 파일을 읽지 못했습니다. 증상 태그만으로 일반적인 가능성을 제시하세요.)'
  }

  try {
    const { text } = await callGemini({
      model: GEMINI_MODEL,
      parts,
      temperature: 0.25,
      maxOutputTokens: 1536,
    })
    const parsed = extractJson(text)
    if (!parsed) {
      console.warn('[diag] parse fail, raw=', String(text).slice(0, 200))
      // 응답 일부라도 사람이 읽게 정리
      const soft = String(text || '')
        .replace(/```json/gi, '')
        .replace(/```/g, '')
        .replace(/[{}\[\]"]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 280)
      return {
        usedGemini: true,
        result: {
          diagnosisName: '잠시 결과를 정리하지 못했어요',
          confidence: 0.4,
          reason: soft
            ? ('온새미가 분석은 했지만 화면 정리 중 문제가 있었어요. 참고 내용: ' + soft)
            : '응답을 해석하지 못했어요. 같은 사진으로 한 번만 더 부탁드려요.',
          managementMethods: [
            '같은 잎을 가까이에서 한 장 더 찍어 주세요',
            '밝고 초점이 맞는 사진이 진단에 도움이 돼요',
            '증상이 보이면 질문에서 골라 주세요',
          ],
          greeting: '안녕하세요, 온새미예요 🌿',
          closing: '다시 한 번 시도해 볼까요? 🌱',
        },
      }
    }
    let confidence = Number(parsed.confidence)
    if (Number.isNaN(confidence)) confidence = 0.5
    if (confidence > 1) confidence = confidence / 100
    confidence = Math.max(0, Math.min(1, confidence))
    let methods = []
    if (Array.isArray(parsed.managementMethods)) methods = parsed.managementMethods.map(String)
    else if (Array.isArray(parsed.managementMethod)) methods = parsed.managementMethod.map(String)
    else if (typeof parsed.managementMethods === 'string') methods = [parsed.managementMethods]
    else if (typeof parsed.managementMethod === 'string') methods = [parsed.managementMethod]
    const result = {
      diagnosisName: String(parsed.diagnosisName || '알 수 없음'),
      confidence,
      reason: String(parsed.reason || ''),
      managementMethods: methods.length ? methods : ['통풍을 조금 더 좋게 해 주세요', '물 주기를 조절해 보세요', '병든 잎은 따로 분리해 주세요'],
      greeting: String(parsed.greeting || '안녕하세요, 식물 친구 온새미예요 🌿'),
      closing: String(parsed.closing || '확진은 전문가 확인을 권해요. 다시 물어보셔도 괜찮아요 🌱'),
    }
    cacheSet(key, result)
    return { usedGemini: true, result }
  } catch (e) {
    console.error('diagnoseWithGemini:', e.message)
    return { usedGemini: false, error: e.message }
  }
}

async function predictWithGemini(imageUrl, imageBase64) {
  if (!GEMINI_API_KEY) {
    return { usedGemini: false, error: 'GEMINI_API_KEY 없음' }
  }
  const key = cacheKey(['pred', imageUrl, GEMINI_PREDICT_MODEL])
  const cached = cacheGet(key)
  if (cached) {
    return { usedGemini: true, ...cached, cached: true }
  }

  const prompt = `당신은 식물 관찰 도우미 "온새미"입니다.
작물 사진을 보고, 사용자가 체크할 수 있는 "예상 질문" 4~6개를 만드세요.
질문은 짧고 일상적인 존댓말로, 예/아니오로 답하기 쉽게 작성하세요.

마크다운 코드블록 없이, 순수 JSON 객체만 출력하세요:
{
  "summary": "사진에 대한 짧은 한 줄 소감",
  "questions": [
    { "id": 1, "text": "질문 문장?", "tag": "짧은태그" }
  ]
}`
  const parts = [{ text: prompt }]
  const img = await loadImageAsBase64(imageUrl, imageBase64)
  if (img) {
    parts.push({ inline_data: { mime_type: img.mimeType, data: img.data } })
    console.log('[predict] image attached', img.data.length, 'b64 chars')
  } else {
    console.warn('[predict] NO IMAGE')
  }

  try {
    const { text } = await callGemini({
      model: GEMINI_PREDICT_MODEL,
      parts,
      temperature: 0.35,
      maxOutputTokens: 512,
    })
    const parsed = extractJson(text)
    if (!parsed || !Array.isArray(parsed.questions)) {
      return { usedGemini: false, error: 'parse fail' }
    }
    const payload = {
      summary: String(parsed.summary || '사진으로 짐작한 질문이에요.'),
      questions: parsed.questions.map((q, i) => ({
        id: Number(q.id) || i + 1,
        text: String(q.text || ''),
        tag: String(q.tag || ''),
      })).filter((q) => q.text),
    }
    cacheSet(key, payload)
    return { usedGemini: true, ...payload }
  } catch (e) {
    console.error('predictWithGemini:', e.message)
    return { usedGemini: false, error: e.message }
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

function getPublicBase(req) {
  const host = req.headers.host || `localhost:${PORT}`
  // 에뮬레이터가 Host를 10.0.2.2로 보내는 경우 그대로 사용
  return `http://${host}`
}



// ========== NCPMS / 공공데이터포털 연동 ==========
// 키 발급: https://www.data.go.kr  (국가농작물병해충도감정보 활용신청)
// NCPMS 안내: http://ncpms.rda.go.kr/npms/OpenApiInfo.np
//
// .env 예시:
//   DATA_GO_KR_SERVICE_KEY=발급키
//   NCPMS_ENCYCLOPEDIA_URL=포털_샘플에_나온_전체_요청_URL_중_쿼리앞까지
//   NCPMS_ALERTS_URL=주의보/발생정보_URL(선택)
//   NCPMS_USE_LIVE=1

// NCPMS 공식: http://ncpms.rda.go.kr/npmsAPI/service
// apiKey + serviceCode(SVC01 병검색) + serviceType(AA003=JSON)
const NCPMS_API_KEY = process.env.NCPMS_API_KEY || process.env.DATA_GO_KR_SERVICE_KEY || process.env.NCPMS_SERVICE_KEY || ''
const DATA_GO_KR_SERVICE_KEY = NCPMS_API_KEY // 하위 호환
const NCPMS_USE_LIVE = String(process.env.NCPMS_USE_LIVE || (NCPMS_API_KEY ? '1' : '0')) === '1'
const NCPMS_SERVICE_URL = process.env.NCPMS_SERVICE_URL || process.env.NCPMS_ENCYCLOPEDIA_URL || 'http://ncpms.rda.go.kr/npmsAPI/service'
const NCPMS_ENCYCLOPEDIA_URL = NCPMS_SERVICE_URL
const NCPMS_ALERTS_URL = process.env.NCPMS_ALERTS_URL || ''
const NCPMS_SERVICE_CODE_SICK = process.env.NCPMS_SERVICE_CODE_SICK || 'SVC01' // 병 검색
const NCPMS_SERVICE_TYPE = process.env.NCPMS_SERVICE_TYPE || 'AA001' // XML(매뉴얼 기본), JSON은 AA003
const NCPMS_CACHE_TTL_MS = Number(process.env.NCPMS_CACHE_TTL_MS || 6 * 60 * 60 * 1000) // 6시간

const ncpmsCache = new Map()
function ncpmsCacheGet(key) {
  const hit = ncpmsCache.get(key)
  if (!hit) return null
  if (Date.now() - hit.at > NCPMS_CACHE_TTL_MS) {
    ncpmsCache.delete(key)
    return null
  }
  return hit.data
}
function ncpmsCacheSet(key, data) {
  ncpmsCache.set(key, { at: Date.now(), data })
}


/**
 * NCPMS 공식 서비스 호출 (매뉴얼 REST)
 * URL: http://ncpms.rda.go.kr/npmsAPI/service
 * 필수: apiKey, serviceCode
 * 병검색 SVC01: cropName|sickNameKor, serviceType AA001(XML)/AA003(JSON)
 * 병상세 SVC05: sickKey
 */
async function fetchNcpmsService(params = {}) {
  if (!NCPMS_API_KEY) throw new Error('NCPMS_API_KEY 없음 — NCPMS 사이트 Open API 신청 키')
  const serviceType = params.serviceType || NCPMS_SERVICE_TYPE || 'AA001'
  const url = new URL(NCPMS_SERVICE_URL)
  url.searchParams.set('apiKey', NCPMS_API_KEY)
  url.searchParams.set('serviceCode', params.serviceCode || NCPMS_SERVICE_CODE_SICK)
  url.searchParams.set('serviceType', serviceType)

  for (const [k, v] of Object.entries(params)) {
    if (['serviceCode', 'serviceType'].includes(k)) continue
    if (v == null || v === '') continue
    url.searchParams.set(k, String(v))
  }

  const debugUrl = url.toString().replace(/apiKey=[^&]+/i, 'apiKey=***')
  console.log('[ncpms] GET', debugUrl)
  const res = await fetch(url.toString(), {
    headers: { Accept: 'application/xml, text/xml, application/json, */*' },
  })
  const text = await res.text()
  console.log('[ncpms] status', res.status, 'len', text.length, 'head', text.slice(0, 300).replace(/\s+/g, ' '))
  if (!res.ok) throw new Error(`NCPMS HTTP ${res.status}`)

  // 에러 노드
  const errCode = (text.match(/<errorCode>([^<]*)/i) || text.match(/"errorCode"\s*:\s*"([^"]*)"/) || [])[1]
  const errMsg = (text.match(/<errorMsg>([^<]*)/i) || text.match(/"errorMsg"\s*:\s*"([^"]*)"/) || [])[1]
  if (errCode) throw new Error(`NCPMS ${errCode}: ${errMsg || ''}`.trim())

  const trimmed = text.trim()
  if (serviceType === 'AA003' || trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try { return { kind: 'json', data: JSON.parse(trimmed), raw: text } } catch { /* fallthrough */ }
  }
  return { kind: 'xml', data: text, raw: text }
}


/** 간단 XML 태그 값 추출 */
function splitLines(text) {
  return String(text || '')
    .split(/[\n\r]+|(?:(?<=[.。])\s+)|•|·/)
    .map((s) => s.replace(/^\d+[\.\)]\s*/, '').trim())
    .filter((s) => s.length > 1)
}

function xmlTag(block, tag) {
  const re = new RegExp(
    '<' + tag + '[^>]*><!\\[CDATA\\[([\\s\\S]*?)\\]\\]><\\/' + tag + '>|<' + tag + '[^>]*>([\\s\\S]*?)<\\/' + tag + '>',
    'i'
  )
  const m = String(block).match(re)
  if (!m) return ''
  return ((m[1] != null && m[1] !== '') ? m[1] : (m[2] || '')).trim()
}

function xmlItems(xml, itemTag = 'item') {
  const re = new RegExp('<' + itemTag + '[\\s\\S]*?<\\/' + itemTag + '>', 'gi')
  return String(xml).match(re) || []
}

function collectXmlBlocks(xml) {
  for (const tag of ['item', 'items', 'row', 'list']) {
    const blocks = xmlItems(xml, tag)
    if (blocks.length && tag === 'item') return blocks
  }
  return xmlItems(xml, 'item')
}

/** XML/JSON 공통: list > item 배열 추출 (매뉴얼 구조) */
function extractListItems(payload) {
  if (!payload) return []
  if (payload.kind === 'json' || (payload && typeof payload === 'object' && payload.data && !payload.kind)) {
    const data = payload.data != null && payload.kind ? payload.data : payload
    // list.item / list / service.list.item 등
    const paths = [
      data?.list?.item,
      data?.list,
      data?.service?.list?.item,
      data?.service?.list,
      data?.response?.list?.item,
      data?.body?.list?.item,
      data?.item,
      data?.items,
    ]
    for (const node of paths) {
      if (!node) continue
      if (Array.isArray(node)) return node
      if (typeof node === 'object') return [node] // 단건
    }
    return []
  }
  // XML string
  const xml = typeof payload === 'string' ? payload : (payload.data || payload.raw || '')
  const listMatch = xml.match(/<list[\s\S]*?<\/list>/i)
  const scope = listMatch ? listMatch[0] : xml
  return xmlItems(scope, 'item')
}

function xmlField(block, ...names) {
  for (const n of names) {
    const v = xmlTag(block, n)
    if (v) return v
  }
  return ''
}

function mapNcpmsSickList(payload) {
  const rows = extractListItems(payload)
  console.log('[ncpms] list items', rows.length)
  return rows.map((raw, idx) => {
    let crop, name, eng, chn, thumb, ori, sickKey
    if (typeof raw === 'string') {
      crop = xmlField(raw, 'cropName', 'cropname')
      name = xmlField(raw, 'sickNameKor', 'sicknamekor', 'korName', 'korname')
      eng = xmlField(raw, 'sickNameEng', 'sicknameeng')
      chn = xmlField(raw, 'sickNameChn', 'sicknamechn')
      thumb = xmlField(raw, 'thumbImg', 'thumbimg')
      ori = xmlField(raw, 'oriImg', 'oriimg')
      sickKey = xmlField(raw, 'sickKey', 'sickkey')
    } else {
      crop = raw.cropName || raw.cropname || raw.crop || ''
      name = raw.sickNameKor || raw.sicknamekor || raw.korName || raw.name || ''
      eng = raw.sickNameEng || raw.sicknameeng || ''
      chn = raw.sickNameChn || raw.sicknamechn || ''
      thumb = raw.thumbImg || raw.thumbimg || null
      ori = raw.oriImg || raw.oriimg || null
      sickKey = raw.sickKey || raw.sickkey || raw.id
    }
    const key = String(sickKey || '').trim() || `tmp-${idx + 1}`
    return {
      id: key,
      crop: String(crop || '작물'),
      category: '병',
      name: String(name || `항목${idx + 1}`),
      scientificName: String(eng || ''),
      summary: [crop, name, chn].filter(Boolean).join(' · '),
      symptoms: name ? `${crop || ''} ${name}`.trim() : '',
      environment: '',
      control: ['상세 화면에서 NCPMS 방제방법을 확인하세요.'],
      prevention: '',
      tags: [name, crop, '병'].filter(Boolean),
      thumbImg: thumb,
      oriImg: ori,
      sickKey: key,
      source: 'NCPMS',
    }
  }).filter((e) => e.name && !String(e.name).startsWith('항목'))
}

function mapNcpmsSickDetail(payload, fallbackId) {
  const rawXml = payload && payload.kind === 'xml'
    ? (payload.data || payload.raw || '')
    : (typeof payload === 'string' ? payload : (payload && payload.raw) || '')
  const jsonRoot = payload && payload.kind === 'json' ? payload.data : null

  const fromXml = (xml) => {
    const crop = xmlField(xml, 'cropName', 'cropname')
    const name = xmlField(xml, 'sickNameKor', 'sicknamekor')
    const eng = xmlField(xml, 'sickNameEng', 'sicknameeng')
    const chn = xmlField(xml, 'sickNameChn', 'sicknamechn')
    const symptoms = xmlField(xml, 'symptoms')
    const env = xmlField(xml, 'developmentCondition', 'developmentcondition')
    const prevention = xmlField(xml, 'preventionMethod', 'preventionmethod')
    const infection = xmlField(xml, 'infectionRoute', 'infectionroute')
    const etc = xmlField(xml, 'etc')
    const control = []
    if (prevention) {
      const lines = splitLines(prevention)
      control.push(...(lines.length ? lines : [prevention]))
    }
    if (infection) control.unshift('전염경로: ' + infection)
    // 이미지 첫 장
    const imgBlock = (xml.match(/<imageList[\s\S]*?<\/imageList>/i) || xml.match(/<imagelist[\s\S]*?<\/imagelist>/i) || [''])[0]
    const firstImg = xmlField(imgBlock, 'image') || xmlField(xml, 'oriImg', 'oriimg', 'thumbImg', 'thumbimg')
    return {
      id: String(fallbackId || ''),
      crop: crop || '작물',
      category: '병',
      name: name || '상세 정보',
      scientificName: [eng, chn].filter(Boolean).join(' / '),
      summary: (symptoms || '').slice(0, 120),
      symptoms: symptoms || etc || '증상 설명이 등록되어 있지 않습니다.',
      environment: env || (infection ? ('전염경로: ' + infection) : '발생 환경 정보가 없습니다.'),
      control: control.length ? control : ['등록된 방제방법을 확인하지 못했습니다. NCPMS 웹 도감을 참고하세요.'],
      prevention: prevention || etc || '',
      tags: [name, crop].filter(Boolean),
      thumbImg: firstImg || null,
      oriImg: firstImg || null,
      source: 'NCPMS',
      sickKey: String(fallbackId || ''),
    }
  }

  if (rawXml && typeof rawXml === 'string' && rawXml.includes('<')) {
    const d = fromXml(rawXml)
    if (d.name && d.name !== '상세 정보') return d
    // name 없어도 symptoms 있으면 반환
    if (d.symptoms && !d.symptoms.includes('등록되어 있지')) return d
  }

  if (jsonRoot && typeof jsonRoot === 'object') {
    const pick = (...keys) => {
      for (const k of keys) {
        if (jsonRoot[k] != null && String(jsonRoot[k]).trim() !== '') return String(jsonRoot[k])
        const found = Object.keys(jsonRoot).find((x) => x.toLowerCase() === k.toLowerCase())
        if (found && jsonRoot[found] != null) return String(jsonRoot[found])
      }
      return ''
    }
    const prevention = pick('preventionMethod', 'preventionmethod')
    const infection = pick('infectionRoute')
    const control = []
    if (prevention) control.push(...(splitLines(prevention).length ? splitLines(prevention) : [prevention]))
    if (infection) control.unshift('전염경로: ' + infection)
    return {
      id: String(fallbackId || ''),
      crop: pick('cropName') || '작물',
      category: '병',
      name: pick('sickNameKor') || '상세 정보',
      scientificName: [pick('sickNameEng'), pick('sickNameChn')].filter(Boolean).join(' / '),
      summary: pick('symptoms').slice(0, 120),
      symptoms: pick('symptoms') || pick('etc') || '증상 설명이 등록되어 있지 않습니다.',
      environment: pick('developmentCondition') || (infection ? '전염경로: ' + infection : ''),
      control: control.length ? control : ['방제방법 정보를 확인하지 못했습니다.'],
      prevention: prevention || '',
      tags: [pick('sickNameKor'), pick('cropName')].filter(Boolean),
      source: 'NCPMS',
      sickKey: String(fallbackId || ''),
    }
  }

  return null
}

async function getEncyclopediaList(q, crop) {
  const cacheKey = `enc:${q || ''}:${crop || ''}`
  const cached = ncpmsCacheGet(cacheKey)
  if (cached) return { ...cached, cached: true }

  if (NCPMS_USE_LIVE && NCPMS_API_KEY) {
    try {
      const query = (q || '').trim()
      const cropQ = (crop || '').trim()
      // SVC01: cropName 또는 sickNameKor 중 하나 필수
      // 동시 전달 시 결과가 0건인 경우가 많아, 우선순위 검색 후 재시도
      const attempts = []
      if (query && cropQ) {
        attempts.push({ sickNameKor: query })
        attempts.push({ cropName: cropQ, sickNameKor: query })
        attempts.push({ cropName: cropQ })
      } else if (query) {
        attempts.push({ sickNameKor: query })
        // "사과 탄저병" → 병명만 재시도
        const parts = query.split(/\s+/)
        if (parts.length >= 2) {
          attempts.push({ sickNameKor: parts[parts.length - 1] })
          attempts.push({ cropName: parts[0], sickNameKor: parts.slice(1).join(' ') })
        }
      } else if (cropQ) {
        attempts.push({ cropName: cropQ })
      } else {
        attempts.push({ sickNameKor: '탄저' })
      }

      let items = []
      for (const extra of attempts) {
        const params = {
          serviceCode: NCPMS_SERVICE_CODE_SICK,
          serviceType: NCPMS_SERVICE_TYPE,
          displayCount: 50,
          startPoint: 1,
          ...extra,
        }
        try {
          let payload
          try {
            payload = await fetchNcpmsService(params)
          } catch (e1) {
            if (NCPMS_SERVICE_TYPE !== 'AA001') {
              payload = await fetchNcpmsService({ ...params, serviceType: 'AA001' })
            } else throw e1
          }
          // totalCount 0 이면 다음 시도
          const raw = payload.raw || ''
          if (/<totalCount>0<\/totalCount>/i.test(raw) || /"totalCount"\s*:\s*0/.test(raw)) {
            console.log('[ncpms] totalCount=0 try next', extra)
            continue
          }
          items = mapNcpmsSickList(payload)
          if (items.length) break
        } catch (e) {
          console.warn('[ncpms] attempt fail', extra, e.message)
        }
      }
      if (items.length) {
        const body = { items, total: items.length, source: 'NCPMS' }
        ncpmsCacheSet(cacheKey, body)
        return body
      }
      console.warn('[ncpms] live encyclopedia empty, fallback mock')
    } catch (e) {
      console.error('[ncpms] encyclopedia live fail:', e.message)
    }
  }

  const items = searchMockEncyclopedia(q, crop)
  return {
    items,
    total: items.length,
    source: NCPMS_USE_LIVE && NCPMS_API_KEY ? 'NCPMS(mock-fallback)' : 'NCPMS(mock)',
  }
}

async function getEncyclopediaDetail(id) {
  const cacheKey = `encDetail:${id}`
  const cached = ncpmsCacheGet(cacheKey)
  if (cached) return cached

  if (NCPMS_USE_LIVE && NCPMS_API_KEY && id != null && String(id) !== '') {
    try {
      let payload
      const base = { serviceCode: 'SVC05', sickKey: String(id) }
      try {
        payload = await fetchNcpmsService({ ...base, serviceType: NCPMS_SERVICE_TYPE })
      } catch (e1) {
        console.warn('[ncpms] detail retry AA001', e1.message)
        payload = await fetchNcpmsService({ ...base, serviceType: 'AA001' })
      }
      const detail = mapNcpmsSickDetail(payload, id)
      if (detail && detail.name) {
        console.log('[ncpms] detail ok', detail.crop, detail.name)
        ncpmsCacheSet(cacheKey, detail)
        return detail
      }
      console.warn('[ncpms] detail parse empty for sickKey=', id)
    } catch (e) {
      console.error('[ncpms] detail fail:', e.message)
    }
  }

  const list = await getEncyclopediaList('', '')
  const item = (list.items || []).find(
    (e) => String(e.id) === String(id) || String(e.sickKey) === String(id)
  )
  if (item) {
    const full = MOCK_ENCYCLOPEDIA.find((e) => String(e.id) === String(id))
    return { ...(full || {}), ...item, id: String(id), sickKey: String(id), source: item.source || 'NCPMS' }
  }
  const mock = MOCK_ENCYCLOPEDIA.find((e) => String(e.id) === String(id))
  return mock ? { ...mock, id: String(mock.id) } : null
}

async function getAlerts(crop) {
  const cacheKey = `alerts:${crop || 'all'}`
  const cached = ncpmsCacheGet(cacheKey)
  if (cached) return { ...cached, cached: true }

  // 1) 별도 예찰 URL이 있으면 사용
  if (NCPMS_USE_LIVE && NCPMS_ALERTS_URL && NCPMS_API_KEY) {
    try {
      const payload = await fetchNcpmsService({
        // URL이 service 엔드포인트면 serviceCode는 env
        serviceCode: process.env.NCPMS_SERVICE_CODE_ALERT || 'SVC01',
        serviceType: NCPMS_SERVICE_TYPE,
        cropName: crop || undefined,
        displayCount: 30,
        startPoint: 1,
      })
      const items = mapNcpmsSickList(payload).map((e, idx) => ({
        id: e.id || idx + 1,
        crop: e.crop,
        name: e.name,
        level: 'WATCH',
        region: '전국',
        period: new Date().toISOString().slice(0, 10),
        summary: e.summary || e.symptoms || '',
        source: 'NCPMS',
      }))
      if (items.length) {
        const body = { items, updatedAt: new Date().toISOString(), source: 'NCPMS' }
        ncpmsCacheSet(cacheKey, body)
        return body
      }
    } catch (e) {
      console.error('[ncpms] alerts url fail:', e.message)
    }
  }

  // 2) SVC01 작물별 병 목록으로 "주의보" 카드 구성 (매뉴얼에 예찰 REST 샘플이 없어 검색 API 활용)
  if (NCPMS_USE_LIVE && NCPMS_API_KEY) {
    try {
      const crops = crop
        ? [crop]
        : ['고추', '토마토', '사과', '배추', '논벼', '오이']
      const items = []
      for (const c of crops) {
        try {
          const payload = await fetchNcpmsService({
            serviceCode: 'SVC01',
            serviceType: NCPMS_SERVICE_TYPE,
            cropName: c,
            displayCount: 5,
            startPoint: 1,
          })
          const list = mapNcpmsSickList(payload)
          list.slice(0, 3).forEach((e, idx) => {
            const key = String(e.sickKey || e.id || '')
            items.push({
              id: key,
              crop: e.crop || c,
              name: e.name,
              level: idx === 0 ? 'WARNING' : 'WATCH',
              region: '전국(NCPMS 도감)',
              period: new Date().toISOString().slice(0, 10),
              summary: `${e.crop || c} · ${e.name} — 도감에 등록된 주요 병해입니다. 상세에서 증상·방제를 확인하세요.`,
              source: 'NCPMS',
              sickKey: key,
            })
          })
        } catch (e) {
          console.warn('[ncpms] alert crop', c, e.message)
        }
      }
      if (items.length) {
        const body = {
          items,
          updatedAt: new Date().toISOString(),
          source: 'NCPMS(SVC01-crop)',
        }
        ncpmsCacheSet(cacheKey, body)
        return body
      }
    } catch (e) {
      console.error('[ncpms] alerts build fail:', e.message)
    }
  }

  let items = MOCK_ALERTS
  if (crop) items = items.filter((a) => a.crop === crop)
  return {
    items,
    updatedAt: new Date().toISOString(),
    source: NCPMS_USE_LIVE && NCPMS_API_KEY ? 'NCPMS(mock-fallback)' : 'NCPMS(mock)',
  }
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

    const needAuth = p.startsWith('/api/') && !p.startsWith('/api/auth/') && p !== '/api/health' && p !== '/api/ncpms/status' && p !== '/api/ncpms/debug'
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
      const fileName = (body.fileName || `${crypto.randomUUID()}.jpg`).replace(/[^a-zA-Z0-9._-]/g, '_')
      const key = `uploads/${Date.now()}_${fileName}`
      const base = getPublicBase(req)
      return send(res, 200, {
        uploadUrl: `${base}/api/upload/direct?key=${encodeURIComponent(key)}`,
        fileUrl: `${base}/${key}`,
        key,
      })
    }
    // 앱에서 바로 base64 업로드 (에뮬레이터 localhost 문제 회피)
    if (method === 'POST' && p === '/api/upload/base64') {
      const body = await readBody(req)
      const b64 = (body.imageBase64 || '').replace(/^data:image\/[a-zA-Z+]+;base64,/, '')
      if (!b64 || b64.length < 100) return send(res, 400, { message: 'imageBase64 필요' })
      const buf = Buffer.from(b64, 'base64')
      if (buf.length < 100) return send(res, 400, { message: '이미지 데이터가 너무 작습니다' })
      const fileName = `${Date.now()}.jpg`
      const key = `uploads/${fileName}`
      const dest = path.join(__dirname, key)
      fs.mkdirSync(path.dirname(dest), { recursive: true })
      fs.writeFileSync(dest, buf)
      const base = getPublicBase(req)
      console.log('[upload/base64]', buf.length, 'bytes ->', key)
      return send(res, 200, { fileUrl: `${base}/${key}`, key, bytes: buf.length })
    }
    if (method === 'PUT' && p === '/api/upload/direct') {
      const key = u.searchParams.get('key') || `uploads/${Date.now()}.jpg`
      const raw = await readBody(req)
      const buf = Buffer.isBuffer(raw) ? raw : Buffer.from(String(raw))
      if (buf.length < 50) return send(res, 400, { message: '업로드 본문이 비어 있습니다' })
      const dest = path.join(__dirname, key)
      fs.mkdirSync(path.dirname(dest), { recursive: true })
      fs.writeFileSync(dest, buf)
      console.log('[upload/direct]', buf.length, 'bytes ->', key)
      return send(res, 200, { ok: true, key, bytes: buf.length })
    }
    // 업로드 이미지 정적 제공
    if (method === 'GET' && p.startsWith('/uploads/')) {
      const filePath = path.join(__dirname, p)
      if (!filePath.startsWith(path.join(__dirname, 'uploads'))) return send(res, 403, { message: 'forbidden' })
      if (!fs.existsSync(filePath)) return send(res, 404, { message: 'file not found' })
      const buf = fs.readFileSync(filePath)
      res.writeHead(200, {
        'Content-Type': 'image/jpeg',
        'Content-Length': buf.length,
        'Cache-Control': 'public, max-age=3600',
      })
      res.end(buf)
      return
    }
    if (method === 'POST' && p === '/api/ai/predict') {
      const body = await readBody(req)
      const pred = await predictWithGemini(body.imageUrl, body.imageBase64)
      if (pred.usedGemini) {
        return send(res, 200, { summary: pred.summary, questions: pred.questions })
      }
      // 데모 예측 질문
      return send(res, 200, {
        summary: '사진으로 짐작한 질문이에요. 해당되는 것을 골라 주세요.',
        questions: [
          { id: 1, text: '잎에 반점이나 얼룩이 생겼나요?', tag: '잎 반점' },
          { id: 2, text: '잎 색이 노랗게 변했나요?', tag: '잎 황화' },
          { id: 3, text: '잎이 시들거나 축 처지나요?', tag: '시들음' },
          { id: 4, text: '흰가루처럼 보이는 게 있나요?', tag: '흰가루' },
          { id: 5, text: '벌레 구멍이나 갉아 먹은 자국이 있나요?', tag: '벌레' },
        ],
      })
    }

    if (method === 'POST' && p === '/api/ai/diagnose') {
      const body = await readBody(req)
      const id = nextDiagId++
      const gemini = await diagnoseWithGemini({
        imageUrl: body.imageUrl,
        symptomTagIds: body.symptomTagIds || body.symptomIds || [],
        question: body.question,
        imageBase64: body.imageBase64,
      })

      let result
      if (gemini.usedGemini && gemini.result) {
        result = {
          id,
          diagnosisName: gemini.result.diagnosisName,
          confidence: gemini.result.confidence,
          reason: gemini.result.reason,
          managementMethods: gemini.result.managementMethods,
          greeting: gemini.result.greeting,
          closing: gemini.result.closing,
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
    
    // ----- NCPMS: 주의보 -----
    if (method === 'GET' && p === '/api/ncpms/alerts') {
      const crop = u.searchParams.get('crop')
      const body = await getAlerts(crop)
      return send(res, 200, body)
    }
    // ----- NCPMS: 도감 목록/검색 -----
    if (method === 'GET' && p === '/api/ncpms/encyclopedia') {
      const q = u.searchParams.get('q') || ''
      const crop = u.searchParams.get('crop') || ''
      const body = await getEncyclopediaList(q, crop)
      // 목록용 필드만
      const items = (body.items || []).map((e) => ({
        id: e.id, crop: e.crop, category: e.category, name: e.name,
        scientificName: e.scientificName, summary: e.summary || (e.symptoms || '').slice(0, 80),
        tags: e.tags,
      }))
      return send(res, 200, { items, total: items.length, source: body.source, cached: body.cached })
    }
    // ----- NCPMS: 도감 상세 -----
    if (method === 'GET' && p.startsWith('/api/ncpms/encyclopedia/')) {
      const id = decodeURIComponent(p.split('/').pop() || '')
      const item = await getEncyclopediaDetail(id)
      if (!item) return send(res, 404, { message: '도감 항목 없음', id })
      return send(res, 200, item)
    }
    // ----- NCPMS: 진단명 → 도감 매칭 -----
    if (method === 'GET' && p === '/api/ncpms/match') {
      const name = u.searchParams.get('name') || ''
      const body = await getEncyclopediaList(name, '')
      return send(res, 200, { items: (body.items || []).slice(0, 5), query: name, source: body.source })
    }
    // ----- NCPMS 상태 -----
    if (method === 'GET' && p === '/api/ncpms/status') {
      return send(res, 200, {
        liveEnabled: NCPMS_USE_LIVE,
        hasApiKey: Boolean(NCPMS_API_KEY),
        serviceUrl: NCPMS_SERVICE_URL,
        serviceCodeSick: NCPMS_SERVICE_CODE_SICK,
        serviceType: NCPMS_SERVICE_TYPE,
        alertsUrl: NCPMS_ALERTS_URL || null,
        cacheTtlHours: NCPMS_CACHE_TTL_MS / 3600000,
        note: 'NCPMS 매뉴얼: apiKey + serviceCode=SVC01 + serviceType=AA003 + cropName|sickNameKor',
      })
    }
    // 디버그: 실제 응답 앞부분 확인 (키 노출 주의, 개발용)
    if (method === 'GET' && p === '/api/ncpms/debug') {
      if (!NCPMS_API_KEY) {
        return send(res, 200, { ok: false, message: 'NCPMS_API_KEY 가 없습니다. NCPMS Open API 신청 키를 .env에 넣으세요.' })
      }
      try {
        const q = u.searchParams.get('q') || '탄저'
        const payload = await fetchNcpmsService({
          serviceCode: NCPMS_SERVICE_CODE_SICK,
          serviceType: NCPMS_SERVICE_TYPE,
          sickNameKor: q,
          displayCount: 10,
          startPoint: 1,
        })
        const items = mapNcpmsSickList(payload)
        const preview = (payload.raw || JSON.stringify(payload.data) || '').slice(0, 1500)
        return send(res, 200, {
          ok: true,
          query: q,
          serviceCode: NCPMS_SERVICE_CODE_SICK,
          serviceType: NCPMS_SERVICE_TYPE,
          parsedCount: items.length,
          sampleNames: items.slice(0, 8).map((i) => `${i.crop}/${i.name}(${i.sickKey})`),
          preview,
        })
      } catch (e) {
        return send(res, 200, { ok: false, message: e.message })
      }
    }


    send(res, 404, { message: `Not found: ${method} ${p}` })
  } catch (e) {
    send(res, 500, { message: e.message || 'server error' })
  }
})

server.listen(PORT, () => {
  console.log(`키:우다 개발 서버 http://localhost:${PORT}`)
  console.log(`데모: demo@kiuda.com / password123`)
  console.log(`NCPMS: ${NCPMS_USE_LIVE && NCPMS_API_KEY ? 'LIVE SVC01' : 'MOCK'} key=${NCPMS_API_KEY ? 'yes' : 'no'} url=${NCPMS_SERVICE_URL}`)
  console.log(`Gemini: ${GEMINI_API_KEY ? 'ON diag=' + GEMINI_MODEL + ' pred=' + GEMINI_PREDICT_MODEL + ' cache=' + (GEMINI_CACHE_TTL_MS/60000) + 'm' : 'OFF (.env에 GEMINI_API_KEY 설정)'}`)
})
