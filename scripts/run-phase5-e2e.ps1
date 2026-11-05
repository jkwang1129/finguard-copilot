[CmdletBinding()]
param([string]$CoreRoot='C:/dev/finguard-core',[switch]$SkipFaultMatrix)
$ErrorActionPreference='Stop'
# This entry point has no real-provider option. Every model request stays on the bounded loopback fixture.
& (Join-Path $PSScriptRoot 'run-business-e2e.ps1') -CoreRoot $CoreRoot -LocalModelStub -McpAcceptance -AgentBranchMatrix -AgentInfrastructureMatrix -AgentFaultMatrix:(-not $SkipFaultMatrix)
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
