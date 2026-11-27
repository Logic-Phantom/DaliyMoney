# DailyRoll 로컬 Android 빌드 스크립트 (Windows PowerShell)
#
# 전역 환경 변수를 건드리지 않고 이 프로세스에만 JDK 17 / Android SDK 경로를 지정한다.
# 사용 예:
#   npm run android:debug         # assembleDebug (APK)
#   npm run test:native           # Kotlin 도메인·파서 단위 테스트
#   npm run android:lint          # 네이티브 모듈 Android Lint
#
# 경로는 DAILYROLL_JAVA_HOME / DAILYROLL_ANDROID_HOME 로 덮어쓸 수 있다.
# 클라우드 빌드는 EAS를 쓴다: npx eas-cli build -p android --profile preview
param(
  [string]$Task = "assembleDebug",
  [string]$Architectures = "arm64-v8a",
  # CMake 최소값 128 (128 미만이면 Expo가 무시하고 1024를 씀)
  [int]$ObjectPathMax = 128
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Pick([string]$override, [string]$fallback) {
  if ($override) { return $override } else { return $fallback }
}

$env:JAVA_HOME = Pick $env:DAILYROLL_JAVA_HOME "C:\Android\jdk-17"
$env:ANDROID_HOME = Pick $env:DAILYROLL_ANDROID_HOME "C:\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# 사용자 폴더에 비ASCII 문자가 있으면 일부 Gradle/CMake 단계가 깨질 수 있어 ASCII 임시 폴더를 쓴다.
$tmp = Pick $env:DAILYROLL_TMP "C:\dailyroll-tmp"
New-Item -ItemType Directory -Force $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp
$env:NODE_ENV = if ($Task -like "*Release*") { "production" } else { "development" }

if (-not (Test-Path (Join-Path $root "android\gradlew.bat"))) {
  Write-Host "android/ 폴더가 없습니다. 먼저 'npm run prebuild' 를 실행하세요."
  exit 1
}

[IO.File]::WriteAllLines((Join-Path $root "android\local.properties"), @("sdk.dir=$($env:ANDROID_HOME.Replace('\', '/'))"))

Push-Location (Join-Path $root "android")
try {
  # "lintDebug assembleDebug" 처럼 공백으로 여러 태스크를 넘길 수 있다.
  $tasks = $Task -split '\s+' | Where-Object { $_ }
  & .\gradlew.bat @tasks "-PreactNativeArchitectures=$Architectures" "-Pexpo.android.cmakeObjectPathMax=$ObjectPathMax" --console=plain
  exit $LASTEXITCODE
} finally {
  Pop-Location
}
