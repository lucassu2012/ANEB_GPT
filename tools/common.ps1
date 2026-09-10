Set-StrictMode -Version 2.0

# Presentation only: no route, firewall, adapter or network-profile changes.
function Get-AnEbLanCandidates {
    param([AllowEmptyCollection()][object[]]$Interfaces)
    if (-not $PSBoundParameters.ContainsKey('Interfaces')) {
        $hardware = @{}
        # Best effort read-only metadata. Standard-user and older Windows fallback
        # still work; an unclassified interface is never advertised as physical.
        try {
            foreach ($adapter in @(Get-NetAdapter -IncludeHidden -ErrorAction Stop)) {
                $hardware[([string]$adapter.InterfaceGuid).Trim('{}')] = [bool]$adapter.HardwareInterface
            }
        }
        catch { }
        $Interfaces = @(foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
            $id = ([string]$nic.Id).Trim('{}')
            foreach ($unicast in $nic.GetIPProperties().UnicastAddresses) {
                [pscustomobject]@{
                    Address = $unicast.Address.ToString()
                    InterfaceType = [string]$nic.NetworkInterfaceType
                    Name = $nic.Name
                    Description = $nic.Description
                    Hardware = if ($hardware.ContainsKey($id)) { $hardware[$id] } else { $null }
                    Up = $true
                }
            }
        })
    }
    $ranked = @(foreach ($nic in $Interfaces) {
        if (-not $nic.Up) { continue }
        $ip = $null
        if (-not [System.Net.IPAddress]::TryParse([string]$nic.Address, [ref]$ip) -or
            $ip.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or
            [System.Net.IPAddress]::IsLoopback($ip)) { continue }
        $bytes = $ip.GetAddressBytes()
        if ($bytes[0] -eq 0 -or $bytes[0] -ge 224 -or ($bytes[0] -eq 169 -and $bytes[1] -eq 254)) { continue }
        $virtual = ($nic.Hardware -eq $false -and $null -ne $nic.Hardware) -or
            (([string]$nic.Name + ' ' + [string]$nic.Description) -match '(?i)virtual|vEthernet|Hyper-V|VMware|VirtualBox|WSL|VPN|TAP|TUN|WireGuard|Tailscale|ZeroTier')
        $kind = 'advanced'
        $rank = 2
        if (-not $virtual -and $nic.InterfaceType -eq 'Wireless80211') {
            $kind = 'wifi'; $rank = 0
        }
        elseif (-not $virtual -and $nic.InterfaceType -eq 'Ethernet' -and $nic.Hardware -eq $true) {
            $kind = 'ethernet'; $rank = 1
        }
        [pscustomobject]@{ Address = $ip.ToString(); Kind = $kind; Rank = $rank }
    })
    $seen = @{}
    foreach ($candidate in @($ranked | Sort-Object Rank, Address)) {
        if (-not $seen.ContainsKey($candidate.Address)) {
            $seen[$candidate.Address] = $true
            [pscustomobject]@{ Address = $candidate.Address; Kind = $candidate.Kind }
        }
    }
}

