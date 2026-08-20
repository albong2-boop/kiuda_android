# 키:우다 (Ki:uda) Android 앱 - Phase 1 MVP

이 프로젝트는 기존 웹/백엔드와 동일한 API를 사용하는 Kotlin Android 앱입니다.

## 기술 스택
- Kotlin
- XML + ViewBinding
- MVVM + Repository
- Retrofit + OkHttp
- Coroutines + StateFlow
- CameraX
- Coil
- EncryptedSharedPreferences (JWT)
- Google Sign-In (선택)

## 프로젝트 구조
app/src/main/java/com/kiuda/app/
├── data/
│   ├── api/ApiService.kt
│   ├── model/
│   └── repository/KiudaRepository.kt
├── ui/
│   ├── login/
│   ├── dashboard/ (보다)
│   └── ask/ (묻다)
└── MainActivity.kt

## 백엔드 연동
- Base URL: http://localhost:8080/api (또는 실제 서버)
- JWT Authorization: Bearer {token}
