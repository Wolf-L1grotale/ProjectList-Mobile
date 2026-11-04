#requires -Version 5.1
<#
.SYNOPSIS
  One-click build of ProjectList Mobile: JDK + Android SDK found automatically, Gradle
  invoked, ready-to-install APK reported. Nothing else to configure.

.EXAMPLE
  .\build.ps1                        # build debug APK
  .\build.ps1 -Install               # build and install to the connected phone
  .\build.ps1 -Task assembleRelease  # release build
  .\build.ps1 -Clean                 # gradle clean, then build
  .\build.ps1 -Doctor                # print detected toolchain, do not build
#>
param(
    [string] $Task = "assembleDebug",
    [switch] $Install,
    [switch] $Clean,
    [switch] $Doctor
)

$ErrorActionPreference = "Stop"
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
Set-Location $root

function Write-Step($text) { Write-Host "-> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "X $text" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- toolchain
function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += (Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName })
    $candidates += (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName })
    $candidates += (Get-ChildItem "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk*" } | ForEach-Object { $_.FullName })

    $preferred = $candidates |
        Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } |
        Where-Object { $_ -match 'jdk-(21|17)(\D|$)' } |
        Select-Object -First 1

    if ($preferred) { return $preferred }
    return ($candidates | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1)
}

function Find-AndroidSdk {
    foreach ($v in $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) {
        if ($v -and (Test-Path (Join-Path $v "platforms"))) { return $v }
    }
    $local = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path (Join-Path $local "platforms")) { return $local }
    return $null
}

function Find-Adb($sdk) {
    $path = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path $path) { return $path }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    return if ($onPath) { $onPath.Source } else { $null }
}

$jdk = Find-Jdk
if (-not $jdk) { Fail "JDK not found. Install JDK 17 or 21 (e.g. Temurin 21) and retry." }
$env:JAVA_HOME = $jdk
Write-Step "JDK: $jdk"

$sdk = Find-AndroidSdk
if (-not $sdk) { Fail "Android SDK not found. Install it (Android Studio) or set ANDROID_HOME." }
Write-Step "Android SDK: $sdk"

$props = Join-Path $root "local.properties"
if (-not (Test-Path $props)) {
    "sdk.dir=$($sdk -replace '\\', '/')`n" | Set-Content -Path $props -Encoding ASCII
    Write-Step "Created local.properties (ignored by git)"
}

if ($Doctor) {
    Write-Host "`nToolchain is ready. Run .\build.ps1 to build." -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- build
if ($Clean) {
    Write-Step "gradle clean"
    & "$root\gradlew.bat" clean
    if ($LASTEXITCODE -ne 0) { Fail "gradle clean failed" }
}

Write-Step "gradle $Task (this can take a couple of minutes on the first run)"
$started = Get-Date
& "$root\gradlew.bat" $Task
if ($LASTEXITCODE -ne 0) { Fail "Build failed - see the Gradle output above" }
$elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)

# ---------------------------------------------------------------- result
$apk = Get-ChildItem -Path (Join-Path $root "app\build\outputs\apk") -Recurse -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Fail "Build finished, but no APK was found in app\build\outputs\apk" }

Write-Host "`nOK - $elapsed s" -ForegroundColor Green
Write-Host "APK: $($apk.FullName)"
Write-Host "Size: $([math]::Round($apk.Length / 1MB, 2)) MB"

if ($Install) {
    $adb = Find-Adb $sdk
    if (-not $adb) { Fail "adb not found - install Android SDK platform-tools" }
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice' }
    if (-not $devices) { Fail "No phone connected. Enable USB debugging and reconnect." }
    Write-Step "adb install -r $($apk.Name)"
    & $adb install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { Fail "adb install failed" }
    Write-Host "Installed on $($devices.Count) device(s)." -ForegroundColor Green
} else {
    Write-Host "Install to a phone: $(Find-Adb $sdk) install -r `"$($apk.FullName)`"" -ForegroundColor DarkGray
}
