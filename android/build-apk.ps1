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
  6. 构建前自动探测「本机局域网 IP:端口」，用 -PpixikoDefaultHost=… 传给 Gradle
     （app/build.gradle 把它变成 BuildConfig.PIXIKO_DEFAULT_HOST，app 首次运行会把它
     预填到「服务器设置」的地址框里，方便装机后直接连；只是预填，不自动连接、不绕令牌）。
     构建结束时会把「本次烧进去的地址」和生成的 BuildConfig 那一行打印出来。
     探测规则见下面「0.5 探测本机局域网地址」一段；-NoDefaultHost 可完全关掉。

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

.PARAMETER DefaultHost
  显式指定要烧进 APK 的「默认服务器地址」，形如 192.168.1.5:8787（也接受 http://192.168.1.5:8787
  或只写 192.168.1.5，缺端口时补 -ConfigPath 里的 webui.port）。给了这个就不再自动探测。

.PARAMETER NoDefaultHost
  不注入默认地址（BuildConfig.PIXIKO_DEFAULT_HOST 为空串），行为与加这个功能之前完全一致。
  与 -DefaultHost 同时给时以 -NoDefaultHost 为准（只警告，不报错）。

.PARAMETER ConfigPath
  读 webui.port 用的 config.json 路径。默认 F:\Bot\config.json；只读，绝不写。
  读不到就退回 8787（与 UrlHelper.DEFAULT_PORT 一致）。

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
    [string] $DefaultHost = '',
    [switch] $NoDefaultHost,
    [string] $ConfigPath = 'F:\Bot\config.json',
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

# ---------------------------------------------------------------- 0.5 探测「本机局域网地址」
# 目的：每次构建 APK 时把「本机局域网 IP:端口」烧进 BuildConfig.PIXIKO_DEFAULT_HOST，
# app 首次运行（还没有任何已保存服务器）时预填到「服务器设置」的地址框，装机后直接能用。
#
# 取值顺序：① -DefaultHost（显式指定，跳过探测）；
#           ② -ConfigPath 里的 webui.port（读不到用 8787）＋ 自己挑一张网卡的 IPv4；
#           ③ 探测不出来：不注入 ＋ 打印一句人话警告（绝不让构建失败）。
# -NoDefaultHost 完全不注入（BuildConfig 里是空串，行为与加这个功能之前一致）。
#
# 怎么挑网卡（这台机器有 VPN / 虚拟网卡，必须排除干净）：
#   硬排除这些网段：127.*、169.254.*（APIPA）、0.*、224.*、240.*、
#     198.18.*（代理软件 fake-IP 段）、100.64.*（CGNAT）、192.0.2.* / 198.51.100.* / 203.0.113.*；
#   硬排除名字或描述里带这些关键字的虚拟网卡：VirtualBox / VMware / Hyper-V / WSL / Docker /
#     TAP / TUN / Wintun / WireGuard / OpenVPN / Tailscale / ZeroTier / 蓝牙 / 虚拟 …
#   剩下的候选打分排序：能 HTTP 应答机器人（ip:port 的 /healthz）＞ 有 IPv4 默认路由 ＞ RFC1918 私网 ＞
#     接口度量（InterfaceMetric + RouteMetric）更小。
#   「能应答」这一项就是验证：不应答会明确警告（仍然注入，但告诉你可能连不上）。
#   注：刻意不用「TCP 能连上」当验证——这台机器的 TUN 代理会对任意远端 IP 假握手，
#       TCP 连得上证明不了什么；/healthz 是 app 自己 Probe 的第一步（免令牌），最贴近真机判据。

function Get-PixikoWebUiPort {
    param([string] $Path, [int] $Fallback = 8787)
    try {
        if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) { return $Fallback }
        # 必须显式按 UTF-8 读：config.json 是 UTF-8（无 BOM），Windows PowerShell 5.1 的
        # Get-Content 默认按 ANSI/GBK 解码，会把里面的中文搞乱甚至解析失败。
        $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
        # 只在 "webui" 那一节里找 port：qq / sd 等节里也有同名字段，不能全局正则一把抓。
        if ($text -match '(?s)"webui"\s*:\s*\{(.*?)\}') {
            if ($Matches[1] -match '"port"\s*:\s*(\d{1,5})') {
                $port = [int]$Matches[1]
                if ($port -ge 1 -and $port -le 65535) { return $port }
            }
        }
    } catch {
        # 读 config.json 失败不影响构建：退回默认端口。
    }
    return $Fallback
}

