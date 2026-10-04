<#
.SYNOPSIS
  Pixiko Android 一键构建脚本：编出 APK、复制到 -OutDir、打印路径/字节数/sha256 前 16 位。

.DESCRIPTION
  本脚本是纯“调用方”，不修改工程源码。它做的事：
    1. 在脚本进程内设置 JAVA_HOME / ANDROID_SDK_ROOT / ANDROID_HOME / GRADLE_USER_HOME
       （绝不写系统环境变量、不动 PATH/注册表）。
    2. 优先用工程自带的 Gradle wrapper（.\gradlew.bat）；
       若 wrapper jar 缺失则回退到 -GradleDist 指向的本地 Gradle 发行版。
    3. 在 $PSScriptRoot（即本仓库副本的 android\ 目录）执行 -Task（默认 assembleDebug）。
    4. 找到生成的 APK，复制到 -OutDir，命名为 pixiko-<版本>-<buildType>.apk。
    5. 打印 APK 绝对路径、字节数、sha256 前 16 位。

  幂等：可重复运行；每次都会覆盖 -OutDir 下的同名 APK。
  失败时打印 gradle 输出的最后 40 行并 exit 非 0。

  兼容性：本文件以 **UTF-8 with BOM** 保存，Windows PowerShell 5.1 与 PowerShell 7+ (pwsh)
  都能正确解码其中的中文注释与输出（5.1 对无 BOM 的 UTF-8 会按 GBK 解码而报语法错误）。
  两种调用方式均已实测通过：
     powershell -NoProfile -ExecutionPolicy Bypass -File .\build-apk.ps1
     pwsh       -NoProfile -ExecutionPolicy Bypass -File .\build-apk.ps1
  推荐优先用 pwsh（7+），但 5.1 同样可用。修改本文件时请勿丢掉 BOM。

.PARAMETER SdkRoot
  Android SDK 根目录。默认 F:\android-toolchain\sdk

.PARAMETER JdkHome
  JDK 21 主目录（AGP 8.x 要求 JDK 17/21，本机默认 JDK 26 太新会被 Gradle 拒绝）。
  默认 F:\android-toolchain\jdk21

.PARAMETER GradleHome
  GRADLE_USER_HOME，放在 F 盘避免污染 C:\Users\<user>\.gradle。
  默认 F:\android-toolchain\gradle-home

.PARAMETER GradleDist
  本地 Gradle 发行版目录（仅当 wrapper jar 缺失时用于回退）。
  默认 F:\android-toolchain\gradle

.PARAMETER Task
  要执行的 Gradle 任务。默认 assembleDebug

.PARAMETER OutDir
  APK 输出目录。默认 F:\Bot\android\dist

.PARAMETER BuildType
  可选，强制指定 APK 变体（debug/release）。默认从 -Task 名推断。

.PARAMETER NoProxy
  不设置 HTTP(S)_PROXY 环境变量。默认会设置为本机代理 http://127.0.0.1:7890，
  因为本机直连 dl.google.com / repo.maven.apache.org 可能被墙。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
  powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Task assembleRelease -OutDir D:\out
#>

[CmdletBinding()]
param(
    [string] $SdkRoot    = 'F:\android-toolchain\sdk',
    [string] $JdkHome    = 'F:\android-toolchain\jdk21',
    [string] $GradleHome = 'F:\android-toolchain\gradle-home',
    [string] $GradleDist = 'F:\android-toolchain\gradle',
    [string] $Task       = 'assembleDebug',
    [string] $OutDir     = 'F:\Bot\android\dist',
    [string] $BuildType  = '',
    [switch] $NoProxy,
    [string] $Proxy      = 'http://127.0.0.1:7890'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 输出小工具
function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok  ($msg) { Write-Host " OK $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  ! $msg" -ForegroundColor Yellow }

# 原生命令包装：native 程序往 stderr 写东西（java -version、gradle 日志都会）时，
# Windows PowerShell 5.1 在 $ErrorActionPreference='Stop' 下会把 stderr 当成
# terminating error 直接把脚本打断。这里临时放宽，跑完恢复原值。
function Invoke-Native {
    param([string] $Exe, [string[]] $Arguments = @())
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments 2>&1
        $script:NativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
}

$ProjectDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ProjectDir)) {
    throw "无法确定脚本所在目录（`$PSScriptRoot 为空）。请用 -File 方式调用本脚本。"
}

