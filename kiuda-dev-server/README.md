# 키:우다 개발용 API 서버 (+ Gemini AI 진단)

## 실행

```bash
cd kiuda-dev-server

# 1) Gemini 키 설정 (AI 진단용)
copy .env.example .env   # Windows
# cp .env.example .env   # Mac/Linux
# .env 파일을 열어 GEMINI_API_KEY=발급키 입력

# 2) 서버 시작
node server.mjs
```

키 발급: https://aistudio.google.com/apikey (무료)

## 확인

- http://localhost:8080/api/health
- 터미널에 `Gemini: ON (gemini-2.0-flash)` 이면 AI 진단 활성

## 데모 계정
`demo@kiuda.com` / `password123`

## AI 진단 흐름
1. 앱에서 사진 촬영 → 업로드
2. `POST /api/ai/diagnose` `{ imageUrl, symptomTagIds, question }`
3. 서버가 Gemini에 이미지+증상 전달
4. JSON 진단 결과 반환 → 앱 결과 화면

키가 없으면 데모 진단 결과를 반환합니다 (앱이 깨지지 않음).


## Gemini 호출 최적화 (적용됨)

- 앱: 업로드 전 이미지 긴 변 1280px / JPEG 품질 78 압축
- 서버: predict / diagnose 결과 메모리 캐시 (기본 10분)
- predict 모델 분리 (`GEMINI_PREDICT_MODEL`)
- maxOutputTokens: predict 512 / diagnose 900
- 429 시 최대 3회 짧은 백오프 재시도
- 앱: 촬영·진단 중 버튼 비활성화로 연타 방지


## NCPMS 실연동

1. [공공데이터포털](https://www.data.go.kr) 가입
2. **농촌진흥청_국가농작물병해충도감정보** 활용신청 → 인증키
3. API 상세 화면의 **샘플 요청 URL** 확인
4. `.env` 설정:

```env
DATA_GO_KR_SERVICE_KEY=발급키
NCPMS_USE_LIVE=1
NCPMS_ENCYCLOPEDIA_URL=https://...가이드에_나온_엔드포인트...
# 선택: 주의보/발생정보
NCPMS_ALERTS_URL=https://...
```

5. `node server.mjs` 재시작
6. 상태 확인: http://localhost:8080/api/ncpms/status

- URL/키가 없으면 **목업 데이터**로 동작 (앱 UI 동일)
- 실호출 실패 시 자동 목업 폴백
- 응답은 6시간 캐시

참고: http://ncpms.rda.go.kr/npms/OpenApiInfo.np
