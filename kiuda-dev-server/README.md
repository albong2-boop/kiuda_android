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