Write-Host ""
Write-Host "==== Pixiko Android build ====" -ForegroundColor White
Write-Host "  project dir : $ProjectDir"
Write-Host "  task        : $Task"
Write-Host "  jdk         : $JdkHome"
Write-Host "  sdk         : $SdkRoot"
Write-Host "  gradle home : $GradleHome"
Write-Host "  out dir     : $OutDir"
Write-Host ""

# ---------------------------------------------------------------- 0. 前置校验
$fail = @()

if (-not (Test-Path -LiteralPath $ProjectDir)) { $fail += "工程目录不存在: $ProjectDir" }
foreach ($f in @('settings.gradle', 'build.gradle')) {
    if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir $f))) { $fail += "缺少 $f（android 工程还没写完？）" }
}

$javaExe = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { $fail += "找不到 JDK: $javaExe（-JdkHome 指向错误？）" }

$sdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $SdkRoot))     { $fail += "找不到 Android SDK: $SdkRoot" }
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-34\android.jar'))) {
    $fail += "缺少 platforms;android-34（$SdkRoot\platforms\android-34\android.jar）"
}
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'build-tools\34.0.0\aapt2.exe'))) {
    $fail += "缺少 build-tools;34.0.0（$SdkRoot\build-tools\34.0.0\aapt2.exe）"
}

if ($fail.Count -gt 0) {
    Write-Host "前置检查未通过：" -ForegroundColor Red
    $fail | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 2
}

# ---------------------------------------------------------------- 1. 环境变量（仅本进程）
$env:JAVA_HOME         = $JdkHome
$env:ANDROID_SDK_ROOT  = $SdkRoot
$env:ANDROID_HOME      = $SdkRoot
$env:GRADLE_USER_HOME  = $GradleHome
# 让 AGP/aapt2 不再去猜 JDK
$env:PATH = "$(Join-Path $JdkHome 'bin');$(Join-Path $SdkRoot 'platform-tools');$env:PATH"

$proxyProps = @()
if (-not $NoProxy) {
    $env:HTTP_PROXY  = $Proxy
    $env:HTTPS_PROXY = $Proxy
    # Gradle 守护进程是 fork 出来的独立 JVM，它不读 shell 的 HTTP(S)_PROXY 环境变量，
    # 所以必须把代理作为 JVM 系统属性传给它：JAVA_TOOL_OPTIONS 会被该 JVM 自动拾取。
    try {
        $pu = [System.Uri]$Proxy
        $ph = $pu.Host
        $pp = $pu.Port
        $proxyProps = @(
            "-Dhttp.proxyHost=$ph",  "-Dhttp.proxyPort=$pp",
            "-Dhttps.proxyHost=$ph", "-Dhttps.proxyPort=$pp"
        )
        $env:JAVA_TOOL_OPTIONS = ($proxyProps -join ' ')
        Write-Host "  proxy       : $Proxy (已注入 JAVA_TOOL_OPTIONS)" -ForegroundColor DarkGray
    } catch {
        Write-Warn "无法解析 -Proxy '$Proxy'，跳过 JVM 代理注入: $_"
    }
} else {
    Write-Host "  proxy       : 已禁用 (-NoProxy)" -ForegroundColor DarkGray
}

New-Item -ItemType Directory -Force -Path $GradleHome | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir     | Out-Null

Write-Step "JDK 版本"
Invoke-Native -Exe $javaExe -Arguments @('-version') | ForEach-Object { "    $_" }

