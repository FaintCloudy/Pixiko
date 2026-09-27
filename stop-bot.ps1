param()
$ErrorActionPreference = 'Stop'
# Match this project's exact JAR argument, never unrelated Java applications.
# run.bat 有两种起法：-jar build\pixiko.jar，或 -cp "...build\pixiko.jar;lib\..." cn.szu.bot.Main（Spring Boot 版）。
# 也匹配 last-good 兜底 jar（build\pixiko-last-good.jar）：开发/测试期间线上跑的是它，同样要能正常停。
$botJarPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build\pixiko.jar'))
$botJarQuoted = [regex]::Escape($botJarPath)
$botJarArgument = '(?i)(?:^|\s)(?:-jar\s+(?:"' + $botJarQuoted + '"|' + $botJarQuoted + '(?=\s|$))|(?:-cp|-classpath)\s+"?[^"]*' + $botJarQuoted + ')'
$botAnyJarArgument = '(?i)(?:^|\s)(?:-jar\s+(?:"?[^"\s]*pixiko(?:-[A-Za-z0-9._-]+)?\.jar"?)|(?:-cp|-classpath)\s+"?[^"]*pixiko(?:-[A-Za-z0-9._-]+)?\.jar)'
$botProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match $botAnyJarArgument })
if ($botProcesses.Count -eq 0) { Write-Output 'This Bot instance is not running.'; return }
# A force-killed JVM never runs its shutdown hook, so the offline notice is sent here first.
$notifier = Join-Path $PSScriptRoot 'work\pixiko-notice.mjs'
if ((Test-Path -LiteralPath $notifier) -and (Get-Command node -ErrorAction SilentlyContinue)) {
    & node $notifier $PSScriptRoot 2>&1 | ForEach-Object { Write-Output $_ }
}
foreach ($botProcess in $botProcesses) {
    $currentBot = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $botProcess.ProcessId)
    if ($currentBot -and $currentBot.CreationDate -eq $botProcess.CreationDate -and $currentBot.CommandLine -match $botAnyJarArgument) {
        Stop-Process -Id $botProcess.ProcessId -ErrorAction SilentlyContinue
    }
}
Write-Output 'Stopped only the Java process(es) using this Bot JAR.'
