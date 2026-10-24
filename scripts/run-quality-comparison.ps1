[CmdletBinding()]
param(
    [string]$OutputDirectory = ('tools/agent-evaluation/reports/quality-' + (Get-Date -Format 'yyyyMMddHHmmss')),
    [string]$EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'),
    [switch]$RequireCopilotHealth,
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile
$dataset = 'tools/agent-evaluation/data/phase3-quality-v2.jsonl'
$datasetPath = Join-Path $root $dataset
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else { [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory)) }
if ($PlanOnly) {
    @{ mode=$(if ($RequireCopilotHealth) {'copilot-health-gated-provider-evaluation'} else {'independent-provider-evaluation'});
       provider=$env:COPILOT_MODEL_PROVIDER; model=$env:COPILOT_MODEL_ID;
       dataset='phase3-quality-v2'; prompts=@('review-analysis-v1','review-analysis-v2');
       maxProviderCalls=12; output=$output } | ConvertTo-Json -Compress
    return
}
if (Test-Path -LiteralPath $output) { throw 'Choose a new output directory; existing reports are not overwritten.' }
New-Item -ItemType Directory -Path $output | Out-Null
$initialInputPrice = $env:COPILOT_INPUT_PRICE_PER_MILLION
$initialOutputPrice = $env:COPILOT_OUTPUT_PRICE_PER_MILLION
$initialTimeout = $env:COPILOT_MODEL_TIMEOUT_SECONDS
try {
    # No automatic retry. Two fixed batches of six cases, at most twelve provider requests.
    if (-not $env:COPILOT_MODEL_TIMEOUT_SECONDS) { $env:COPILOT_MODEL_TIMEOUT_SECONDS = '60' }
    foreach ($version in @('review-analysis-v1', 'review-analysis-v2')) {
        if ($RequireCopilotHealth) {
            & (Join-Path $PSScriptRoot 'run-phase3-evaluation.ps1') -Dataset $dataset `
                -DatasetVersion 'phase3-quality-v2' -KnowledgeIndexVersion 'quality-fixture-v2' `
                -PromptVersion $version -MaxCases 6 -Output (Join-Path $output "$version.json")
        } else {
            Push-Location (Join-Path $root 'tools/agent-evaluation')
            try {
                $configuration = "phase3-quality-v2:$($env:COPILOT_MODEL_PROVIDER):$($env:COPILOT_MODEL_ID)"
                if ($env:COPILOT_MODEL_PROVIDER -eq 'deepseek') {
                    $configuration += ':thinking-disabled:temperature-0:max-output-2000'
                }
                & uv run --python 3.12 python -m finguard_agent_evaluation.runner --mode real `
                    --dataset $datasetPath --dataset-version phase3-quality-v2 `
                    --knowledge-index-version quality-fixture-v2 --prompt-version $version `
                    --model-configuration-version $configuration --max-cases 6 `
                    --output (Join-Path $output "$version.json")
                if ($LASTEXITCODE -ne 0) { throw 'Independent evaluation failed; no automatic retry.' }
            } finally { Pop-Location }
        }
    }
    Push-Location (Join-Path $root 'tools/agent-evaluation')
    try {
        & uv run --python 3.12 python -m finguard_agent_evaluation.runner --mode compare-prompts `
            --reports (Join-Path $output 'review-analysis-v1.json') (Join-Path $output 'review-analysis-v2.json') `
            --output (Join-Path $output 'comparison.json')
        if ($LASTEXITCODE -ne 0) { throw 'Comparison report validation failed.' }
    } finally { Pop-Location }
    Write-Host "COMPARISON $(Join-Path $output 'comparison.json')"
} finally {
    $env:COPILOT_INPUT_PRICE_PER_MILLION = $initialInputPrice
    $env:COPILOT_OUTPUT_PRICE_PER_MILLION = $initialOutputPrice
    $env:COPILOT_MODEL_TIMEOUT_SECONDS = $initialTimeout
}
