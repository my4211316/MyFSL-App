# 產生可以安裝到手機的正式版 APK。
# 在 PowerShell 執行：  powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
# 會先跑全部測試與 lint，全部通過才產生 APK，放在 release-apk\。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    throw '還沒有簽署金鑰。請先執行 tools\create-keystore.ps1（只需要一次）。'
}

# :core:domain 是純 Kotlin 模組（任務叫 test），其他是 Android 模組（testDebugUnitTest）。
& .\gradlew.bat clean :core:domain:test :core:data:testDebugUnitTest :core:data:lintRelease :app:lintRelease :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw '測試、lint 或建置失敗，沒有產生 APK。' }

$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$version = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
$code = [regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "找不到簽署過的 APK：$apk（keystore.properties 設定是否正確？）" }

$outDir = Join-Path $root 'release-apk'
New-Item -ItemType Directory -Force $outDir | Out-Null
$target = Join-Path $outDir "MyFSL-$version-$code.apk"
Copy-Item $apk $target -Force
$hash = (Get-FileHash $target -Algorithm SHA256).Hash

Write-Host ''
Write-Host "完成：$target"
Write-Host "SHA-256：$hash"
Write-Host '把這個檔案傳到手機安裝；更新前記得先在 App 裡匯出完整備份。'