function Get-AnEbReadyGuide {
    param(
        [Parameter(Mandatory = $true)][object[]]$Candidates,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$ResultsDirectory
    )
    ''
    '============================================================'
    '                 ANEB 已就绪 READY'
    'ANEB Prototype 0.1 - READY'
    '电脑节点检查通过；不代表手机已经连通。'
    '============================================================'
    $preferred = @($Candidates | Where-Object { $_.Kind -in @('wifi', 'ethernet') })
    if ($preferred.Count -gt 0) {
        $label = if ($preferred[0].Kind -eq 'wifi') { 'Wi-Fi' } else { '有线' }
        '推荐先试（' + $label + '）：http://' + $preferred[0].Address + ':' + $Port
        foreach ($candidate in @($preferred | Select-Object -Skip 1)) {
            '其他局域网备选：http://' + $candidate.Address + ':' + $Port
        }
    }
    else {
        '未找到可优先推荐的 Wi-Fi / 有线地址。请先检查电脑的局域网连接。'
    }
    $advanced = @($Candidates | Where-Object { $_.Kind -notin @('wifi', 'ethernet') })
    if ($advanced.Count -gt 0) {
        ''
        '高级备选（虚拟 / 隧道 / 未识别网卡，手机可达性未验证；不建议首先选择）：'
        foreach ($candidate in $advanced) { '  http://' + $candidate.Address + ':' + $Port }
    }
    ''
    '1. 手机与电脑连接同一个可互访的私人局域网。'
    '2. 手机打开 ANEB Prototype，输入上方地址，点“检查节点 / Test connection”。'
    '   看到“兼容 / Compatible”才开始；失败时核对地址并检查局域网隔离。'
    '   不要关闭防火墙；不要把公共网络改成受信任网络。'
    '3. 先运行 Quick（3 次），需要重复验证再运行 Acceptance（9 次）。'
    '在测什么：手机到此节点的合成流式响应，比较基线、变慢与不稳定三种条件。'
    '边界：不是运营商评级、SLA 或真实 AI 模型性能；RPI 仅用于同一次测试内比较。'
    '结果目录 / Results: ' + $ResultsDirectory
    'Results: ' + $ResultsDirectory
    '停止：输入 Q 后按 Enter；只关闭本启动器的节点，保留已保存结果。'
    '============================================================'
}

function Get-AnEbFullPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.Path]::GetFullPath($Path)
}

function Test-AnEbReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Path)
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    return (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
}

function Assert-AnEbRegularFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "FILE_NOT_REGULAR: $Path"
    }
    if (Test-AnEbReparsePoint -Path $Path) {
        throw "FILE_REPARSE: $Path"
    }
    return Get-Item -LiteralPath $Path -Force -ErrorAction Stop
}

function Assert-AnEbDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "DIRECTORY_MISSING: $Path"
    }
    if (Test-AnEbReparsePoint -Path $Path) {
        throw "DIRECTORY_REPARSE: $Path"
    }
    return Get-Item -LiteralPath $Path -Force -ErrorAction Stop
}

function Assert-AnEbPathUnderRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $rootFull = (Get-AnEbFullPath -Path $Root).TrimEnd('\')
    $pathFull = Get-AnEbFullPath -Path $Path
    if ([string]::Equals($rootFull, $pathFull.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
        return $pathFull
    }
    $prefix = $rootFull + '\'
    if (-not $pathFull.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "PATH_ESCAPE: $Path"
    }
    return $pathFull
}

function Assert-AnEbTreeNoReparse {
    param([Parameter(Mandatory = $true)][string]$Root)
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $rootFull -Recurse -Force -ErrorAction Stop)) {
        Assert-AnEbPathUnderRoot -Root $rootFull -Path $item.FullName | Out-Null
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "PATH_REPARSE: $($item.FullName)"
        }
    }
}

function Assert-AnEbExistingParents {
    param([Parameter(Mandatory = $true)][string]$Path)
    $parent = Split-Path -Parent (Get-AnEbFullPath -Path $Path)
    while ($parent) {
        Assert-AnEbDirectory -Path $parent | Out-Null
        $next = Split-Path -Parent $parent
        if ($next -eq $parent) {
            break
        }
        $parent = $next
    }
}

function Assert-AnEbNoReparseAncestors {
    param([Parameter(Mandatory = $true)][string]$Path)
    $full = Get-AnEbFullPath -Path $Path
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "PATH_REPARSE_ANCESTOR: $full"
    }
    $parent = Split-Path -Parent $full
    while ($parent) {
        $parentItem = Get-Item -LiteralPath $parent -Force -ErrorAction Stop
        if (($parentItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "PATH_REPARSE_ANCESTOR: $parent"
        }
        $next = Split-Path -Parent $parent
        if ($next -eq $parent) {
            break
        }
        $parent = $next
    }
}