# ---------------------------------------------------------------- 2. 选 launcher
$wrapperBat = Join-Path $ProjectDir 'gradlew.bat'
$wrapperJar = Join-Path $ProjectDir 'gradle\wrapper\gradle-wrapper.jar'

$useWrapper = (Test-Path -LiteralPath $wrapperBat) -and (Test-Path -LiteralPath $wrapperJar)

# 关键：**始终**显式传 -p <工程目录>。
# 原因：本工程自带的 gradlew.bat 是个自写 stub（只把 CLASSPATH 指到 gradle-wrapper.jar），
# 而 wrapper jar 在本仓库里并不存在；即便存在，Gradle 默认也会以“当前工作目录”为构建根，
# 从 F:\Bot 这类上层目录调用时会解析到错误的根目录（报 Directory 'F:\Bot' does not contain a Gradle build）。
# 显式 -p 对 wrapper 与本地发行版都有效，因此两条路径都不会踩这个坑。
$launcherArgs = @('-p', $ProjectDir)

if ($useWrapper) {
    Write-Step "使用工程自带 Gradle wrapper: gradlew.bat"
    $launcher = $wrapperBat
} else {
    if (Test-Path -LiteralPath $wrapperBat) {
        Write-Warn "gradlew.bat 存在但缺少 gradle\wrapper\gradle-wrapper.jar —— 回退到本地 Gradle 发行版"
    }
    $gradleBat = Join-Path $GradleDist 'bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $gradleBat)) {
        Write-Host "找不到可用的 Gradle：既没有 wrapper jar，也没有 $gradleBat" -ForegroundColor Red
        exit 2
    }
    Write-Step "使用本地 Gradle 发行版: $gradleBat"
    $launcher = $gradleBat
}

# ---------------------------------------------------------------- 3. 跑构建
$logDir = Join-Path $OutDir '_logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logDir "gradle-$Task-$stamp.log"

Write-Step "执行: $launcher $($launcherArgs -join ' ') $Task --stacktrace --console=plain"
Write-Host ""

$gradleArgs = @()
$gradleArgs += $launcherArgs
$gradleArgs += $Task
$gradleArgs += @('--console=plain', '--stacktrace')

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$raw = @(Invoke-Native -Exe $launcher -Arguments $gradleArgs | ForEach-Object { "$_" })
$exitCode = $script:NativeExitCode
if ($null -eq $exitCode) { $exitCode = $LASTEXITCODE }
$sw.Stop()

$raw | Set-Content -LiteralPath $logFile -Encoding utf8

$raw | ForEach-Object { Write-Host $_ }

Write-Host ""
Write-Host ("---- gradle 退出码 $exitCode，耗时 {0:N1}s，日志: {1}" -f $sw.Elapsed.TotalSeconds, $logFile)

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Host "构建失败。gradle 输出最后 40 行：" -ForegroundColor Red
    Write-Host "----------------------------------------------------------------" -ForegroundColor DarkGray
    $raw | Select-Object -Last 40 | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }
    Write-Host "----------------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "完整日志: $logFile" -ForegroundColor Red
    exit $exitCode
}

Write-Ok "gradle $Task 成功"

# ---------------------------------------------------------------- 3.5 该任务是否产出 APK？
# clean / tasks 这类任务本身没有 APK 产物，不能算失败（否则 -Task clean 会误报 exit 3）。
$apkProducing = $Task -match '(?i)(assemble|bundle|package)'
if (-not $apkProducing) {
    Write-Host ""
    Write-Host "任务 '$Task' 不是 APK 产出型任务（无 APK 可复制），视为成功。" -ForegroundColor Yellow
    Write-Host "日志: $logFile"
    exit 0
}

# ---------------------------------------------------------------- 4. 找 APK
if ([string]::IsNullOrWhiteSpace($BuildType)) {
    $BuildType = switch -Regex ($Task) {
        'Release' { 'release'; break }
        'Debug'   { 'debug';   break }
        default   { 'debug' }
    }
}
$BuildType = $BuildType.ToLowerInvariant()

