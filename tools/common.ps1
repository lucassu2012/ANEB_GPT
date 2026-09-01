Set-StrictMode -Version 2.0

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
    return [System.IO.File]::ReadAllBytes((Get-AnEbFullPath -Path $Path))
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
        [Parameter(Mandatory = $true)][byte[]]$Bytes
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
