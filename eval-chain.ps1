# Runs the real-API natural-language task-chain hit-rate evaluation.
# Compiles main + test classes, then runs only cn.szu.bot.ChainHitRateEval (the offline *Test classes are untouched).
# Exit code 1 means the hit rate is below the threshold: adjust the planner base rules and run again.
param([double]$MinHitRate = 0.90)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Set-Location -LiteralPath $PSScriptRoot
$jar = Join-Path $PSScriptRoot 'lib\gson-2.13.1.jar'
$classes = Join-Path $PSScriptRoot 'build\classes'
$tests = Join-Path $PSScriptRoot 'build\test-classes'
& (Join-Path $PSScriptRoot 'build.ps1') -CompileTestsOnly -OutputJar (Join-Path $PSScriptRoot 'build\pixiko.check.jar')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Dbot.home=$PSScriptRoot" "-Dbot.test.work=$PSScriptRoot\work" "-Dhitrate.min=$MinHitRate" -cp "$jar;$classes;$tests" 'cn.szu.bot.ChainHitRateEval'
exit $LASTEXITCODE
