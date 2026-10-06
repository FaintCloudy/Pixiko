<#
.SYNOPSIS
  Pixiko 发版前「只读」校验：版本号对不对、产物齐不齐、sha256 对不对、APK 里有没有本机局域网地址。

.DESCRIPTION
  规则来源：仓库 `RELEASE.md` 的「发行清单（每版必做）」。这次校验只做四件事，逐条打印
  PASS / FAIL / WARN，最后 **有 FAIL 就以退出码 1 结束**（全 PASS 退出 0）：

    ① android\app\build.gradle 的 versionName ＝ 传入的发行号（**不一致就不许发**）
    ② versionCode 与发行号口径一致（1.4.0→140 / 1.5.3→153 / 1.6.0→160，即 major*100+minor*10+patch；
       对不上只是 WARN，因为两位 patch 的老版本套不上这个公式；-Strict 下升级为 FAIL）
    ③ 发行目录里**同时**存在：两个 zip、两个 `.sha256`、APK
    ④ 两个 `.sha256` 的内容与 zip 的实测 sha256 一致（hex 与文件名都要对）
    ⑤ APK 的 sha256 与发行里给出的 sha256 一致（-ExpectApkSha256，或 `<apk>.sha256` 边车）
    ⑥ **APK 里没有本机局域网地址**：搜 `classes*.dex` / `assets/` / `res/raw/` / `AndroidManifest.xml`；
       出现本机网卡的私网地址即 FAIL；dex 里出现**源码文本里没有**的私网字面量也 FAIL
       （那只能来自构建期自动探测并烧入的地址 —— 发布包必须用 -NoDefaultHost 构建）
    ⑦ 发行的 APK 与 android\dist\ 那份「本机测试包」不是同一个文件（sha256 相同即 FAIL，
       因为带局域网地址的包只能留在 android\dist\，绝不能出现在发行里）

  **只读**：不构建、不下载、不联网、不写任何文件、不改任何东西、不启动任何进程。

.PARAMETER Version
  本次发行号。`1.7.0` 与 `v1.7.0` 都接受（自动去掉开头的 v）。

