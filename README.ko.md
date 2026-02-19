# yt-dlpk (Kotlin + Compose Desktop)

Kotlin + Compose Multiplatform 기반 `yt-dlp` 데스크톱 GUI입니다 (Windows/macOS/Linux).

언어별 README:
- 영어: `README.md`
- 일본어: `README.ja.md`
- 한국어: `README.ko.md`

## 현재 구현 범위 (MVP+)

- URL 입력 + Analyze
  - `yt-dlp --dump-single-json`으로 메타데이터 조회
  - `yt-dlp -F`로 포맷 목록 조회
- 포맷 선택 UI
  - 탭: `영상+오디오 / 영상만 / 오디오만`
  - 테이블형 목록: `Format | Resolution | Bitrate`
  - 해상도/비트레이트 높은 순 정렬
  - 선택 행은 포인트 컬러 아이콘으로 표시
  - `영상+오디오` 기본 선택은 최고 해상도 우선
- 다운로드 옵션
  - 자막: `--write-subs --write-auto-subs --sub-lang --convert-subs srt`
  - 오디오 추출: `-x --audio-format` (드롭다운)
  - 저장 폴더 선택
  - 파일명 템플릿
  - 플레이리스트 전체/단일 전환
  - 병합 출력 포맷 드롭다운
- 진행/제어
  - `--newline` 기반 진행 파싱
  - 취소 지원
  - Analyze 중 Progress 영역 상태 문구 표시
  - Analyze 완료 전 Download 버튼 비활성화
- 도구 관리
  - 앱 내부 도구/시스템 PATH 도구 모두 감지
  - 없으면 `yt-dlp`/`ffmpeg` 자동 다운로드
  - `tool-sources.json`으로 URL 변경 가능
  - Global 탭: 버전 표시, 최신 확인, 업데이트
  - 시스템 관리 도구는 안내 다이얼로그 표시
- UI/UX
  - 플레이리스트에서 첫 항목 썸네일 폴백
  - 초기/최소 윈도우 크기 지정
  - 테마: `SYSTEM / DARK / LIGHT`
  - 언어: `English / 日本語 / 한국어` 즉시 전환

## 아키텍처

- UI 레이어와 도메인/서비스 레이어 분리
- `StateFlow` 기반 상태 관리
- Coroutine 기반 비동기 프로세스 실행
- 핵심 서비스:
  - `FormatService`
  - `YtDlpCommandBuilder`
  - `YtDlpService`
  - `ToolManager`

## 실행

```bash
./gradlew run
```

## 패키징

```bash
./gradlew packageDistributionForCurrentOS
```

타겟: `Dmg`, `Msi`, `Deb`, `Rpm`

## 수동 테스트 체크리스트

1. `./gradlew run` 실행
2. URL 입력 후 `Analyze`
3. 메타정보/썸네일/포맷 목록 확인
4. `영상+오디오`에서 최고 해상도 기본 선택 확인
5. 탭 전환 및 포맷 선택 확인
6. 다운로드 시작 후 진행률 확인
7. 취소 동작 확인
8. 자막/오디오 추출/출력 포맷 옵션 확인
9. Global 탭의 최신 확인/업데이트 버튼 확인
10. 언어/테마 전환 즉시 반영 확인

## 알려진 제한사항

- `yt-dlp -F` 출력은 사이트마다 달라 파서는 휴리스틱 기반
- ffmpeg 배포 자산 이름이 향후 변경될 수 있음
- 썸네일 URL 접근 불가 시 표시되지 않을 수 있음

## 주요 파일

- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `src/desktopMain/kotlin/com/ytdlpk/app/Main.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/ui/App.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/ui/AppViewModel.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/FormatService.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/YtDlpCommandBuilder.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/YtDlpService.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/ToolManager.kt`
- `src/desktopMain/resources/tool-sources.json`
