[CmdletBinding()]
param(
    [string]$VerifierExecutable = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$oracle = Join-Path $repo 'contracts\prototype-0.1\validate_contracts.py'
$python = (Get-Command python.exe -ErrorAction Stop).Source
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-g4-evidence-cli-' + [Guid]::NewGuid().ToString('N'))

function Invoke-EvidenceCommand {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ([string]::IsNullOrWhiteSpace($VerifierExecutable)) {
            $output = @(& $python -B $oracle @Arguments 2>&1)
        }
        else {
            $output = @(& ([System.IO.Path]::GetFullPath($VerifierExecutable)) @Arguments 2>&1)
        }
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = [string]::Join([char]10, @($output | ForEach-Object { $_.ToString() }))
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw ('ASSERTION_FAILED evidence command failed output=' + $text)
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $text }
}

function Invoke-EvidenceCommandRaw {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    if ([string]::IsNullOrWhiteSpace($VerifierExecutable)) {
        throw 'ASSERTION_FAILED raw runtime receipt check requires the bundled verifier executable'
    }
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = [System.IO.Path]::GetFullPath($VerifierExecutable)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) {
        $null = $startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw 'ASSERTION_FAILED bundled verifier process did not start'
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdout
            Stderr = $stderr
        }
    }
    finally {
        $process.Dispose()
    }
}

function Invoke-OracleFixture {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $python -B $oracle @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw ('ASSERTION_FAILED oracle fixture command failed output=' + [string]::Join([char]10, $output))
    }
}

function Add-DuplicateCampaignIdMember {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$CampaignId
    )
    $text = [System.IO.File]::ReadAllText($Path)
    $member = '"campaign_id":"' + $CampaignId + '"'
    $index = $text.IndexOf($member, [System.StringComparison]::Ordinal)
    if ($index -lt 0) {
        throw ('ASSERTION_FAILED campaign_id member was not found in ' + $Path)
    }
    $mutated = $text.Insert($index + $member.Length, ',' + $member)
    [System.IO.File]::WriteAllText(
        $Path,
        $mutated,
        (New-Object System.Text.UTF8Encoding($false))
    )
}

function Update-ManifestArtifactClosure {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$ArtifactName
    )
    $manifestPath = Join-Path $Bundle 'manifest.json'
    $artifactPath = Join-Path $Bundle $ArtifactName
    $manifest = [System.IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json
    $matches = @($manifest.artifacts | Where-Object { $_.path -ceq $ArtifactName })
    if ($matches.Count -ne 1) {
        throw ('ASSERTION_FAILED manifest does not contain exactly one artifact path=' + $ArtifactName)
    }
    $artifactBytes = [System.IO.File]::ReadAllBytes($artifactPath)
    $matches[0].size_bytes = $artifactBytes.Length
    $matches[0].sha256 = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText(
        $manifestPath,
        (($manifest | ConvertTo-Json -Compress -Depth 8) + [char]10),
        (New-Object System.Text.UTF8Encoding($false))
    )
}

