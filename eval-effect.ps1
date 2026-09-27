# Runs the effect evaluation of the natural-language task chain: plans are really executed against
# the running WebUI (txt2img stubbed), then the prompt that generation would use is checked for the
# requested changes, the style basis, standard-dictionary conformance, order and redundancy.
param([int]$Count = 200, [double]$MinHitRate = 0.95, [int]$Workers = 6, [long]$Seed = 20260919, [switch]$NoLlmJudge)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Set-Location -LiteralPath $PSScriptRoot
$jar = Join-Path $PSScriptRoot 'lib\gson-2.13.1.jar'
$classes = Join-Path $PSScriptRoot 'build\classes'
$tests = Join-Path $PSScriptRoot 'build\test-classes'
& (Join-Path $PSScriptRoot 'build.ps1') -CompileTestsOnly -OutputJar (Join-Path $PSScriptRoot 'build\pixiko.effect.jar')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$llm = if ($NoLlmJudge) { 'false' } else { 'true' }
& java -ea '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Dbot.home=$PSScriptRoot" "-Dbot.test.work=$PSScriptRoot\work" "-Dhitrate.min=$MinHitRate" "-Dtasks.count=$Count" "-Dtasks.seed=$Seed" "-Dworkers=$Workers" "-Dllm.judge=$llm" -cp "$jar;$classes;$tests" 'cn.szu.bot.ChainEffectEval'
exit $LASTEXITCODE
