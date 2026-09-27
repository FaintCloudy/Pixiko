param([switch]$Test, [switch]$CompileTestsOnly, [string]$OutputJar)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1')
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1')
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Set-Location -LiteralPath $PSScriptRoot
$taskJar = Join-Path $PSScriptRoot 'lib\gson-2.13.1.jar'
$taskHash = '94855942d4992f112946d3de1c334e709237b8126d8130bf07807c018a4a2120'
if (-not (Test-Path -LiteralPath $taskJar)) {
    New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'lib') -Force | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.1/gson-2.13.1.jar' -OutFile $taskJar
}
if ((Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskHash) { throw 'Gson SHA256 mismatch.' }
# Spring Boot (web layer) ships in lib\spring; it is merged into the runnable jar below.
# NOTE: keep this script ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.
$taskSpring = Join-Path $PSScriptRoot 'lib\spring'
if (-not (Test-Path -LiteralPath $taskSpring)) { throw 'Missing lib\spring (Spring Boot jars for the web layer).' }
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'Install JDK 17 or newer and add its bin directory to PATH.' }
$taskClasses = Join-Path $PSScriptRoot 'build\classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$taskUtf8 = New-Object System.Text.UTF8Encoding($false)
$taskArgs = Join-Path $PSScriptRoot 'build\sources.txt'
[IO.File]::WriteAllLines($taskArgs, $taskSources, $taskUtf8)
& javac --release 17 -encoding UTF-8 -cp "$taskJar;$taskSpring\*" -d $taskClasses "@$taskArgs"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$taskManifest = Join-Path $PSScriptRoot 'build\MANIFEST.MF'
[IO.File]::WriteAllText($taskManifest, "Manifest-Version: 1.0`nMain-Class: cn.szu.bot.Main`nClass-Path: ../lib/gson-2.13.1.jar`n`n", $taskUtf8)
$taskJarCommand = Get-Command jar -ErrorAction SilentlyContinue
if ($taskJarCommand) { $taskJarTool = $taskJarCommand.Source } else {
    # Oracle's javapath shim exposes javac but may omit jar. Resolve the actual JDK.
    $taskProbe = New-Object System.Diagnostics.ProcessStartInfo
    $taskProbe.FileName = (Get-Command java).Source
    $taskProbe.Arguments = '-XshowSettings:properties -version'
    $taskProbe.UseShellExecute = $false
    $taskProbe.CreateNoWindow = $true
    $taskProbe.RedirectStandardError = $true
    $taskProcess = [Diagnostics.Process]::Start($taskProbe)
    $taskProperties = $taskProcess.StandardError.ReadToEnd()
    $taskProcess.WaitForExit()
    $taskMatch = [regex]::Match($taskProperties, '(?m)^\s*java\.home\s*=\s*(.+)$')
    if (-not $taskMatch.Success) { throw 'Could not locate jar.exe in the JDK.' }
    $taskJarTool = Join-Path $taskMatch.Groups[1].Value.Trim() 'bin\jar.exe'
}
$taskOutputJar = if ($OutputJar) { [IO.Path]::GetFullPath($OutputJar) } else { Join-Path $PSScriptRoot 'build\pixiko.jar' }
# Package only this project's classes: Spring Boot lives in lib\spring and is put on the
# runtime classpath (see run.bat). Merging the Spring jars into one archive by name would
# clobber same-named files (META-INF/spring.factories, AutoConfiguration.imports) and Boot
# would silently start as a non-web application.
& $taskJarTool --create --file $taskOutputJar --manifest $taskManifest -C $taskClasses .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
Write-Output "Build OK: $taskOutputJar"
if ($Test -or $CompileTestsOnly) {
    $taskTests = Join-Path $PSScriptRoot 'build\test-classes'
    New-Item -ItemType Directory -Path $taskTests -Force | Out-Null
    $taskTestSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
    $taskTestArgs = Join-Path $PSScriptRoot 'build\test-sources.txt'
    [IO.File]::WriteAllLines($taskTestArgs, $taskTestSources, $taskUtf8)
    & javac --release 17 -encoding UTF-8 -cp "$taskJar;$taskClasses;$taskSpring\*" -d $taskTests "@$taskTestArgs"
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    if ($CompileTestsOnly) { Write-Output "Test classes OK: $taskTests"; exit 0 }
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java\cn\szu\bot') -Filter '*Test.java' | ForEach-Object {
        & java -ea '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Dbot.test.work=$PSScriptRoot\work" -cp "$taskJar;$taskClasses;$taskTests;$taskSpring\*" ("cn.szu.bot." + $_.BaseName)
        if ($LASTEXITCODE -ne 0) { throw "Test failed: $($_.BaseName)" }
    }
}
