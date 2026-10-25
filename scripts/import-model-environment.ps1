param([string]$Path = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'))
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Path) {
    foreach ($modelEnvironmentLine in (Get-Content -LiteralPath $Path)) {
        if ($modelEnvironmentLine -match '^\s*(#|$)') { continue }
        $modelEnvironmentParts = $modelEnvironmentLine -split '=', 2
        if ($modelEnvironmentParts.Count -ne 2) { throw 'Malformed local model configuration line.' }
        $modelEnvironmentName = $modelEnvironmentParts[0].Trim()
        if ($modelEnvironmentName -notmatch '^(COPILOT_(MODEL_[A-Z0-9_]+|PROMPT_VERSION|PRICE_TABLE_VERSION|INPUT_PRICE_PER_MILLION|OUTPUT_PRICE_PER_MILLION)|SPRING_PROFILES_ACTIVE|FINGUARD_COPILOT_BASE_URL)$') { continue }
        $modelEnvironmentValue = $modelEnvironmentParts[1].Trim().Trim('"').Trim("'")
        if (-not [Environment]::GetEnvironmentVariable($modelEnvironmentName, 'Process')) {
            [Environment]::SetEnvironmentVariable($modelEnvironmentName, $modelEnvironmentValue, 'Process')
        }
    }
}
