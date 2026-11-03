[CmdletBinding()]
param([switch]$Langfuse,[switch]$Stop)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
function Random-Hex { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
function Initialize-LocalFile([string]$Path,[string[]]$Lines,[string]$Project) {
    if (Test-Path -LiteralPath $Path) { return }
    $volumes=@(docker volume ls -q --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect existing monitoring volumes' }
    $containers=@(docker ps -aq --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect existing monitoring containers' }
    if ($volumes.Count -gt 0 -or $containers.Count -gt 0) {
        throw 'Local credentials file is missing but project resources exist; restore the original local env file before starting. Credentials were not regenerated.'
    }
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false))
        try { foreach ($line in $Lines) { $writer.WriteLine($line) } } finally { $writer.Dispose() }
    } finally { $stream.Dispose() }
}
Push-Location $root
try {
    if ($Stop) {
        docker compose --env-file .env.observability.local -f ops/observability/compose.yml stop
        if ($LASTEXITCODE -ne 0) { throw 'Monitoring stop failed' }
        if ($Langfuse) { docker compose --env-file .env.langfuse.local -f ops/langfuse/compose.yml --profile retention stop }
        if ($LASTEXITCODE -ne 0) { throw 'Observability stop failed' }
        return
    }
    Initialize-LocalFile (Join-Path $root '.env.observability.local') @('COPILOT_GRAFANA_ADMIN_PASSWORD='+(Random-Hex)) 'finguard-copilot-observability'
    docker compose --env-file .env.observability.local -f ops/observability/compose.yml up -d
    if ($LASTEXITCODE -ne 0) { throw 'Monitoring startup failed' }
    if ($Langfuse) {
        $lines=@()
        foreach ($name in @('LF_POSTGRES_PASSWORD','LF_CLICKHOUSE_PASSWORD','LF_REDIS_PASSWORD','LF_MINIO_PASSWORD','LF_SALT','LF_ENCRYPTION_KEY','LF_AUTH_SECRET','LF_USER_PASSWORD')) { $lines += $name+'='+(Random-Hex) }
        $lines += 'LF_ORG_ID=fg-local-'+[guid]::NewGuid().ToString('N')
        $lines += 'LF_PROJECT_ID=fg-p4-4-'+[guid]::NewGuid().ToString('N')
        $lines += 'COPILOT_LANGFUSE_PUBLIC_KEY=pk-lf-'+[guid]::NewGuid().ToString('N')
        $lines += 'COPILOT_LANGFUSE_SECRET_KEY=sk-lf-'+[guid]::NewGuid().ToString('N')
        Initialize-LocalFile (Join-Path $root '.env.langfuse.local') $lines 'finguard-copilot-langfuse'
        docker compose --env-file .env.langfuse.local -f ops/langfuse/compose.yml --profile retention up -d
        if ($LASTEXITCODE -ne 0) { throw 'Local Langfuse startup failed; inspect its service health' }
        Write-Output 'Langfuse: http://127.0.0.1:13001 ; local login reviewer@finguard.invalid ; password LF_USER_PASSWORD in .env.langfuse.local'
    }
    Write-Output 'Grafana: http://127.0.0.1:13000 ; password in .env.observability.local ; Prometheus: http://127.0.0.1:19090'
} finally { Pop-Location }
