# 키:우다 (Ki:uda) Android 앱 — Phase 1 MVP

홈페이지와 동일한 소프트 그린 / 뉴모피즘 컨셉의 Kotlin Android 앱입니다.

## 테스트 결과 (코드 정적 검증)

### 수정·해결한 이슈
1. **ViewModel 크래시** — `by viewModels()`용 zero-arg 생성자로 수정
2. **메인 스레드 네트워크** — Repository 전체를 `Dispatchers.IO`로 이동
3. **EncryptedSharedPreferences 실패** — 평문 SharedPreferences 폴백 추가
4. **백엔드 미연결** — 로그인/홈/진단 모두 **데모 모드**로 동작
5. **CoordinatorLayout 의존성 누락** — 명시적 dependency 추가
6. **로그아웃 스택** — `CLEAR_TASK`로 로그인 화면 복귀
7. **카메라 재시작/권한** — 방어 코드 및 상태 메시지 보강
8. **진단 Intent extras** — companion constant로 통일
9. **Launcher 아이콘** — adaptive icon foreground vector 추가

### 기능 동작 (백엔드 없이)
| 기능 | 동작 |
|------|------|
| 로그인 | 서버 실패 시 데모 토큰으로 홈 진입 |
| 보다 (홈) | API 실패/빈 응답 시 데모 작물·체크리스트·날씨 |
| 묻다 | CameraX 촬영 → 증상 선택 → 데모 진단 결과 |
| 로그아웃 | 토큰 삭제 후 로그인 화면 |

### 백엔드 연결 시
- Base URL: `RetrofitClient.BASE_URL` (`http://10.0.2.2:8080/api/`)
- 실기기: PC LAN IP로 변경

## 실행
1. Android Studio에서 `kiuda-android` 폴더 Open
2. Gradle Sync (Wrapper 자동 생성될 수 있음)
3. 에뮬레이터/기기 Run

## 기술
Kotlin · XML · ViewBinding · MVVM · Retrofit · CameraX · EncryptedSharedPreferences
