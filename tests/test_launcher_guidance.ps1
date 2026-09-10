[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools/common.ps1')
$passed = 0
function Assert-Guide([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL $Message" }
    $script:passed++
    Write-Output "PASS $Message"
}
function Adapter([string]$Address, [string]$Type, [string]$Name, $Hardware = $true, [bool]$Up = $true) {
    [pscustomobject]@{ Address = $Address; InterfaceType = $Type; Name = $Name; Description = $Name; Hardware = $Hardware; Up = $Up }
}

# Documentation-only addresses replay P01's Wi-Fi / Ethernet / vEthernet topology.
$interfaces = @(
    (Adapter '192.0.2.1' Ethernet 'vEthernet (Default Switch)' $false),
    (Adapter '192.0.2.20' Ethernet 'Ethernet'),
    (Adapter '192.0.2.30' Wireless80211 'WLAN'),
    (Adapter '127.0.0.2' Loopback 'loopback'),
    (Adapter '169.254.1.1' Ethernet 'not configured'),
    (Adapter '192.0.2.40' Wireless80211 'disconnected' $true $false),
    (Adapter '::1' Loopback 'IPv6'),
    (Adapter '0.0.0.0' Ethernet 'unspecified'),
    (Adapter '224.0.0.1' Ethernet 'multicast')
)
$candidates = @(Get-AnEbLanCandidates -Interfaces $interfaces)
Assert-Guide ($candidates.Count -eq 3) 'only up usable unicast IPv4 candidates survive'
Assert-Guide ((@($candidates | ForEach-Object { $_.Address }) -join ',') -ceq '192.0.2.30,192.0.2.20,192.0.2.1') 'Wi-Fi then physical Ethernet then virtual, not IP lexical order'
Assert-Guide ($candidates[2].Kind -ceq 'advanced') 'virtual adapter never promoted as physical'
$unknownHardware = @(Get-AnEbLanCandidates -Interfaces @((Adapter '192.0.2.5' Ethernet 'vEthernet' $null)))
Assert-Guide ($unknownHardware[0].Kind -ceq 'advanced') 'virtual-name fallback works without privileged adapter metadata'
$duplicate = @(Get-AnEbLanCandidates -Interfaces @($interfaces[0], $interfaces[2], $interfaces[2]))
Assert-Guide ($duplicate.Count -eq 2) 'duplicate addresses do not create duplicate choices'

$guide = @(Get-AnEbReadyGuide -Candidates $candidates -Port 18088 -ResultsDirectory 'C:\test\results')
$text = $guide -join "`n"
Assert-Guide ($text.Contains('已就绪 READY') -and $text.Contains('ANEB Prototype 0.1 - READY')) 'Chinese readiness and existing diagnostic marker both present'
Assert-Guide ((@($guide | Where-Object { $_ -like '推荐先试*' })).Count -eq 1 -and $text.Contains('http://192.0.2.30:18088')) 'exactly one first-choice URL'
Assert-Guide ($text.Contains('高级备选') -and $text.IndexOf('高级备选') -lt $text.IndexOf('http://192.0.2.1:18088')) 'virtual URL separated under advanced heading'
Assert-Guide ($text.Contains('Compatible') -and $text.Contains('不代表手机已经连通')) 'local Ready never claims phone connectivity'
Assert-Guide ($text.Contains('检查连接')) 'launcher names the same connection button as the Chinese Android screen'
Assert-Guide ($text.Contains('Quick') -and $text.Contains('Acceptance') -and $text.Contains('不是运营商评级')) 'next action and synthetic test limits visible'
Assert-Guide ($text.Contains('Results: C:\test\results') -and $text.Contains('Q')) 'result path and owned-server stop instruction retained'
$virtualOnly = @(Get-AnEbReadyGuide -Candidates @($candidates[2]) -Port 18088 -ResultsDirectory 'C:\test\results')
Assert-Guide ((@($virtualOnly | Where-Object { $_ -like '推荐先试*' })).Count -eq 0 -and ($virtualOnly -join "`n").Contains('未找到可优先推荐')) 'virtual-only state is not presented as a recommended route'
$ethernetOnly = @(Get-AnEbReadyGuide -Candidates @($candidates[1]) -Port 18088 -ResultsDirectory 'C:\test\results')
Assert-Guide (($ethernetOnly -join "`n").Contains('推荐先试（有线）')) 'wired-only physical route remains usable'
Write-Output "PASS LAUNCHER_GUIDANCE_TESTS count=$passed"
