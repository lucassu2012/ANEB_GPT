[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$probe = Join-Path $repo 'app\probe'
$androidNs = 'http://schemas.android.com/apk/res/android'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Read-Manifest([string]$Path) {
    Assert-True (Test-Path -LiteralPath $Path) "Manifest missing: $Path"
    $xml = [xml](Get-Content -LiteralPath $Path -Raw -Encoding UTF8)
    $ns = [System.Xml.XmlNamespaceManager]::new($xml.NameTable)
    $ns.AddNamespace('android', $androidNs)
    return @{ Xml = $xml; Namespace = $ns }
}

$debugSource = Read-Manifest (Join-Path $probe 'src\debug\AndroidManifest.xml')
$debugActivity = $debugSource.Xml.SelectSingleNode(
    "/manifest/application/activity[@android:name='.debug.ApiProbeDebugActivity']",
    $debugSource.Namespace
)
Assert-True ($null -ne $debugActivity) 'Debug API diagnostic Activity is not declared.'
Assert-True ($debugActivity.GetAttribute('exported', $androidNs) -eq 'true') 'Debug API diagnostic must be exported for ADB shell.'
Assert-True ($debugActivity.GetAttribute('permission', $androidNs) -eq 'android.permission.DUMP') 'Debug API diagnostic is missing the shell-only DUMP permission.'
Assert-True ($debugActivity.SelectNodes('intent-filter').Count -eq 0) 'Debug API diagnostic must not expose an intent filter.'

$mainManifestPath = Join-Path $probe 'src\main\AndroidManifest.xml'
$mainManifestText = Get-Content -LiteralPath $mainManifestPath -Raw -Encoding UTF8
Assert-True (-not $mainManifestText.Contains('ApiProbeDebugActivity')) 'Main manifest exposes the Debug API diagnostic.'
Assert-True (-not $mainManifestText.Contains('apiprobe')) 'Main manifest contains an API-probe action or deep link.'

$mainActivityPath = Join-Path $probe 'src\main\java\com\aneb\probe\ui\MainActivity.kt'
$mainActivityText = Get-Content -LiteralPath $mainActivityPath -Raw -Encoding UTF8
Assert-True (-not $mainActivityText.Contains('apiprobe_')) 'Product MainActivity still accepts paid API-probe extras.'
Assert-True (-not $mainActivityText.Contains('Screen.ApiProbe')) 'Product navigation still contains the paid API Probe route.'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $probe 'src\main\java\com\aneb\probe\ui\ApiProbeScreen.kt'))) 'Paid API Probe UI remains in the product source set.'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $probe 'src\main\java\com\aneb\probe\apiprobe\ApiKeyStore.kt'))) 'Product source still persists third-party API keys.'

$merged = Get-ChildItem -Path (Join-Path $probe 'build\intermediates') -Recurse -Filter AndroidManifest.xml -ErrorAction SilentlyContinue
$releaseMerged = @($merged | Where-Object { $_.FullName -match '[\\/]release[\\/]' })
$debugMerged = @($merged | Where-Object { $_.FullName -match '[\\/]debug[\\/]' })
Assert-True ($releaseMerged.Count -gt 0) 'No merged Release manifest was produced for boundary verification.'
Assert-True ($debugMerged.Count -gt 0) 'No merged Debug manifest was produced for boundary verification.'

foreach ($manifest in $releaseMerged) {
    $text = Get-Content -LiteralPath $manifest.FullName -Raw -Encoding UTF8
    Assert-True (-not $text.Contains('ApiProbeDebugActivity')) "Release merged manifest exposes Debug API diagnostic: $($manifest.FullName)"
}

$protectedDebugComponentFound = $false
foreach ($manifest in $debugMerged) {
    $text = Get-Content -LiteralPath $manifest.FullName -Raw -Encoding UTF8
    if ($text.Contains('ApiProbeDebugActivity') -and $text.Contains('android.permission.DUMP')) {
        $protectedDebugComponentFound = $true
    }
}
Assert-True $protectedDebugComponentFound 'Merged Debug manifest does not contain the permission-protected API diagnostic.'

Write-Host 'ANEB release boundary: PASS'