try {
    New-Item -ItemType Directory -Path $tempRoot -ErrorAction Stop | Out-Null
    $input = Join-Path $tempRoot 'canonical-six'
    $output = Join-Path $tempRoot 'published'

    if (-not [string]::IsNullOrWhiteSpace($VerifierExecutable)) {
        $runtimeFixtureResult = Invoke-EvidenceCommand -Arguments @('emit-bundle', '--help') -AllowFailure
        if ($runtimeFixtureResult.ExitCode -eq 0) {
            throw 'ASSERTION_FAILED runtime verifier exposes the synthetic fixture generator'
        }
        Write-Output 'PASS runtime verifier does not expose synthetic fixture generation'

        $runtimeRoot = Split-Path -Parent ([System.IO.Path]::GetFullPath($VerifierExecutable))
        $forbiddenRuntimeFiles = @(
            Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File -Force |
                Where-Object {
                    $_.Name -in @('README.md', 'validate_contracts.py', 'validate_contracts.pyc') -or
                    $_.FullName -match '[\\/]__pycache__[\\/]'
                }
        )
        if ($forbiddenRuntimeFiles.Count -ne 0) {
            throw ('ASSERTION_FAILED runtime package includes fixture-only files=' +
                [string]::Join(',', @($forbiddenRuntimeFiles.FullName)))
        }
        Write-Output 'PASS runtime package contains no fixture-only contract sources or bytecode'
    }
    Invoke-OracleFixture -Arguments @('emit-bundle', '--scenario', 'quick-complete', '--output', $input, '--omit-manifest')
    $meta = Get-Content -LiteralPath (Join-Path $input 'meta.json') -Raw | ConvertFrom-Json
    $campaignId = [string]$meta.campaign_id

    $finalizeResult = Invoke-EvidenceCommand -Arguments @('finalize-bundle', '--input', $input, '--output-root', $output, '--campaign-id', $campaignId)
    if ($finalizeResult.Output -notmatch 'G4_FINALIZE_OK') {
        throw ('ASSERTION_FAILED canonical six-file input was not finalized output=' + $finalizeResult.Output)
    }

    $published = Join-Path $output $campaignId
    $files = @(Get-ChildItem -LiteralPath $published -File -Force | Sort-Object Name)
    if ($files.Count -ne 7 -or @($files.Name) -notcontains 'manifest.json') {
        throw 'ASSERTION_FAILED finalizer did not publish the exact seven-file bundle'
    }
    $null = Invoke-EvidenceCommand -Arguments @('verify-bundle', '--bundle', $published)
    Write-Output 'PASS canonical six-file input is atomically finalized and independently verified'

    $duplicateFinalizeInput = Join-Path $tempRoot 'duplicate-finalizer-meta-six'
    $duplicateFinalizeOutput = Join-Path $tempRoot 'duplicate-finalizer-meta-published'
    Invoke-OracleFixture -Arguments @(
        'emit-bundle', '--scenario', 'quick-complete', '--output', $duplicateFinalizeInput, '--omit-manifest'
    )
    Add-DuplicateCampaignIdMember `
        -Path (Join-Path $duplicateFinalizeInput 'meta.json') `
        -CampaignId $campaignId
    $duplicateFinalizeResult = Invoke-EvidenceCommand -Arguments @(
        'finalize-bundle',
        '--input', $duplicateFinalizeInput,
        '--output-root', $duplicateFinalizeOutput,
        '--campaign-id', $campaignId
    ) -AllowFailure
    if (
        $duplicateFinalizeResult.ExitCode -eq 0 -or
        $duplicateFinalizeResult.Output -notmatch 'finalizer metadata contains a duplicate JSON key'
    ) {
        throw ('ASSERTION_FAILED finalizer accepted duplicate-key metadata output=' + $duplicateFinalizeResult.Output)
    }
    if (Test-Path -LiteralPath (Join-Path $duplicateFinalizeOutput $campaignId)) {
        throw 'ASSERTION_FAILED duplicate-key finalizer input appeared in the published campaign path'
    }
    Write-Output 'PASS finalizer rejects duplicate-key metadata before publication'

    $duplicateVerifyMetaBundle = Join-Path $tempRoot 'duplicate-verifier-meta-bundle'
    Copy-Item -LiteralPath $published -Destination $duplicateVerifyMetaBundle -Recurse -ErrorAction Stop
    Add-DuplicateCampaignIdMember `
        -Path (Join-Path $duplicateVerifyMetaBundle 'meta.json') `
        -CampaignId $campaignId
    Update-ManifestArtifactClosure -Bundle $duplicateVerifyMetaBundle -ArtifactName 'meta.json'
    $duplicateVerifyMetaResult = Invoke-EvidenceCommand -Arguments @(
        'verify-bundle', '--bundle', $duplicateVerifyMetaBundle
    ) -AllowFailure
    if (
        $duplicateVerifyMetaResult.ExitCode -eq 0 -or
        $duplicateVerifyMetaResult.Output -notmatch 'campaign metadata contains a duplicate JSON key'
    ) {
        throw ('ASSERTION_FAILED verifier accepted duplicate-key metadata output=' + $duplicateVerifyMetaResult.Output)
    }
    Write-Output 'PASS verifier rejects duplicate-key metadata with valid manifest closure'

    $duplicateVerifyManifestBundle = Join-Path $tempRoot 'duplicate-verifier-manifest-bundle'
    Copy-Item -LiteralPath $published -Destination $duplicateVerifyManifestBundle -Recurse -ErrorAction Stop
    Add-DuplicateCampaignIdMember `
        -Path (Join-Path $duplicateVerifyManifestBundle 'manifest.json') `
        -CampaignId $campaignId
    $duplicateVerifyManifestResult = Invoke-EvidenceCommand -Arguments @(
        'verify-bundle', '--bundle', $duplicateVerifyManifestBundle
    ) -AllowFailure
    if (
        $duplicateVerifyManifestResult.ExitCode -eq 0 -or
        $duplicateVerifyManifestResult.Output -notmatch 'campaign manifest contains a duplicate JSON key'
    ) {
        throw ('ASSERTION_FAILED verifier accepted duplicate-key manifest output=' + $duplicateVerifyManifestResult.Output)
    }
    Write-Output 'PASS verifier rejects duplicate-key manifest'

    $duplicateVerifyEventBundle = Join-Path $tempRoot 'duplicate-verifier-event-bundle'
    Copy-Item -LiteralPath $published -Destination $duplicateVerifyEventBundle -Recurse -ErrorAction Stop
    Add-DuplicateCampaignIdMember `
        -Path (Join-Path $duplicateVerifyEventBundle 'events.jsonl') `
        -CampaignId $campaignId
    Update-ManifestArtifactClosure -Bundle $duplicateVerifyEventBundle -ArtifactName 'events.jsonl'
    $duplicateVerifyEventResult = Invoke-EvidenceCommand -Arguments @(
        'verify-bundle', '--bundle', $duplicateVerifyEventBundle
    ) -AllowFailure
    if (
        $duplicateVerifyEventResult.ExitCode -eq 0 -or
        $duplicateVerifyEventResult.Output -notmatch 'campaign event line 1 contains a duplicate JSON key'
    ) {
        throw ('ASSERTION_FAILED verifier accepted duplicate-key event line output=' + $duplicateVerifyEventResult.Output)
    }
    Write-Output 'PASS verifier rejects duplicate-key event line with valid manifest closure'

    foreach ($scenario in @('acceptance-complete', 'quick-interrupted')) {
        $scenarioInput = Join-Path $tempRoot ($scenario + '-six')
        $scenarioOutput = Join-Path $tempRoot ($scenario + '-published')
        Invoke-OracleFixture -Arguments @(
            'emit-bundle', '--scenario', $scenario, '--output', $scenarioInput, '--omit-manifest'
        )
        $scenarioMeta = Get-Content -LiteralPath (Join-Path $scenarioInput 'meta.json') -Raw |
            ConvertFrom-Json
        $scenarioCampaignId = [string]$scenarioMeta.campaign_id
        $null = Invoke-EvidenceCommand -Arguments @(
            'finalize-bundle',
            '--input', $scenarioInput,
            '--output-root', $scenarioOutput,
            '--campaign-id', $scenarioCampaignId
        )
        $null = Invoke-EvidenceCommand -Arguments @(
            'verify-bundle',
            '--bundle', (Join-Path $scenarioOutput $scenarioCampaignId)
        )
        Write-Output ('PASS bundled runtime finalizes and verifies ' + $scenario)
    }

    $uploadSource = Join-Path $tempRoot 'upload-source-six'
    $uploadPayloadPath = Join-Path $tempRoot 'android-handoff.json'
    $uploadOutput = Join-Path $tempRoot 'upload-published'
    Invoke-OracleFixture -Arguments @(
        'emit-bundle', '--scenario', 'quick-complete', '--output', $uploadSource, '--omit-manifest'
    )
    $uploadMetaText = [System.IO.File]::ReadAllText((Join-Path $uploadSource 'meta.json')).Replace("`r`n", "`n")
    $uploadMeta = $uploadMetaText | ConvertFrom-Json
    $uploadPayload = [ordered]@{
        schema_version = 'aneb-prototype-upload-0.1'
        campaign_id = [string]$uploadMeta.campaign_id
        meta_json = $uploadMetaText
        events_jsonl = [System.IO.File]::ReadAllText((Join-Path $uploadSource 'events.jsonl')).Replace("`r`n", "`n")
        runs_csv = [System.IO.File]::ReadAllText((Join-Path $uploadSource 'runs.csv')).Replace("`r`n", "`n")
        summary_csv = [System.IO.File]::ReadAllText((Join-Path $uploadSource 'summary.csv')).Replace("`r`n", "`n")
    }
    [System.IO.File]::WriteAllText(
        $uploadPayloadPath,
        ($uploadPayload | ConvertTo-Json -Compress -Depth 4),
        (New-Object System.Text.UTF8Encoding($false))
    )
    $publishArguments = @(
        'publish-upload', '--input', $uploadPayloadPath, '--output-root', $uploadOutput
    )
    if ([string]::IsNullOrWhiteSpace($VerifierExecutable)) {
        $publishResult = Invoke-EvidenceCommand -Arguments $publishArguments
        $publishReceiptText = $publishResult.Output
    }
    else {
        $rawPublishResult = Invoke-EvidenceCommandRaw -Arguments $publishArguments
        if ($rawPublishResult.ExitCode -ne 0) {
            throw ('ASSERTION_FAILED bundled publish-upload failed stderr=' + $rawPublishResult.Stderr)
        }
        if ($rawPublishResult.Stdout.Contains("`r")) {
            throw 'ASSERTION_FAILED publication receipt contains CR; protocol requires one LF terminator'
        }
        if (-not $rawPublishResult.Stdout.EndsWith("`n") -or $rawPublishResult.Stdout.EndsWith("`n`n")) {
            throw 'ASSERTION_FAILED publication receipt must end with exactly one LF'
        }
        $publishReceiptText = $rawPublishResult.Stdout.Substring(0, $rawPublishResult.Stdout.Length - 1)
    }
    $publishReceipt = $publishReceiptText | ConvertFrom-Json
    $publishReceiptKeys = @($publishReceipt.psobject.Properties.Name | Sort-Object)
    if (
        [string]::Join(',', $publishReceiptKeys) -cne
            'campaign_id,manifest_sha256,publication_status,schema_version' -or
        $publishReceipt.campaign_id -cne [string]$uploadMeta.campaign_id -or
        $publishReceipt.publication_status -cne 'verified' -or
        $publishReceipt.schema_version -cne 'aneb-prototype-publication-receipt-0.1' -or
        [string]$publishReceipt.manifest_sha256 -cnotmatch '^[0-9a-f]{64}$'
    ) {
        throw ('ASSERTION_FAILED Android handoff receipt is not canonical output=' + $publishReceiptText)
    }
    $null = Invoke-EvidenceCommand -Arguments @(
        'verify-bundle', '--bundle', (Join-Path $uploadOutput ([string]$uploadMeta.campaign_id))
    )
    Write-Output 'PASS Android four-record handoff is rendered, finalized, and independently verified'

    $forgedUploadPayloadPath = Join-Path $tempRoot 'android-handoff-forged.json'
    $forgedUploadOutput = Join-Path $tempRoot 'upload-forged-published'
    $forgedUploadRows = @($uploadPayload.runs_csv | ConvertFrom-Csv)
    $forgedUploadRows[0].ttft_ms = ([double]$forgedUploadRows[0].ttft_ms + 1).ToString(
        [Globalization.CultureInfo]::InvariantCulture
    )
    $forgedUploadPayload = [ordered]@{}
    foreach ($property in $uploadPayload.Keys) {
        $forgedUploadPayload[$property] = $uploadPayload[$property]
    }
    $forgedUploadPayload.runs_csv =
        [string]::Join([char]10, @($forgedUploadRows | ConvertTo-Csv -NoTypeInformation)) + [char]10
    [System.IO.File]::WriteAllText(
        $forgedUploadPayloadPath,
        ($forgedUploadPayload | ConvertTo-Json -Compress -Depth 4),
        (New-Object System.Text.UTF8Encoding($false))
    )
    $forgedUploadResult = Invoke-EvidenceCommand -Arguments @(
        'publish-upload', '--input', $forgedUploadPayloadPath, '--output-root', $forgedUploadOutput
    ) -AllowFailure
    if ($forgedUploadResult.ExitCode -eq 0) {
        throw 'ASSERTION_FAILED Android handoff accepted a raw-event to runs metric forgery'
    }
    if (Test-Path -LiteralPath (Join-Path $forgedUploadOutput ([string]$uploadMeta.campaign_id))) {
        throw 'ASSERTION_FAILED rejected Android handoff appeared in the published campaign path'
    }
    Write-Output 'PASS forged Android handoff is rejected without publishing verified evidence'

    $forgedInput = Join-Path $tempRoot 'forged-six'
    $forgedOutput = Join-Path $tempRoot 'forged-published'
    Invoke-OracleFixture -Arguments @('emit-bundle', '--scenario', 'quick-complete', '--output', $forgedInput, '--omit-manifest')
    $runsPath = Join-Path $forgedInput 'runs.csv'
    $runRows = @(Import-Csv -LiteralPath $runsPath)
    $runRows[0].ttft_ms = ([double]$runRows[0].ttft_ms + 1).ToString([Globalization.CultureInfo]::InvariantCulture)
    $runsText = [string]::Join([char]10, @($runRows | ConvertTo-Csv -NoTypeInformation)) + [char]10
    [System.IO.File]::WriteAllText($runsPath, $runsText, (New-Object System.Text.UTF8Encoding($false)))

    $forgedFinalizeResult = Invoke-EvidenceCommand -Arguments @('finalize-bundle', '--input', $forgedInput, '--output-root', $forgedOutput, '--campaign-id', $campaignId) -AllowFailure
    if ($forgedFinalizeResult.ExitCode -eq 0) {
        throw 'ASSERTION_FAILED coordinated manifest closure accepted a runs.csv metric forgery'
    }
    if (Test-Path -LiteralPath (Join-Path $forgedOutput $campaignId)) {
        throw 'ASSERTION_FAILED rejected evidence appeared in the published campaign path'
    }
    $partialManifests = @(Get-ChildItem -LiteralPath $forgedOutput -Filter manifest.json -File -Recurse -ErrorAction SilentlyContinue)
    foreach ($partialManifest in $partialManifests) {
        $partial = Get-Content -LiteralPath $partialManifest.FullName -Raw | ConvertFrom-Json
        if ($partial.publication_status -ceq 'verified') {
            throw 'ASSERTION_FAILED rejected partial evidence retained a verified publication status'
        }
    }
    Write-Output 'PASS raw-event to runs metric forgery is rejected without publishing verified evidence'

    $secretInput = Join-Path $tempRoot 'secret-six'
    $secretOutput = Join-Path $tempRoot 'secret-published'
    Invoke-OracleFixture -Arguments @('emit-bundle', '--scenario', 'quick-complete', '--output', $secretInput, '--omit-manifest')
    [System.IO.File]::AppendAllText(
        (Join-Path $secretInput 'run.log'),
        '2026-08-28T10:01:01Z ERROR api_key=forged-secret' + [char]10,
        (New-Object System.Text.UTF8Encoding($false))
    )
    $secretFinalizeResult = Invoke-EvidenceCommand -Arguments @('finalize-bundle', '--input', $secretInput, '--output-root', $secretOutput, '--campaign-id', $campaignId) -AllowFailure
    if ($secretFinalizeResult.ExitCode -eq 0) {
        throw 'ASSERTION_FAILED evidence containing a credential was published'
    }
    if (Test-Path -LiteralPath (Join-Path $secretOutput $campaignId)) {
        throw 'ASSERTION_FAILED secret-bearing evidence appeared in the published path'
    }
    Write-Output 'PASS secret-bearing run.log evidence is rejected before publication'
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
