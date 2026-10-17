param([switch]$Test, [switch]$CompileTestsOnly, [string]$OutputJar, [string]$Only)
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
# Test runs (and -CompileTestsOnly) deliberately skip jar packaging: they run off build\classes,
# and repackaging build\pixiko.jar would fail while a production instance has that jar open.
if (-not ($Test -or $CompileTestsOnly)) {
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
}
if ($Test -or $CompileTestsOnly) {
    $taskTestClasses = Join-Path $PSScriptRoot 'build\test-classes'
    New-Item -ItemType Directory -Path $taskTestClasses -Force | Out-Null
    $taskTestSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
    $taskTestArgs = Join-Path $PSScriptRoot 'build\test-sources.txt'
    [IO.File]::WriteAllLines($taskTestArgs, $taskTestSources, $taskUtf8)
    # build\classes comes first: the tests must compile against (and run) what was just compiled,
    # never against a stale build\pixiko.jar.
    & javac --release 17 -encoding UTF-8 -cp "$taskClasses;$taskJar;$taskSpring\*" -d $taskTestClasses "@$taskTestArgs"
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    if ($CompileTestsOnly) { Write-Output "Test classes OK: $taskTestClasses"; exit 0 }

    # ---------------------------------------------------------------------------------------
    # Isolated test run (docs\TEST-ISOLATION.md, user requirement 14/10/12).
    #
    # One GUID per run: work\tests\<run-id>\<TestClass>\ is that test PROCESS's own root.
    # Every test JVM gets:
    #   -Dbot.test.mode=true                 test mode (production Main refuses to start with it)
    #   -Dbot.test.home=<class root>         isolated root (config.json/data/logs/maps/generated/lora/bot.lock)
    #   -Dbot.test.work=<class root>\work    legacy property the existing 89 tests already read
    #   -Dbot.test.run.id=<uuid>--<Class>    per-process UUID
    #   -Dbot.test.production.root=F:\Bot    the real root, used only to assert "we are not it"
    #   -Dbot.test.web.port=0                dynamic ports: bind 0, then read the real one
    #   -Dbot.test.onebot.port=0
    #   -Dbot.test.sd.port=0
    # The whole run-id directory is removed in the finally block (success, failure or Ctrl+C).
    # ---------------------------------------------------------------------------------------
    $taskRunsRoot = Join-Path $PSScriptRoot 'work\tests'
    New-Item -ItemType Directory -Path $taskRunsRoot -Force | Out-Null
    $taskRunId = [guid]::NewGuid().ToString()
    $taskRunRoot = Join-Path $taskRunsRoot $taskRunId
    New-Item -ItemType Directory -Path $taskRunRoot -Force | Out-Null
    Write-Output "Test isolation root: $taskRunRoot"

    $taskFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java\cn\szu\bot') -Filter '*Test.java' | Sort-Object Name)
    if ($Only) {
        $taskFiles = @($taskFiles | Where-Object { $_.BaseName -like $Only -or $_.Name -like $Only })
        if ($taskFiles.Count -eq 0) { throw "No test matches -Only '$Only'." }
    }
    $taskClasspath = "$taskClasses;$taskTestClasses;$taskJar;$taskSpring\*"

    function New-TestProcessRoot([string]$classRoot) {
        foreach ($dir in @($classRoot, (Join-Path $classRoot 'data'), (Join-Path $classRoot 'logs'),
                           (Join-Path $classRoot 'maps'), (Join-Path $classRoot 'generated'),
                           (Join-Path $classRoot 'lora'), (Join-Path $classRoot 'work'),
                           (Join-Path $classRoot 'data\generated'), (Join-Path $classRoot 'data\webui'))) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
        [IO.File]::WriteAllText((Join-Path $classRoot '.pixiko-test-root'),
            "run-id=$taskRunId`npid=$PID`n", $taskUtf8)
        [IO.File]::WriteAllText((Join-Path $classRoot 'bot.lock'), "pid=$PID`nrun-id=$taskRunId`n", $taskUtf8)
        [IO.File]::WriteAllText((Join-Path $classRoot 'data\bot.lock'), "pid=$PID`nrun-id=$taskRunId`n", $taskUtf8)
        $lora = (Join-Path $classRoot 'lora').Replace('\', '/')
        $config = [ordered]@{
            bot_name        = 'test-bird'
            owner_user_id   = '10001'
            startup_group_id = ''
            startup_notice  = ''
            qq              = [ordered]@{ ws_url = 'ws://127.0.0.1:0'; access_token = 'offline-test-token'; bot_name = 'test-bird' }
            webui           = [ordered]@{ enabled = $false; host = '127.0.0.1'; port = 0; access_token = 'offline-test-token' }
            sd              = [ordered]@{ base_url = 'http://127.0.0.1:0'; root = $lora; timeout_seconds = 5 }
            civitai         = [ordered]@{ lora_dir = $lora; base_url = 'https://civitai.com'; timeout_seconds = 5 }
            progen          = [ordered]@{ api_base = 'http://127.0.0.1:0'; timeout_seconds = 5 }
            chat_api        = [ordered]@{ api_base = 'http://127.0.0.1:0'; timeout_seconds = 5 }
        }
        [IO.File]::WriteAllText((Join-Path $classRoot 'config.json'), ($config | ConvertTo-Json -Depth 8), $taskUtf8)
    }

    try {
        $taskFailed = $null
        foreach ($file in $taskFiles) {
            $taskHome = Join-Path $taskRunRoot $file.BaseName
            New-TestProcessRoot $taskHome
            $taskHomeWork = Join-Path $taskHome 'work'
            Write-Output "== $($file.BaseName) (home: work\tests\$taskRunId\$($file.BaseName)) =="
            $taskJavaArgs = @(
                '-ea',
                '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
                '-Dbot.test.mode=true',
                "-Dbot.test.home=$taskHome",
                "-Dbot.test.work=$taskHomeWork",
                "-Dbot.test.run.id=$taskRunId--$($file.BaseName)",
                "-Dbot.test.production.root=$PSScriptRoot",
                '-Dbot.test.web.port=0',
                '-Dbot.test.onebot.port=0',
                '-Dbot.test.sd.port=0',
                '-cp', $taskClasspath,
                ("cn.szu.bot." + $file.BaseName)
            )
            & java @taskJavaArgs
            if ($LASTEXITCODE -ne 0) {
                $taskFailed = "$($file.BaseName) (exit $LASTEXITCODE)"
                break
            }
        }
        if ($taskFailed) { throw "Test failed: $taskFailed (isolation run-id $taskRunId)" }
        Write-Output "All tests passed in isolation mode (run-id $taskRunId, $($taskFiles.Count) classes)."
    }
    finally {
        # Requirement 12: clean this run's directory on success, failure and interruption.
        # Nothing outside work\tests\<run-id> is ever removed, and no process is ever killed.
        if (Test-Path -LiteralPath $taskRunRoot) {
            Remove-Item -LiteralPath $taskRunRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $taskRunRoot) {
            Start-Sleep -Milliseconds 500
            Remove-Item -LiteralPath $taskRunRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $taskRunRoot) {
            Write-Warning "Could not fully remove the test run directory: $taskRunRoot"
        } else {
            Write-Output "Test isolation root removed: $taskRunRoot"
        }
    }
}