# AGP 8.x 输出路径可能带/不带变体子目录，两种都找
$candidates = @(
    (Join-Path $ProjectDir "app\build\outputs\apk\$BuildType\app-$BuildType.apk"),
    (Join-Path $ProjectDir "app\build\outputs\apk\$BuildType\app-$BuildType-unsigned.apk"),
    (Join-Path $ProjectDir "app\build\outputs\apk\$BuildType")
)

$apks = @()
$searchRoot = Join-Path $ProjectDir 'app\build\outputs\apk'
foreach ($c in $candidates) {
    if (Test-Path -LiteralPath $c -PathType Leaf) {
        $apks += Get-Item -LiteralPath $c
    }
}
if ($apks.Count -eq 0) {
    # 兜底：整个 outputs 树里搜
    if (Test-Path -LiteralPath $searchRoot) {
        $apks = @(Get-ChildItem -LiteralPath $searchRoot -Recurse -Filter '*.apk' -File -ErrorAction SilentlyContinue)
    }
}
if ($apks.Count -eq 0) {
    Write-Host "构建成功但找不到 APK。已搜索: $searchRoot" -ForegroundColor Red
    exit 3
}

# 优先选非 unsigned、名字里带 buildType 的
$apk = $apks |
    Sort-Object @{ Expression = { if ($_.Name -like '*unsigned*') { 1 } else { 0 } } }, Length -Descending |
    Select-Object -First 1

Write-Step "构建产物: $($apk.FullName)"

# ---------------------------------------------------------------- 5. 版本号
$versionName = $null
$appGradle  = Join-Path $ProjectDir 'app\build.gradle'
if (Test-Path -LiteralPath $appGradle) {
    $txt = Get-Content -LiteralPath $appGradle -Raw
    if ($txt -match 'versionName\s*[= ]\s*["'']([^"'']+)["'']') { $versionName = $Matches[1] }
}
if ([string]::IsNullOrWhiteSpace($versionName)) {
    Write-Warn "没能从 app\build.gradle 解析出 versionName，用 'unknown' 代替"
    $versionName = 'unknown'
}
Write-Step "版本号 versionName = $versionName"

# ---------------------------------------------------------------- 6. 复制到 OutDir
$destName = "pixiko-$versionName-$BuildType.apk"
$destPath = Join-Path $OutDir $destName
Copy-Item -LiteralPath $apk.FullName -Destination $destPath -Force

# ---------------------------------------------------------------- 7. 报告
$outItem = Get-Item -LiteralPath $destPath
$hash    = (Get-FileHash -LiteralPath $destPath -Algorithm SHA256).Hash.ToLowerInvariant()
$hash16  = $hash.Substring(0, 16)

Write-Host ""
Write-Host "================ 构建完成 ================" -ForegroundColor Green
Write-Host "  APK 路径 : $($outItem.FullName)"
Write-Host "  字节数   : $($outItem.Length)"
Write-Host "  sha256   : $hash16  (前 16 位)"
Write-Host "  完整sha256: $hash"
Write-Host "  变体     : $BuildType"
Write-Host "  版本     : $versionName"
Write-Host "  日志     : $logFile"
Write-Host "==========================================" -ForegroundColor Green

# aapt2 badging 校验（best-effort，不因为它的失败而让整个脚本失败）
$aapt2 = Join-Path $SdkRoot 'build-tools\34.0.0\aapt2.exe'
if (Test-Path -LiteralPath $aapt2) {
    Write-Host ""
    Write-Step "aapt2 dump badging"
    $badging = @(Invoke-Native -Exe $aapt2 -Arguments @('dump', 'badging', $destPath))
    $badging | Where-Object { $_ -match "^(package:|launchable-activity:|sdkVersion:|targetSdkVersion:|application-label:)" } |
        ForEach-Object { Write-Host "    $_" }
}

exit 0