function Test-PixikoConsole {
    param([string] $TargetIp, [int] $Port, [int] $TimeoutMs = 1500)
    # 为什么不能只做一次 TCP 连接：这台机器装了 TUN 代理（TAG Wintun），它会对**任意**远端 IP
    # 直接把 TCP 握手做成功（实测连 10.1.2.3:9999 / 8.8.8.8:9999 都报「连上了」），
    # 所以「TCP 连得上」证明不了机器人在这张网卡上。这里改成真发一次 HTTP GET /healthz
    # （与 app 里 Probe 的第一步同一个免令牌接口），一次同时证明两件事：
    # 「网络这条路是通的」＋「对面真的是 Pixiko 控制台」。
    try {
        $req = [System.Net.HttpWebRequest]::Create("http://${TargetIp}:${Port}/healthz")
        # 本机代理（HTTP_PROXY / JAVA_TOOL_OPTIONS 那套）对 .NET 默认代理也生效，
        # 必须显式关掉，否则可能是代理替我们「回答」的。
        $req.Proxy = $null
        $req.Method = 'GET'
        $req.Timeout = $TimeoutMs
        $req.ReadWriteTimeout = $TimeoutMs
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $body = $reader.ReadToEnd()
        $reader.Close()
        $resp.Close()
        if ($code -eq 200 -and $body -match 'ok') { return $true }
        return $false
    } catch {
        return $false
    }
}

function Get-PixikoIpNumber {
    param([string] $Ip)
    $bytes = [System.Net.IPAddress]::Parse($Ip).GetAddressBytes()
    [array]::Reverse($bytes)
    return [int64][System.BitConverter]::ToUInt32($bytes, 0)
}

function Test-PixikoIpInCidr {
    param([string] $Ip, [string] $Cidr)
    try {
        $parts = $Cidr.Split('/')
        $bits  = [int]$parts[1]
        $mask  = ([int64]4294967295 -shl (32 - $bits)) -band [int64]4294967295
        return (((Get-PixikoIpNumber $Ip) -band $mask) -eq ((Get-PixikoIpNumber $parts[0]) -band $mask))
    } catch {
        return $false
    }
}

# 与 UrlHelper.DEFAULT_PORT 保持一致；-ConfigPath 只读不写。
$pixikoPort = Get-PixikoWebUiPort -Path $ConfigPath -Fallback 8787

$PixikoDefaultHost  = ''      # 本次要烧进 APK 的 host:port；空串＝不注入
$pixikoHostReason   = ''      # 给人看的一句话来由（构建日志与最终报告都打）
$pixikoHostVerified = $false  # 是否已 TCP 验证「机器人在这张网卡上可达」

# 排除网段：APIPA / 代理软件的 fake-IP / 各种保留段。私网、公网地址本身不在这里排除，
# 它们由下面的打分决定优先级。
$pixikoExcludedCidrs = @(
    '127.0.0.0/8', '169.254.0.0/16', '0.0.0.0/8', '224.0.0.0/4', '240.0.0.0/4',
    '198.18.0.0/15', '100.64.0.0/10', '192.0.2.0/24', '198.51.100.0/24', '203.0.113.0/24'
)
# 虚拟网卡 / VPN / 隧道关键字：对「网卡别名 + 网卡描述」小写后做包含匹配。
$pixikoVirtualKeywords = @(
    'virtualbox', 'vmware', 'hyper-v', 'vethernet', 'wsl', 'docker', 'loopback',
    'tap-windows', 'tap adapter', 'wintun', 'wireguard', 'openvpn', 'tailscale',
    'zerotier', 'radmin', 'softether', 'bluetooth', 'meta tunnel', 'clash',
    'mihomo', 'sing-box', 'vpn', 'proxy', '虚拟', '蓝牙', '隧道'
)

Write-Step "本机局域网地址（要烧进 APK 的测试默认服务器地址）"

