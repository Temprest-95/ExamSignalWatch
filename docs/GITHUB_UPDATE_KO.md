# v0.3를 GitHub에 올리는 방법

GitHub에 v0.1 또는 v0.2를 올린 경우, **v0.3 전체 프로젝트 폴더**를 기준으로 업데이트해야 합니다. v0.3는 이전 파일에 추가된 코드도 포함합니다.

기존 파일을 웹 브라우저의 'Upload files'로 다시 올릴 때에는 같은 경로/이름을 가진 파일 덮어쓰기가 제한될 수 있습니다. 그래서 **GitHub Desktop**이나 로컬 Git을 이용하는 방법을 추천합니다.

## GitHub Desktop
1. GitHub Desktop에서 본인 계정으로 로그인합니다.
2. File → Clone repository → URL 에 `https://github.com/Temprest-95/ExamSignalWatch` 를 입력합니다.
3. 내려받은 저장소 폴더에 이 ZIP을 풀어 나온 **프로젝트 내용 전체**를 복사해 덮어씁니다. `.github` 숨김 폴더도 포함되어야 합니다.
4. GitHub Desktop의 Changes 화면에서 바뀐 파일 확인 → Summary에 `Add v0.3 exam baseline and automatic AP filtering` 입력 → `Commit to main`.
5. `Push origin`을 클릭하고 GitHub 웹에서 Actions 실행 결과를 확인합니다.
6. 성공한 작업에서 `ExamSignalWatch-debug-apk` 아티팩트를 내려받아 테스트합니다.

## 명령줄 Git 사용자
클론된 저장소의 **루트**에 프로젝트 전체 파일을 덮어쓴 다음 아래 명령을 사용합니다.

```shell
git add -A
git commit -m "Add v0.3 baseline, AP filters and signal tracking"
git push origin main
```

### 중요
- `.github/workflows/android-debug.yml` 파일은 `android-actions/setup-android@v4` 유지.
- 소스 파일을 저장소 안의 `ExamSignalWatch_v0.3/`라는 새 하위 폴더에 넣지 말 것. 프로젝트 루트에 `app`, `gradle`, `build.gradle.kts`가 바로 보이도록 유지.
- 변경된 APK를 설치하기 전에 검사 기록을 CSV로 백업. GitHub debug 서명 키가 달라 기존 앱을 제거해야 할 수 있음.
- APK 빌드/실기기 동작은 검증 전이므로 결과를 확인한 후 대외 공개.
