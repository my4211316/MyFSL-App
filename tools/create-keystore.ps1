# 建立 MyFSL 正式版簽署金鑰（只需要做一次）。
# 在 PowerShell 執行：  powershell -ExecutionPolicy Bypass -File tools\create-keystore.ps1
# 密碼由你自己輸入，不會顯示在畫面上，也不會存到任何地方以外的 keystore.properties。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $root 'myfsl-release.jks'
$props = Join-Path $root 'keystore.properties'
$keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'

if (Test-Path $keystore) {
    Write-Host "已經有金鑰：$keystore"
    Write-Host '不要重新建立！換了金鑰，手機上的 App 就不能直接更新。'
    exit 1
}
if (-not (Test-Path $keytool)) { throw "找不到 keytool：$keytool" }

$secure = Read-Host '請設定金鑰密碼（至少 8 個字元）' -AsSecureString
$again = Read-Host '再輸入一次' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$plain2 = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($again))
if ($plain -ne $plain2) { throw '兩次密碼不一樣' }
if ($plain.Length -lt 8) { throw '密碼至少 8 個字元' }

& $keytool -genkeypair -v -keystore $keystore -alias myfsl -keyalg RSA -keysize 4096 -validity 36500 `
    -storepass $plain -keypass $plain -dname 'CN=MyFSL, O=Personal, C=TW'
if ($LASTEXITCODE -ne 0) { throw 'keytool 失敗' }

@"
storeFile=myfsl-release.jks
storePassword=$plain
keyAlias=myfsl
keyPassword=$plain
"@ | Set-Content -Path $props -Encoding ascii

Write-Host ''
Write-Host "完成：$keystore"
Write-Host '請把 myfsl-release.jks 和密碼另外備份（例如加密的隨身碟或密碼管理器）。'
Write-Host '遺失的話，之後的新版本就不能直接更新到手機上。'