.PARAMETER ReleaseDir
  发行目录。默认 `F:\Bot\work\release\v<发行号>`（上一版的实际位置：`F:\Bot\work\release\v1.6.0`）。
  产物可以直接在它下面，也可以在它的 `out\` 子目录下（上一版就在 `out\` 里）。

.PARAMETER Apk
  显式指定要校验的发布 APK。默认在发行目录里找 `pixiko-<发行号>-debug.apk`。

.PARAMETER RepoRoot
  仓库根目录，默认本脚本所在目录的上一级（即 `android\` 的父目录）。用来读 `app\build.gradle`
  的版本号、以及「源码里本来就有哪些 IP 字面量」的白名单。

.PARAMETER ExpectApkSha256
  发行里给出的 APK sha256（64 位 hex）。给了就拿它核对；不给就看 APK 旁边的 `.sha256` 边车。

.PARAMETER DistApk
  本机测试包路径。默认 `<仓库根>\android\dist\pixiko-<发行号>-debug.apk`。

.PARAMETER Strict
  按**新口径**严查：APK 必须给出 sha256、versionCode 必须与口径一致（否则 FAIL）。
  默认（不加 -Strict）对这两种情况只报 WARN，这样上一版 v1.6.0 的真实产物（APK 没有 .sha256 边车）
  也能如实 PASS —— 见 RELEASE.md 第 1 节对「APK 的 sha256 按上一版实际做法」的说明。

.PARAMETER NoApkScan
  跳过第 ⑥ 条（APK 内局域网地址扫描）。默认会扫。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File android\verify-release.ps1 -Version 1.7.0

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File android\verify-release.ps1 -Version 1.7.0 -ReleaseDir D:\out\v1.7.0 -Strict

.NOTES
  本文件以 **UTF-8 with BOM** 保存（含中文；Windows PowerShell 5.1 对无 BOM 的 UTF-8 会按 GBK 解码而乱码）。
  作者：loriko（deloriko@outlook.com）。
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Version,
    [string] $ReleaseDir = '',
    [string] $Apk = '',
    [string] $RepoRoot = '',
    [string] $ExpectApkSha256 = '',
    [string] $DistApk = '',
    [switch] $Strict,
    [switch] $NoApkScan
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 输出小工具 + 计数
$script:failCount = 0
$script:warnCount = 0
function Write-Head([string] $msg) { Write-Host ""; Write-Host "== $msg" -ForegroundColor Cyan }
function Write-Pass([string] $msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail([string] $msg) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    $script:failCount++
}
function Write-Warn([string] $msg) {
    Write-Host "  [WARN] $msg" -ForegroundColor Yellow
    $script:warnCount++
}
function Write-Info([string] $msg) { Write-Host "         $msg" -ForegroundColor DarkGray }
function Write-Skip([string] $msg) { Write-Host "  [SKIP] $msg" -ForegroundColor DarkGray }

function Get-Sha256([string] $path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }

# 从 "<64 hex><两个空格><文件名><CRLF>" 这种 sha256 边车里取出 hex 与文件名（取第一行）
function Read-Sha256Sidecar([string] $path) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $line = ($text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ($line -match '^\s*([0-9a-fA-F]{64})\s+\*?(.+?)\s*$') {
        return [pscustomobject]@{ hex = $Matches[1].ToLowerInvariant(); name = $Matches[2]; raw = $line }
    }
    return $null
}

function Test-PrivateIpv4([string] $ip) {
    $p = @($ip.Split('.'))
    if ($p.Count -ne 4) { return $false }
    $o = @()
    foreach ($x in $p) {
        if ($x -notmatch '^\d{1,3}$') { return $false }
        $n = [int]$x
        if ($n -lt 0 -or $n -gt 255) { return $false }
        $o += $n
    }
    if ($o[0] -eq 10) { return $true }                                   # 10.0.0.0/8
    if ($o[0] -eq 172 -and $o[1] -ge 16 -and $o[1] -le 31) { return $true } # 172.16.0.0/12
    if ($o[0] -eq 192 -and $o[1] -eq 168) { return $true }               # 192.168.0.0/16
    if ($o[0] -eq 169 -and $o[1] -eq 254) { return $true }               # 169.254.0.0/16 (APIPA)
    if ($o[0] -eq 100 -and $o[1] -ge 64 -and $o[1] -le 127) { return $true } # 100.64.0.0/10 (CGNAT)
    return $false
}

function Test-LoopbackIpv4([string] $ip) {
    if ($ip -eq '0.0.0.0') { return $true }
    return ($ip -match '^127\.')
}

# ---------------------------------------------------------------- 头部
$ver = $Version.Trim() -replace '^[vV]', ''
Write-Host ""
Write-Host "======== Pixiko 发版前校验（只读） ========" -ForegroundColor White
Write-Host "  发行号     : $ver"
Write-Host "  脚本       : $PSCommandPath"
Write-Host "  严格模式   : $([bool]$Strict)"
Write-Host "========================================="

if ($ver -notmatch '^\d+\.\d+\.\d+$') {
    Write-Fail "发行号 '$Version' 不是 x.y.z 形式（例：1.7.0 或 v1.7.0）"
    Write-Host ""
    Write-Host "结果：发行号形式不对，无法校验。" -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
if ([string]::IsNullOrWhiteSpace($ReleaseDir)) { $ReleaseDir = Join-Path 'F:\Bot\work\release' "v$ver" }
Write-Host "  仓库根目录 : $RepoRoot"
Write-Host "  发行目录   : $ReleaseDir"

# ---------------------------------------------------------------- ① 版本号
Write-Head "① versionName ＝ 本次发行号"
$buildGradle = Join-Path $RepoRoot 'android\app\build.gradle'
$gradleName = $null
$gradleCode = $null
if (-not (Test-Path -LiteralPath $buildGradle -PathType Leaf)) {
    Write-Fail "找不到 android\app\build.gradle（仓库根目录给错了？）：$buildGradle"
} else {
    # 显式按 UTF-8 读；只读，绝不写。
    $gt = [System.IO.File]::ReadAllText($buildGradle, [System.Text.Encoding]::UTF8)
    if ($gt -match 'versionName\s*[= ]\s*["'']([^"'']+)["'']') { $gradleName = $Matches[1] }
    if ($gt -match 'versionCode\s*[= ]\s*(\d+)') { $gradleCode = [int]$Matches[1] }
    Write-Info "build.gradle : $buildGradle"
    Write-Info "versionName  : $gradleName"
    Write-Info "versionCode  : $gradleCode"
    if ([string]::IsNullOrWhiteSpace($gradleName)) {
        Write-Fail "没能从 build.gradle 里解析出 versionName"
    } elseif ($gradleName -eq $ver) {
        Write-Pass "versionName '$gradleName' ＝ 本次发行号 '$ver'"
    } else {
        Write-Fail "versionName '$gradleName' ≠ 本次发行号 '$ver'（不一致就不许发：改 build.gradle 或改发行号）"
    }
}

# ---------------------------------------------------------------- ② versionCode
Write-Head "② versionCode 与发行号口径"
$parts = @($ver.Split('.'))
$expectCode = [int]$parts[0] * 100 + [int]$parts[1] * 10 + [int]$parts[2]
if ($null -eq $gradleCode -or $gradleCode -le 0) {
    Write-Fail "build.gradle 里没有可用的 versionCode（读到：$gradleCode）"
} elseif ($gradleCode -eq $expectCode) {
    Write-Pass "versionCode $gradleCode ＝ major*100+minor*10+patch（$expectCode）"
} else {
    $msg = "versionCode $gradleCode ≠ 按口径算出的 $expectCode（两位 patch 的老版本套不上这个公式；只要确实比上一版大也可以）"
    if ($Strict) { Write-Fail "$msg（-Strict）" } else { Write-Warn $msg }
}

# ---------------------------------------------------------------- ③ 产物清单
Write-Head "③ 发行目录里的产物清单（两个 zip + 两个 .sha256 + APK）"
$outDir = Join-Path $ReleaseDir 'out'
if (-not (Test-Path -LiteralPath $ReleaseDir -PathType Container)) {
    Write-Fail "发行目录不存在：$ReleaseDir"
}

function Find-ReleaseFile([string] $name) {
    foreach ($d in @($ReleaseDir, $outDir)) {
        $p = Join-Path $d $name
        if (Test-Path -LiteralPath $p -PathType Leaf) { return (Get-Item -LiteralPath $p) }
    }
    return $null
}

$srcZipName = "pixiko-v$ver.zip"
$runZipName = "pixiko-v$ver-runnable.zip"
$srcZip = Find-ReleaseFile $srcZipName
$runZip = Find-ReleaseFile $runZipName
$srcSha = Find-ReleaseFile "$srcZipName.sha256"
$runSha = Find-ReleaseFile "$runZipName.sha256"

$apkItem = $null
if (-not [string]::IsNullOrWhiteSpace($Apk)) {
    if (Test-Path -LiteralPath $Apk -PathType Leaf) { $apkItem = Get-Item -LiteralPath $Apk }
    else { Write-Fail "指定的 APK 不存在：$Apk" }
} else {
    $apkItem = Find-ReleaseFile "pixiko-$ver-debug.apk"
    if ($null -eq $apkItem) { $apkItem = Find-ReleaseFile "pixiko-android-$ver-debug.apk" }
}

foreach ($pair in @(
        @{ item = $srcZip; name = $srcZipName; what = '源码包 zip' },
        @{ item = $srcSha; name = "$srcZipName.sha256"; what = '源码包 sha256' },
        @{ item = $runZip; name = $runZipName; what = '开箱即用包 zip' },
        @{ item = $runSha; name = "$runZipName.sha256"; what = '开箱即用包 sha256' })) {
    if ($null -eq $pair.item) {
        Write-Fail "缺 $($pair.what) ：$($pair.name)（找过 $ReleaseDir 和 $outDir）"
    } else {
        Write-Pass "$($pair.what) 在：$($pair.item.FullName)  ($($pair.item.Length) B)"
    }
}
if ($null -eq $apkItem) {
    Write-Fail "缺安卓 APK：pixiko-$ver-debug.apk（找过 $ReleaseDir 和 $outDir）—— 每一版都必须同时发安卓版"
} else {
    Write-Pass "安卓 APK 在：$($apkItem.FullName)  ($($apkItem.Length) B)"
}

# ---------------------------------------------------------------- ④ zip 的 sha256
Write-Head "④ 两个 zip 的 .sha256 与实测 sha256 一致"
foreach ($triple in @(
        @{ zip = $srcZip; sha = $srcSha; name = $srcZipName },
        @{ zip = $runZip; sha = $runSha; name = $runZipName })) {
    if ($null -eq $triple.zip -or $null -eq $triple.sha) {
        Write-Skip "$($triple.name) 或它的 .sha256 不在，跳过"
        continue
    }
    $side = Read-Sha256Sidecar $triple.sha.FullName
    if ($null -eq $side) {
        Write-Fail "$($triple.sha.Name) 内容不是 '<64 位 hex><两个空格><文件名>' 格式"
        continue
    }
    $real = Get-Sha256 $triple.zip.FullName
    if ($side.hex -ne $real) {
        Write-Fail "$($triple.name) sha256 不符：边车 $($side.hex) ≠ 实测 $real"
    } elseif ($side.name -ne $triple.name) {
        Write-Fail "$($triple.sha.Name) 里写的文件名 '$($side.name)' ≠ '$($triple.name)'"
    } else {
        Write-Pass "$($triple.name) sha256 $real（边车一致）"
    }
}

# ---------------------------------------------------------------- ⑤ APK 的 sha256
Write-Head "⑤ APK 的 sha256 与发行里给出的一致"
$apkSha = $null
if ($null -ne $apkItem) {
    $apkSha = Get-Sha256 $apkItem.FullName
    Write-Info "APK 实测：$($apkItem.Length) B  $apkSha"
    $want = ''
    $where = ''
    if (-not [string]::IsNullOrWhiteSpace($ExpectApkSha256)) {
        $want = $ExpectApkSha256.Trim().ToLowerInvariant()
        $where = '-ExpectApkSha256'
    } elseif (Test-Path -LiteralPath ($apkItem.FullName + '.sha256') -PathType Leaf) {
        $side = Read-Sha256Sidecar ($apkItem.FullName + '.sha256')
        if ($null -ne $side) { $want = $side.hex; $where = ($apkItem.Name + '.sha256') }
    }
    if ([string]::IsNullOrWhiteSpace($want)) {
        $msg = "发行里没有给出 APK 的 sha256（没有 .sha256 边车，也没传 -ExpectApkSha256）" +
               "—— v1.6.0 的实际做法就是这样（verify-local.txt 里写 '(no sidecar by design)'），" +
               "但新口径要求 APK 的 sha256 必须出现在发行里"
        if ($Strict) { Write-Fail "$msg（-Strict）" } else { Write-Warn $msg }
        Write-Info "把这一行抄进发行正文即可：$apkSha  $($apkItem.Name)"
    } elseif ($want -eq $apkSha) {
        Write-Pass "APK sha256 $apkSha（来自 $where）"
    } else {
        Write-Fail "APK sha256 不符：发行里给的 $want ≠ 实测 $apkSha（来自 $where）"
    }
} else {
    Write-Skip "APK 不在，跳过"
}

# ---------------------------------------------------------------- ⑥ APK 里有没有局域网地址
Write-Head "⑥ APK 里没有本机局域网地址（发布包必须是 -NoDefaultHost 那份）"
if ($null -eq $apkItem) {
    Write-Skip "APK 不在，跳过"
} elseif ($NoApkScan) {
    Write-Skip "用了 -NoApkScan，跳过扫描"
} else {
    # 本机所有网卡的 IPv4（不含回环）：这些地址绝不该出现在发布包里。
    $machineIps = New-Object System.Collections.ArrayList
    foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
        foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
            if ($ua.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
            $ip = $ua.Address.ToString()
            if ($ip -match '^127\.') { continue }
            if (-not $machineIps.Contains($ip)) { [void]$machineIps.Add($ip) }
        }
    }
    Write-Info ("本机网卡 IPv4（不含回环）：" + ($machineIps -join ', '))

    # 「源码里本来就有的 IP 字面量」白名单：构建期自动探测出来的地址**绝不可能**出现在源码里，
    # 所以 dex 里出现一个源码里没有的私网字面量，就说明它是构建时烧进去的。
    $srcIps = New-Object System.Collections.ArrayList
    $srcMain = Join-Path $RepoRoot 'android\app\src\main'
    if (Test-Path -LiteralPath $srcMain -PathType Container) {
        foreach ($f in (Get-ChildItem -LiteralPath $srcMain -Recurse -File -Include '*.java', '*.xml', '*.md', '*.txt', '*.json', '*.properties', '*.gradle', '*.pro' -ErrorAction SilentlyContinue)) {
            try {
                $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
            } catch { continue }
            foreach ($m in [regex]::Matches($txt, '(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\d.])')) {
                $v = $m.Value
                if (-not $srcIps.Contains($v)) { [void]$srcIps.Add($v) }
            }
        }
        Write-Info ("源码 app\src\main 里出现过的 IP 字面量（白名单）：" + (($srcIps | Sort-Object) -join ', '))
    } else {
        Write-Warn "找不到 $srcMain，没法建白名单：只要 dex 里出现私网字面量就按 FAIL 处理"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apkItem.FullName)
    try {
        $scanned = 0
        $dexFound = 0
        $machineHits = New-Object System.Collections.ArrayList
        $unknownPrivate = New-Object System.Collections.ArrayList
        $publicSeen = New-Object System.Collections.ArrayList
        foreach ($entry in $zip.Entries) {
            $isDex = ($entry.FullName -match '^classes\d*\.dex$')
            $interesting = $isDex -or ($entry.FullName -like 'assets/*') -or ($entry.FullName -like 'res/raw/*') -or
                           ($entry.FullName -eq 'AndroidManifest.xml') -or ($entry.FullName -eq 'resources.arsc')
            if (-not $interesting) { continue }
            if ($entry.Length -gt 67108864) { continue }
            $ms = New-Object System.IO.MemoryStream
            $st = $entry.Open()
            try { $st.CopyTo($ms) } finally { $st.Close() }
            $bytes = $ms.ToArray()
            $ms.Dispose()
            $scanned++
            $ascii = [System.Text.Encoding]::ASCII.GetString($bytes)

            # (a) 本机网卡地址：出现在任何被扫条目里都算发布事故
            foreach ($ip in $machineIps) {
                if ($ascii.IndexOf($ip) -ge 0) {
                    [void]$machineHits.Add("$ip 出现在 $($entry.FullName)")
                }
            }
            if (-not $isDex) { continue }
            $dexFound++
            # (b) dex 里的 IPv4 字面量：私网且源码里没有 → 只可能是构建期烧入的
            foreach ($m in [regex]::Matches($ascii, '(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\d.])')) {
                $lit = $m.Value
                $addr = $lit
                if ($addr.Contains(':')) { $addr = $addr.Substring(0, $addr.IndexOf(':')) }
                if (Test-LoopbackIpv4 $addr) { continue }
                if ($srcIps.Contains($lit) -or $srcIps.Contains($addr)) { continue }
                if (Test-PrivateIpv4 $addr) {
                    $hit = "$lit 出现在 $($entry.FullName)"
                    if (-not $unknownPrivate.Contains($hit)) { [void]$unknownPrivate.Add($hit) }
                } elseif (-not $publicSeen.Contains($lit)) {
                    [void]$publicSeen.Add($lit)
                }
            }
        }
        Write-Info "扫描条目 $scanned 个（其中 dex $dexFound 个）"

        if ($machineHits.Count -gt 0) {
            foreach ($h in $machineHits) { Write-Fail "本机地址：$h —— 发布包必须用 -NoDefaultHost 构建（带地址的包只能留在 android\dist\）" }
        } else {
            Write-Pass "本机网卡地址（$($machineIps.Count) 个）在 APK 里 0 命中"
        }
        if ($unknownPrivate.Count -gt 0) {
            foreach ($h in $unknownPrivate) { Write-Fail "dex 里有源码里没有的私网地址：$h —— 这是构建期烧进去的，发布包必须用 -NoDefaultHost 构建" }
        } else {
            Write-Pass "dex 里的私网地址字面量全部能在源码里找到出处（静态示例文本），没有构建期烧入的地址"
        }
        if ($publicSeen.Count -gt 0) {
            Write-Info ("dex 里还有这些非私网 IPv4 字面量（只提示，不判失败）：" + (($publicSeen | Sort-Object) -join ', '))
        }
    } finally {
        $zip.Dispose()
    }
}

# ---------------------------------------------------------------- ⑦ 发布包 ≠ 本机测试包
Write-Head "⑦ 发行的 APK 与「本机测试包」不是同一个文件"
$distCandidates = New-Object System.Collections.ArrayList
if (-not [string]::IsNullOrWhiteSpace($DistApk)) {
    [void]$distCandidates.Add($DistApk)
} else {
    # build-apk.ps1 的默认 -OutDir 是 F:\Bot\android\dist（运行树）；仓库副本里也可能留着一份，两处都看。
    [void]$distCandidates.Add((Join-Path 'F:\Bot\android\dist' "pixiko-$ver-debug.apk"))
    [void]$distCandidates.Add((Join-Path $RepoRoot "android\dist\pixiko-$ver-debug.apk"))
}
$distFound = @()
foreach ($c in $distCandidates) {
    if (Test-Path -LiteralPath $c -PathType Leaf) { $distFound += (Get-Item -LiteralPath $c) }
}
if ($null -eq $apkItem) {
    Write-Skip "APK 不在，跳过"
} elseif ($distFound.Count -eq 0) {
    Write-Warn ("没有找到本机测试包（找过：" + ($distCandidates -join ' ; ') + "）—— 没构建过就正常，跳过")
} else {
    $sameAsDist = $false
    foreach ($d in $distFound) {
        $dSha = Get-Sha256 $d.FullName
        Write-Info ("本机测试包：" + $d.FullName + "  " + $d.Length + " B  " + $dSha)
        if ($dSha -eq $apkSha) {
            $sameAsDist = $true
            Write-Fail "发行的 APK 就是本机测试包 $($d.FullName)（sha256 相同 $dSha）—— 带局域网地址的包绝不能发行"
        }
    }
    if (-not $sameAsDist) { Write-Pass "发行包与找到的 $($distFound.Count) 份本机测试包都不是同一个文件" }
}

# ---------------------------------------------------------------- 汇总
Write-Host ""
Write-Host "=========================================" -ForegroundColor White
if ($script:failCount -gt 0) {
    Write-Host "结果：FAIL $($script:failCount) 条，WARN $($script:warnCount) 条 —— 不许发版（退出码 1）" -ForegroundColor Red
    exit 1
}
Write-Host "结果：全部检查通过（WARN $($script:warnCount) 条）—— 可以发版（退出码 0）" -ForegroundColor Green
exit 0
