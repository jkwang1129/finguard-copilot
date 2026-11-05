[CmdletBinding()]
param(
    [string]$EnvironmentFile='C:/dev/worktrees/finguard-copilot-phase1/.env.local',
    [switch]$Execute,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$initialEnvironment=@{}
Get-ChildItem Env: | ForEach-Object {$initialEnvironment[$_.Name]=$_.Value}
$nativeExit=1
try {
    if(-not $Execute -and -not $ValidateOnly){throw 'Execute or ValidateOnly required'}
    $manifest=Get-Content -LiteralPath (Join-Path $root 'docs/evaluations/human-judge-batch-20261104-manifest.json') -Raw | ConvertFrom-Json
    if($manifest.status -ne 'AUTHORIZED_READY' -or $manifest.maxCalls -ne 10 -or [decimal]$manifest.maxSpendCny -ne 0.10 -or $manifest.automaticRetries -ne 0){throw 'Frozen Judge batch limits/status invalid'}
    $now=[DateTimeOffset]::UtcNow
    if($now -lt [DateTimeOffset]::Parse($manifest.priceValidFrom) -or $now -ge [DateTimeOffset]::Parse($manifest.priceValidUntil)){throw 'Reviewed Judge price window expired'}
    foreach($entry in $manifest.verifiedSourceShas.PSObject.Properties){
        if((Get-FileHash (Join-Path $root $entry.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value){throw 'Frozen Judge input/source changed'}
    }
    $statePath=Join-Path $root '.superpowers/sdd/2026-11-04-human-calibration-real-rag-quality/judge-paid-batch.json'
    if(Test-Path -LiteralPath $statePath){throw 'Judge batch already started; no automatic retry'}
    if($ValidateOnly){Write-Host 'JUDGE_BATCH_VALIDATED maxCalls=10 maxSpendCny=0.10 no credential import or HTTP';return}
    . (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile
    if($env:COPILOT_MODEL_PROVIDER -ne 'deepseek' -or $env:COPILOT_MODEL_ID -ne 'deepseek-flash' -or -not $env:COPILOT_MODEL_API_KEY -or $env:COPILOT_MODEL_BASE_URL.TrimEnd('/') -ne 'https://api.deepseek.com'){throw 'Exact approved DeepSeek credential/model configuration required'}
    $state=[ordered]@{batch='human-judge-20261104';status='STARTING';startedAt=[DateTimeOffset]::Now.ToString('o');maxCalls=10;maxSpendCny='0.10';reservedPerCallCny='0.00870400';automaticRetries=0}
    $exclusive=[IO.File]::Open($statePath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes(($state|ConvertTo-Json));$exclusive.Write($bytes,0,$bytes.Length)}finally{$exclusive.Dispose()}
    $output=Join-Path $root 'docs/evaluations/phase4-human-judge-20261104-v1.jsonl'
    $usage=Join-Path $root 'docs/evaluations/phase4-human-judge-usage-20261104-v1.jsonl'
    $dataset=Join-Path $root 'docs/evaluations/phase4-human-calibration-input-20261104-v1.jsonl'
    & uv run --offline --project (Join-Path $root 'tools/agent-evaluation') python -m finguard_agent_evaluation.phase4_provider_judge --dataset $dataset --dataset-sha256 $manifest.datasetSha256 --output $output --usage-output $usage --max-spend-cny '0.10' *> (Join-Path $root '.superpowers/sdd/2026-11-04-human-calibration-real-rag-quality/judge-execution.log')
    $nativeExit=$LASTEXITCODE
    $state.status=if($nativeExit -eq 0){'COMPLETE'}else{'STOPPED_NO_AUTOMATIC_RETRY'}
    $state.finishedAt=[DateTimeOffset]::Now.ToString('o');$state.exitCode=$nativeExit
    if(Test-Path -LiteralPath $usage){
        $rows=@(Get-Content -LiteralPath $usage | Select-Object -Skip 1 | ForEach-Object {$_|ConvertFrom-Json})
        $state.calls=$rows.Count
        $settled=[decimal]0
        foreach($row in $rows){if($null -ne $row.estimated_cny){$settled += [decimal]$row.estimated_cny}}
        $unknown=@($rows|Where-Object {$_.status -eq 'failed'}).Count
        # A nonzero process may have stopped before writing a case receipt. Retain one possible sent-call hold.
        if($nativeExit -ne 0 -and $unknown -eq 0){$unknown=1}
        $state.knownEstimatedCny=$settled.ToString('0.00000000',[Globalization.CultureInfo]::InvariantCulture)
        $state.unknownHeldCny=([decimal]$unknown*[decimal]'0.00870400').ToString('0.00000000',[Globalization.CultureInfo]::InvariantCulture)
    }
    $state|ConvertTo-Json|Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "JUDGE_BATCH status=$($state.status) calls=$($state.calls) estimateCny=$($state.knownEstimatedCny) heldCny=$($state.unknownHeldCny)"
} finally {
    foreach($entry in @(Get-ChildItem Env:)){if(-not $initialEnvironment.ContainsKey($entry.Name)){[Environment]::SetEnvironmentVariable($entry.Name,$null,'Process')}}
    foreach($name in $initialEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$initialEnvironment[$name],'Process')}
}
exit $nativeExit