if ($NoDefaultHost) {
    if (-not [string]::IsNullOrWhiteSpace($DefaultHost)) {
        Write-Warn "-DefaultHost 与 -NoDefaultHost 同时给了：以 -NoDefaultHost 为准（不注入）。"
    }
    $pixikoHostReason = '已用 -NoDefaultHost 关闭'
} elseif (-not [string]::IsNullOrWhiteSpace($DefaultHost)) {
    # ① 显式指定：只做最基本的整形，不探测（用户说哪个就是哪个）。
    $explicit = $DefaultHost.Trim()
    if ($explicit -match '^[a-zA-Z][a-zA-Z0-9+.-]*://') {
        $explicit = $explicit -replace '^[a-zA-Z][a-zA-Z0-9+.-]*://', ''
    }
    $explicit = $explicit.TrimEnd('/')
    if ($explicit -notmatch ':') { $explicit = "$explicit`:$pixikoPort" }
    $PixikoDefaultHost = $explicit
    $expParts  = $PixikoDefaultHost.Split(':')
    $expHost   = $expParts[0]
    $expPort   = $pixikoPort
    if ($expParts.Count -ge 2 -and $expParts[1] -match '^\d+$') { $expPort = [int]$expParts[1] }
    $expIsIp   = $false
    try { $null = [System.Net.IPAddress]::Parse($expHost); $expIsIp = $true } catch { $expIsIp = $false }
    if ($expIsIp) { $pixikoHostVerified = Test-PixikoConsole -TargetIp $expHost -Port $expPort }
    $pixikoHostReason = '已用 -DefaultHost 显式指定（未做网卡探测）'
} else {
    # ② 自动探测。枚举 IPv4 单播地址（优先 Get-NetIPAddress，不可用时退回 .NET）。
    $addrs = @()
    try {
        $addrs = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop)
    } catch {
        foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
            foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
                if ($ua.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
                $addrs += [pscustomobject]@{
                    IPAddress      = $ua.Address.ToString()
                    InterfaceIndex = -1
                    InterfaceAlias = $nic.Name
                    PrefixLength   = $ua.PrefixLength
                    SkipAsSource   = $false
                }
            }
        }
    }

    # 网卡描述：Get-NetIPAddress 只给别名，而 "VirtualBox Host-Only Ethernet Adapter" 这类
    # 关键字大多躺在描述里。
    $descByIndex = @{}
    try {
        foreach ($ad in @(Get-NetAdapter -ErrorAction Stop)) { $descByIndex[[int]$ad.ifIndex] = "$($ad.InterfaceDescription)" }
    } catch { }

    # 每张网卡的 IPv4 默认路由（0.0.0.0/0）：「有默认路由」是「这张网卡真的在联网」的最强信号。
    $defaultRouteMetric = @{}
    try {
        foreach ($rt in @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop)) {
            $idx = [int]$rt.InterfaceIndex
            $m   = [int]$rt.RouteMetric + [int]$rt.InterfaceMetric
            if (-not $defaultRouteMetric.ContainsKey($idx) -or $m -lt $defaultRouteMetric[$idx]) {
                $defaultRouteMetric[$idx] = $m
            }
        }
    } catch { }

    $candidates = @()
    foreach ($a in $addrs) {
        $ip = "$($a.IPAddress)"
        if ([string]::IsNullOrWhiteSpace($ip)) { continue }
        $alias = "$($a.InterfaceAlias)"
        $desc  = ''
        if ($descByIndex.ContainsKey([int]$a.InterfaceIndex)) { $desc = $descByIndex[[int]$a.InterfaceIndex] }

        $skip = $false
        $why  = ''
        if ($a.SkipAsSource) { $skip = $true; $why = 'SkipAsSource' }
        foreach ($cidr in $pixikoExcludedCidrs) {
            if (Test-PixikoIpInCidr -Ip $ip -Cidr $cidr) { $skip = $true; $why = "网段 $cidr"; break }
        }
        if (-not $skip) {
            $hay = ("$alias $desc").ToLowerInvariant()
            foreach ($kw in $pixikoVirtualKeywords) {
                if ($hay.Contains($kw)) { $skip = $true; $why = "虚拟/隧道网卡关键字「$kw」"; break }
            }
        }
        if ($skip) {
            Write-Host ("    跳过 {0,-17} {1,-16} （{2}）" -f $ip, $alias, $why) -ForegroundColor DarkGray
            continue
        }

        $hasRoute  = $defaultRouteMetric.ContainsKey([int]$a.InterfaceIndex)
        $reachable = Test-PixikoConsole -TargetIp $ip -Port $pixikoPort
        $private   = (Test-PixikoIpInCidr -Ip $ip -Cidr '10.0.0.0/8') -or
                     (Test-PixikoIpInCidr -Ip $ip -Cidr '172.16.0.0/12') -or
                     (Test-PixikoIpInCidr -Ip $ip -Cidr '192.168.0.0/16')
        $metric = 999999
        if ($hasRoute) { $metric = $defaultRouteMetric[[int]$a.InterfaceIndex] }

        $score = 0
        if ($reachable) { $score += 1000 }   # 机器人真的在这张网卡上应答
        if ($hasRoute)  { $score += 100 }    # 有默认路由＝真正在联网的那张
        if ($private)   { $score += 10 }     # 私网地址优先于公网地址

        $candidates += [pscustomobject]@{
            IP = $ip; Alias = $alias; Reachable = $reachable
            HasDefaultRoute = $hasRoute; Private = $private; Metric = $metric; Score = $score
        }
    }

    $best = $null
    if ($candidates.Count -gt 0) {
        $best = $candidates |
            Sort-Object -Property @{ Expression = 'Score'; Descending = $true },
                                  @{ Expression = 'Metric'; Descending = $false } |
            Select-Object -First 1
    }

    if ($null -eq $best) {
        Write-Warn "没能探测到可用的局域网 IPv4（VPN / 虚拟网卡与保留网段都被排除了）。本次不注入默认地址。"
        Write-Warn "要指定就传 -DefaultHost 192.168.x.y:$pixikoPort；想明确关掉就传 -NoDefaultHost。"
        $pixikoHostReason = '探测失败：没有可用的局域网 IPv4'
    } else {
        $PixikoDefaultHost  = "$($best.IP):$pixikoPort"
        $pixikoHostVerified = $best.Reachable
        $pixikoHostReason   = "自动探测：默认路由网卡「$($best.Alias)」，接口度量 $($best.Metric)"
    }
}

