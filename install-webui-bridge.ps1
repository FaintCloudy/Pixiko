param(
    [string]$WebUiRoot = 'F:\sd\sd-webui'
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1')
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1')
$source = Join-Path $PSScriptRoot 'webui-extension\pixiko-bridge'
$resolvedRoot = (Resolve-Path -LiteralPath $WebUiRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot 'modules\script_callbacks.py') -PathType Leaf)) {
    throw "Not a supported Stable Diffusion WebUI root: $resolvedRoot"
}
$target = Join-Path $resolvedRoot 'extensions\pixiko-bridge'
# Only copy extension source files. A re-install preserves data/prompts.json.
foreach ($folder in @('scripts', 'javascript')) {
    $destination = Join-Path $target $folder
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $source $folder) -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $destination $_.Name) -Force
    }
}
Copy-Item -LiteralPath (Join-Path $source 'README.md') -Destination (Join-Path $target 'README.md') -Force
Write-Host "Installed: $target"
# The pre-rename extension serves the old /onebot-bridge route and is incompatible: drop it on install.
$stale = Join-Path $resolvedRoot 'extensions\onebot-prompt-bridge'
if (Test-Path -LiteralPath $stale) {
    Remove-Item -LiteralPath $stale -Recurse -Force
    Write-Host "Removed stale extension: $stale"
}
Write-Host 'Save current prompts, sampler, selected styles and dimensions; wait for generation to finish.'
Write-Host 'In WebUI Settings, click Reload UI, then refresh its browser page.'
Write-Host 'Alternatively restart WebUI using the launcher. This script does not restart it.'
Write-Host 'Keep one WebUI browser page open to synchronize live txt2img prompts.'
