param([double]$MinHitRate = 0.90)
# 前置拆解层命中率评测：40 条复杂场景，默认门槛 90%。
# 直接跑编译好的测试类，不改动 build\pixiko.jar（运行中的实例不受影响）。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Set-Location -LiteralPath $PSScriptRoot
$jar = Join-Path $PSScriptRoot 'lib\gson-2.13.1.jar'
$classes = Join-Path $PSScriptRoot 'build\classes'
$tests = Join-Path $PSScriptRoot 'build\test-classes'
if (-not (Test-Path -LiteralPath $classes) -or -not (Test-Path -LiteralPath $tests)) {
    throw '先运行 build.ps1 -CompileTestsOnly 编译主代码与测试类。'
}
& java -ea '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' "-Dbot.home=$PSScriptRoot\." `
    -cp "$jar;$classes;$tests" cn.szu.bot.SceneDecomposeEval $MinHitRate
if ($LASTEXITCODE -ne 0) { throw "拆解命中率评测未达标（门槛 $MinHitRate）。" }
