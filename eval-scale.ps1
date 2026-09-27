# Runs the 500-task scale evaluation of the natural-language task chain (strict "complete hit").
# The harness first executes .style list / .lora list / .function list through the Bot, so #numbers
# in generated tasks refer to the real numbered lists. Exit code 1 means the hit rate is below the gate.
param([int]$Count = 500, [double]$MinHitRate = 0.97, [int]$Workers = 6, [long]$Seed = 20260919)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Set-Location -LiteralPath $PSScriptRoot
$jar = Join-Path $PSScriptRoot 'lib\gson-2.13.1.jar'
$classes = Join-Path $PSScriptRoot 'build\classes'
$tests = Join-Path $PSScriptRoot 'build\test-classes'
& (Join-Path $PSScriptRoot 'build.ps1') -CompileTestsOnly -OutputJar (Join-Path $PSScriptRoot 'build\pixiko.scale.jar')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Dbot.home=$PSScriptRoot" "-Dbot.test.work=$PSScriptRoot\work" "-Dhitrate.min=$MinHitRate" "-Dtasks.count=$Count" "-Dtasks.seed=$Seed" "-Dworkers=$Workers" -cp "$jar;$classes;$tests" 'cn.szu.bot.ChainScaleEval'
exit $LASTEXITCODE
