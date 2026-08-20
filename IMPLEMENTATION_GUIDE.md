# 키:우다 구현 가이드 · 미구현 기능 추천 구현법

이 문서는 **지금 바로 실행 가능한 범위**와 **아직 API/제품적으로 비어 있는 부분**,  
그리고 **프로덕션급으로 가는 추천 구현 방법**을 정리합니다.

---

## 1. 지금 바로 쓰는 방법

### ① 개발 API 서버 실행

```bash
cd kiuda-dev-server
npm install
npm start
# → http://localhost:8080
```

| 항목 | 값 |
|------|-----|
| 데모 계정 | `demo@kiuda.com` / `password123` |
| Health | `GET /api/health` |
| 에뮬레이터 Base URL | `http://10.0.2.2:8080/api/` |

### ② Android 앱

1. Android Studio에서 `kiuda-android` Open  
2. Gradle Sync → Run  
3. 로그인 / 회원가입 / 보다 / 묻다 확인  

서버가 꺼져 있어도 **데모 모드**로 화면 플로우는 동작합니다.  
서버가 켜져 있으면 **실제 JWT·데이터**를 사용합니다.

### ③ 제공 API (dev-server)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/login` | 로그인 (username 또는 email) |
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/check-email` | 이메일 중복 |
| GET | `/api/user/me` | 내 정보 |
| GET | `/api/plants` | 내 키움이 |
| GET | `/api/checklist` | 체크리스트 |
| PUT | `/api/checklist/:id/complete` | 체크 완료 |
| GET | `/api/weather` | 날씨 스냅샷 |
| GET | `/api/pest-alerts` | 병해충 |
| GET | `/api/symptoms` | 증상 태그 |
| POST | `/api/upload/presigned` | 업로드 URL |
| PUT | `/api/upload/direct` | 개발용 직접 업로드 |
| POST | `/api/ai/diagnose` | AI 진단 (목 결과) |
| GET | `/api/ai/diagnose/:id` | 진단 조회 |

---

## 2. Android에 구현된 화면 (Phase 1)

| 화면 | 상태 | 비고 |
|------|------|------|
| 로그인 | ✅ | JWT, 오프라인 데모 |
| 회원가입 (2단계) | ✅ | 비밀번호 강도, 약관 |
| 보다 (홈) | ✅ | 키움이/체크/날씨/병해충 |
| 묻다 (CameraX) | ✅ | 증상 선택 + 진단 요청 |
| 진단 결과 | ✅ | 확신도 바 + 관리법 |
| Google 로그인 | 🔘 UI만 | 아래 추천 구현 참고 |
| 잇다 / 나누다 / DM | ❌ Phase 2 | |

---

## 3. 미구현 · 부족한 부분과 추천 구현

### 3.1 Google 로그인

**현재:** 버튼 토스트만.

**추천 구현**

1. Google Cloud Console에서 OAuth 클라이언트 (Android + Web) 생성  
2. Android: `Credential Manager` 또는 `play-services-auth` 로 ID Token 수신  
3. 백엔드: `POST /api/auth/google`  
   - body: `{ "idToken": "..." }`  
   - 서버에서 Google Tokeninfo / 공식 라이브러리로 **검증** 후 JWT 발급  
4. **절대** 클라이언트 claim만 믿지 말 것  

```text
Android → Google ID Token → Backend 검증 → JWT 발급 → Android 저장
```

### 3.2 실제 AI 진단 (Gemini 등)

**현재:** dev-server는 고정 목 진단 반환.

**추천 구현**

1. 이미지: S3/GCS **Presigned URL** 업로드  
2. 백엔드가 이미지 URL + 증상 태그를 Gemini / 자체 모델에 전달  
3. 응답 스키마를 고정 (diagnosisName, confidence, reason, managementMethods[])  
4. `AIQueryLog` 테이블에 요청/응답 저장 (감사·개선용)  
5. Android는 이미 `DiagnosisResult` 모델로 수신 가능 → **서버 스키마만 맞추면 됨**

### 3.3 Presigned URL (프로덕션)

**현재:** 로컬 `PUT /api/upload/direct`.

**추천**

- AWS S3 또는 GCS V4 signed URL  
- Android: Retrofit이 아닌 **OkHttp로 해당 URL에 PUT** (이미 Repository에 구조 있음)  
- Content-Type, 만료시간(5~15분), 파일 크기 제한  

### 3.4 Refresh Token

**현재:** Access Token만 (7일).

**추천**

- Access 15분 ~ 1시간, Refresh 14~30일  
- `EncryptedSharedPreferences`에 둘 다 저장  
- OkHttp Authenticator에서 401 시 refresh → 재시도  
- Refresh 재사용/로테이션 정책 적용  

### 3.5 회원가입 고도화 (웹 3단계 위저드)

**현재 Android:** 2단계 (계정 + 프로필/약관).

**추천 (웹과 맞춤)**

1. 이메일 중복 API (`/api/auth/check-email`) — 서버/앱 모두 준비됨, UI 버튼만 추가  
2. 지역(시/군) 선택 — `regions` 데이터 공유  
3. 관심 작물 태그  
4. 약관 전문 WebView / 인앱 브라우저  

### 3.6 내 키움이 CRUD

**현재:** 목록 조회만.

**추천 API**

```
POST   /api/plants
PUT    /api/plants/:id
DELETE /api/plants/:id
POST   /api/plants/:id/photo
```

Android: 등록 화면 + Coil 이미지 + 폼 검증.

### 3.7 날씨 · 병해충 실데이터

**현재:** 고정값.

**추천**

- 기상청 / OpenWeather + 사용자 위치(시군구)  
- 병해충: 농진청 정보 또는 내부 규칙 엔진  
- 캐시(Room) + 하루 1회 갱신  

### 3.8 잇다 / 나누다 / DM (Phase 2)

**추천 순서**

1. 게시글 목록/작성 API + 이미지  
2. 댓글·좋아요·북마크  
3. 팔로우 / 지역 기반 피드  
4. DM: WebSocket 또는 FCM + REST  
5. UI는 Jetpack Compose로 신규 화면부터 전환  

### 3.9 사진 품질 (Phase 1.5)

**추천:** ML Kit  
- 블러 / 밝기 / 작물 ROI 가이드  
- 실패 시 재촬영 유도 카피  

### 3.10 보안 · 운영

| 항목 | 추천 |
|------|------|
| HTTPS | 배포 필수, cleartext 제거 |
| Certificate Pinning | 금융급 필요 시 |
| ProGuard/R8 | 릴리스 minify |
| API 버전 | `/api/v1/...` |
| 로깅 | 서버 구조화 로그, 모바일 Crashlytics |
| 환경 분리 | dev / staging / prod Base URL BuildConfig |

### 3.11 기존 플랫폼 서버 (Node) 보완 포인트

`kiuda-platform/server` 기준:

| 이슈 | 추천 |
|------|------|
| 회원가입 컨트롤러 없음 | `authController.signup` + bcrypt + User insert |
| `requireAuth` 토큰 파싱 버그 | `header.split(' ')[1]` (공백 기준) |
| login만 존재 | Android와 필드명 통일 (`username`/`email`, `jwt`/`token`) |
| MySQL 스키마 | 제공된 `kiuda_db_schema` 적용 후 메모리 서버 → 실DB 이전 |

**실DB 이전 순서 추천**

1. 스키마 적용  
2. dev-server 로직을 Repository/Service로 이식  
3. 동일 경로 유지 → Android 코드 변경 최소화  

---

## 4. Android ↔ 서버 계약 (필드)

### 로그인 요청
```json
{ "username": "demo@kiuda.com", "password": "password123" }
```
(`email` 키도 서버에서 수용)

### 로그인/가입 응답
```json
{
  "jwt": "...",
  "token": "...",
  "username": "...",
  "name": "...",
  "nickname": "...",
  "role": "USER"
}
```

### 진단 응답
```json
{
  "id": 1,
  "diagnosisName": "토마토 잎곰팡이병",
  "confidence": 0.87,
  "reason": "...",
  "managementMethods": ["...", "..."],
  "imageUrl": "..."
}
```

---

## 5. 디자인 토큰 (웹과 통일)

| 토큰 | 값 |
|------|-----|
| Primary | `#56B968` |
| Dark green | `#4F7A3E` |
| Soft green | `#D5E6C8` |
| BG | `#EEF3EA` / cream gradient |
| Text | `#2C3A28` / `#3D5A3D` |
| Border | `#D9CFC1` |
| Radius | 12~20dp 카드, 14dp 버튼 |

---

## 6. 체크리스트 (출시 전)

- [ ] HTTPS + 실서버 Base URL  
- [ ] Google 로그인 서버 검증  
- [ ] Presigned 실스토리지  
- [ ] AI 실모델 연동  
- [ ] Refresh Token  
- [ ] 회원가입 이메일 인증 (선택)  
- [ ] Crashlytics / Analytics  
- [ ] 스토어 스크린샷·개인정보처리방침  

---

**요약:**  
Phase 1 앱 + 개발 서버로 **로그인·가입·홈·AI 진단 플로우**는 즉시 사용 가능합니다.  
위 3장 항목을 우선순위로 채우면 프로덕션 수준으로 올라갑니다.