if ([string]::IsNullOrWhiteSpace($PixikoDefaultHost)) {
    Write-Warn "本次不注入默认服务器地址（$pixikoHostReason）——App 首次运行仍需手输地址，与旧包一致。"
} elseif ($pixikoHostVerified) {
    Write-Ok "默认服务器地址：$PixikoDefaultHost   （$pixikoHostReason；已用 HTTP GET /healthz 验证机器人在这张网卡上可达）"
} else {
    Write-Warn "默认服务器地址：$PixikoDefaultHost   （$pixikoHostReason；但 HTTP GET /healthz 连不上 $PixikoDefaultHost：机器人没在跑或防火墙挡着）——仍然烧进包里，可改用 -DefaultHost 指定别的。"
}
Write-Host "  （端口取自 $ConfigPath 的 webui.port＝$pixikoPort；-NoDefaultHost 可完全关闭本机制）" -ForegroundColor DarkGray

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
if (-not [string]::IsNullOrWhiteSpace($PixikoDefaultHost)) {
    # 属性名 pixikoDefaultHost 是脚本与 app/build.gradle 之间的约定：
    # build.gradle 把它变成 BuildConfig.PIXIKO_DEFAULT_HOST（buildConfigField）。
    $gradleArgs += "-PpixikoDefaultHost=$PixikoDefaultHost"
    Write-Host "  注入 Gradle 属性： -PpixikoDefaultHost=$PixikoDefaultHost" -ForegroundColor DarkGray
}

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
if ([string]::IsNullOrWhiteSpace($PixikoDefaultHost)) {
    Write-Host "  默认地址 : （本次未注入）$pixikoHostReason" -ForegroundColor Yellow
} else {
    Write-Host "  默认地址 : $PixikoDefaultHost" -ForegroundColor Green
    Write-Host "             ↑ 本次烧进 APK 的测试默认服务器地址（BuildConfig.PIXIKO_DEFAULT_HOST）"
    Write-Host "               $pixikoHostReason" -ForegroundColor DarkGray
}
# 回读生成的 BuildConfig.java：让「这次到底烧进去了什么」有个不依赖 gradle 输出的落点。
$bcFile = Join-Path $ProjectDir "app\build\generated\source\buildConfig\$BuildType\cn\szu\bot\app\BuildConfig.java"
if (Test-Path -LiteralPath $bcFile) {
    $bcHit = Select-String -LiteralPath $bcFile -Pattern 'PIXIKO_DEFAULT_HOST' -SimpleMatch | Select-Object -First 1
    if ($bcHit) { Write-Host "  BuildConfig: $($bcHit.Line.Trim())" -ForegroundColor DarkGray }
    Write-Host "               （$bcFile）" -ForegroundColor DarkGray
} else {
    Write-Warn "没找到生成的 BuildConfig.java（$bcFile），无法回读烧进去的值"
}
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
