<#
One-time setup for signed builds + in-app updates.

  1. Creates the release signing key  %USERPROFILE%\.android\agesofsiege-release.jks  (random password).
  2. Writes keystore.properties in the repo root (git-ignored) so local builds use the same key.
  3. Uploads the key to the GitHub repository secrets so CI can sign and publish releases.

Every version of the game must be signed with this key, otherwise Android refuses to install updates.
BACK UP the .jks file and keystore.properties somewhere safe (e.g. a password manager / private cloud).

Usage (from the repo root):   powershell -ExecutionPolicy Bypass -File tools\setup-signing.ps1
#>
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $env:USERPROFILE '.android\agesofsiege-release.jks'
$propsFile = Join-Path $repoRoot 'keystore.properties'
$alias = 'agesofsiege'

function Find-Keytool {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) { return "$env:JAVA_HOME\bin\keytool.exe" }
    $studio = Get-ChildItem 'C:\Program Files\Android' -Directory -Filter 'Android Studio*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr\bin\keytool.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($studio) { return $studio }
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'keytool not found. Install Android Studio or set JAVA_HOME.'
}

if (Test-Path $keystore) {
    if (-not (Test-Path $propsFile)) {
        throw "$keystore already exists but keystore.properties is missing. Restore keystore.properties from your backup."
    }
    Write-Host "Using existing key $keystore"
    $password = ((Get-Content $propsFile | Where-Object { $_ -like 'storePassword=*' }) -replace '^storePassword=', '').Trim()
} else {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x')
    New-Item -ItemType Directory -Force -Path (Split-Path $keystore) | Out-Null
    & (Find-Keytool) -genkeypair -keystore $keystore -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 `
        -validity 36500 -storepass $password -keypass $password -dname 'CN=Ages of Siege' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    Write-Host "Created signing key $keystore"
}

@(
    '# Release signing (git-ignored). Back this file up together with the .jks!'
    "storeFile=$($keystore -replace '\\', '/')"
    "storePassword=$password"
    "keyAlias=$alias"
) | Set-Content -Encoding ascii $propsFile
Write-Host 'Wrote keystore.properties'

Push-Location $repoRoot
try {
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) | gh secret set SIGNING_KEYSTORE_BASE64
    gh secret set SIGNING_STORE_PASSWORD --body $password
    gh secret set SIGNING_KEY_ALIAS --body $alias
    if ($LASTEXITCODE -ne 0) { throw 'gh secret set failed - run "gh auth login" first' }
    Write-Host 'Uploaded GitHub secrets: SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS'
} finally {
    Pop-Location
}

Write-Host ''
Write-Host 'Done. Back up these two files somewhere safe:' -ForegroundColor Yellow
Write-Host "  $keystore"
Write-Host "  $propsFile"
