[CmdletBinding()]
param(
    [string] $Output,
    [string] $Dataset = "tools/agent-evaluation/data/phase3-quality-v2.jsonl",
    [string] $PromptVersion = "review-analysis-v2",
    [string] $DatasetVersion = "phase3-quality-v2",
    [string] $KnowledgeIndexVersion = "quality-fixture-v2",
    [int] $MaxCases = 0,
    [string] $EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'),
    [switch] $PlanOnly
)

. (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile
if (-not $Output) {
    $Output = 'tools/agent-evaluation/reports/phase3-{0}-{1}-{2}.json' -f `
        $env:COPILOT_MODEL_PROVIDER,(Get-Date -Format 'yyyyMMddHHmmssfff'),([guid]::NewGuid().ToString('N').Substring(0,8))
}

$required = @(
    'FINGUARD_COPILOT_BASE_URL',
    'COPILOT_MODEL_PROVIDER',
    'COPILOT_MODEL_ID',
    'COPILOT_MODEL_API_KEY',
    'COPILOT_PRICE_TABLE_VERSION',
    'COPILOT_INPUT_PRICE_PER_MILLION',
    'COPILOT_OUTPUT_PRICE_PER_MILLION'
)
$missing = @($required | Where-Object { -not (Get-Item "Env:$($_)" -ErrorAction SilentlyContinue).Value })
if ($missing.Count -gt 0) {
    throw "Missing required environment fields: $($missing -join ', ')"
}

$provider = $env:COPILOT_MODEL_PROVIDER.ToLowerInvariant()
$expectedBaseUrl = switch ($provider) {
    'qwen' { 'https://dashscope.aliyuncs.com/compatible-mode/v1' }
    'deepseek' { 'https://api.deepseek.com' }
    'zhipu' { 'https://open.bigmodel.cn/api/paas/v4' }
    default { throw "Unsupported model provider: $provider" }
}

if ($env:COPILOT_MODEL_BASE_URL -and $env:COPILOT_MODEL_BASE_URL.TrimEnd('/') -ne $expectedBaseUrl) {
    throw "COPILOT_MODEL_BASE_URL is not allowed for provider $provider"
}
if ($MaxCases -lt 0) {
    throw 'MaxCases must not be negative.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$datasetPath = if ([IO.Path]::IsPathRooted($Dataset)) { [IO.Path]::GetFullPath($Dataset) } `
    else { [IO.Path]::GetFullPath((Join-Path $repoRoot ($Dataset -replace '/', '\'))) }
$outputPath = if ([IO.Path]::IsPathRooted($Output)) { [IO.Path]::GetFullPath($Output) } `
    else { [IO.Path]::GetFullPath((Join-Path $repoRoot ($Output -replace '/', '\'))) }
if (Test-Path -LiteralPath $outputPath) { throw 'Existing evaluation reports are not overwritten; choose a new Output.' }
if ($PlanOnly) {
    @{ provider=$provider; model=$env:COPILOT_MODEL_ID; output=$outputPath;
       dataset=$DatasetVersion; prompt=$PromptVersion; mode='copilot-health-gated-provider-evaluation' } | ConvertTo-Json -Compress
    return
}

$managementBase = if ($env:COPILOT_MANAGEMENT_BASE_URL) { $env:COPILOT_MANAGEMENT_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:9091' }
$healthUrl = "$managementBase/actuator/health"
try {
    $health = Invoke-RestMethod -UseBasicParsing -Uri $healthUrl -Method Get
    if ($health.status -ne 'UP') {
        throw 'health status is not UP'
    }
}
catch {
    throw "Copilot health check failed: $($_.Exception.GetType().Name)"
}

$modelConfigurationVersion = $env:COPILOT_MODEL_CONFIGURATION_VERSION
if (-not $modelConfigurationVersion) {
    $modelConfigurationVersion = "phase3-model-v1:{0}:{1}" -f $provider, $env:COPILOT_MODEL_ID
}

Push-Location $repoRoot
try {
    Push-Location tools/agent-evaluation
    try {
        $arguments = @(
            '-m', 'finguard_agent_evaluation.runner',
            '--mode', 'real',
            '--dataset', $datasetPath,
            '--prompt-version', $PromptVersion,
            '--dataset-version', $DatasetVersion,
            '--knowledge-index-version', $KnowledgeIndexVersion,
            '--model-configuration-version', $modelConfigurationVersion,
            '--output', $outputPath,
            '--max-cases', $MaxCases
        )
        & uv run --python 3.12 python @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Real model evaluation failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    Pop-Location
}