function Get-AnEbSha256Bytes {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-AnEbSha256File {
    param([Parameter(Mandatory = $true)][string]$Path)
    Assert-AnEbRegularFile -Path $Path | Out-Null
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = New-Object System.IO.FileStream(
        (Get-AnEbFullPath -Path $Path),
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $stream.Dispose()
        $sha.Dispose()
    }
}

function Read-AnEbBytes {
    param([Parameter(Mandatory = $true)][string]$Path)
    Assert-AnEbRegularFile -Path $Path | Out-Null
    return ,([System.IO.File]::ReadAllBytes((Get-AnEbFullPath -Path $Path)))
}

function ConvertTo-AnEbUtf8NoBomBytes {
    param([Parameter(Mandatory = $true)][string]$Text)
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    return $encoding.GetBytes($Text)
}

function ConvertFrom-AnEbUtf8Strict {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        throw 'UTF8_BOM_REJECTED'
    }
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    return $encoding.GetString($Bytes)
}

function Read-AnEbUtf8Strict {
    param([Parameter(Mandatory = $true)][string]$Path)
    return ConvertFrom-AnEbUtf8Strict -Bytes (Read-AnEbBytes -Path $Path)
}

function Write-AnEbCreateNewBytes {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Bytes
    )
    Assert-AnEbExistingParents -Path $Path
    $full = Get-AnEbFullPath -Path $Path
    $stream = New-Object System.IO.FileStream(
        $full,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    try {
        if ($Bytes.Length -gt 0) {
            $stream.Write($Bytes, 0, $Bytes.Length)
        }
        $stream.Flush($true)
    }
    finally {
        $stream.Dispose()
    }
}

function Write-AnEbCreateNewUtf8 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    Write-AnEbCreateNewBytes -Path $Path -Bytes (ConvertTo-AnEbUtf8NoBomBytes -Text $Text)
}

function Write-AnEbCanonicalJsonCreateNew {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $json = $Value | ConvertTo-Json -Compress -Depth 12
    Write-AnEbCreateNewUtf8 -Path $Path -Text ($json + [Environment]::NewLine)
}

function Get-AnEbRelativeFiles {
    param([Parameter(Mandatory = $true)][string]$Root)
    $rootFull = (Get-AnEbFullPath -Path $Root).TrimEnd('\')
    Assert-AnEbDirectory -Path $rootFull | Out-Null
    $rootInfo = Get-Item -LiteralPath $rootFull -Force
    $files = @()
    Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force -ErrorAction Stop |
        Sort-Object FullName |
        ForEach-Object {
            if (($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "FILE_REPARSE: $($_.FullName)"
            }
            $relative = $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
            $files += [pscustomobject]@{
                RelativePath = $relative
                FullPath = $_.FullName
                Length = [int64]$_.Length
            }
        }
    return @($files)
}

function Assert-AnEbRelativePath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        throw 'RELATIVE_PATH_EMPTY'
    }
    if ($RelativePath.Contains('\') -or $RelativePath.Contains(':') -or
        $RelativePath.StartsWith('/') -or $RelativePath.StartsWith('\\') -or
        [System.IO.Path]::IsPathRooted($RelativePath)) {
        throw "RELATIVE_PATH_INVALID: $RelativePath"
    }
    $parts = $RelativePath.Split('/')
    foreach ($part in $parts) {
        if ($part -eq '' -or $part -eq '.' -or $part -eq '..') {
            throw "RELATIVE_PATH_INVALID: $RelativePath"
        }
    }
}

function Get-AnEbUtcNow {
    return [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
}

function Assert-AnEbNoAbsoluteDeveloperPath {
    param([Parameter(Mandatory = $true)][string]$Root)
    $textExtensions = @('.md', '.json', '.html', '.txt', '.csv', '.jsonl')
    foreach ($file in (Get-AnEbRelativeFiles -Root $Root)) {
        $extension = [System.IO.Path]::GetExtension($file.RelativePath).ToLowerInvariant()
        if ($textExtensions -contains $extension) {
            $text = Read-AnEbUtf8Strict -Path $file.FullPath
            if ($text -match '(?i)([A-Z]:\\|/Users/|/home/|/root/)') {
                throw "ABSOLUTE_DEVELOPER_PATH: $($file.RelativePath)"
            }
        }
    }
}
