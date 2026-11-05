$scopeBody=@{toolScope=@('get_review_facts','get_task_state','search_review_policy')}|ConvertTo-Json
$openapi=Invoke-RestMethod 'http://127.0.0.1:8081/v3/api-docs'
$capabilityOperation=$openapi.paths.'/api/copilot/review-analyses/{analysisId}/mcp-tools'.post
$issueOperation=$openapi.paths.'/api/copilot/review-analyses/{analysisId}/mcp-capabilities'.post
Check 'OpenAPI distinguishes Core JWT authorization from the bridge capability' ($capabilityOperation.security[0].PSObject.Properties.Name -contains 'McpCapability' -and $issueOperation.security[0].PSObject.Properties.Name -contains 'CoreReviewerBearer')
Check 'MCP issuance still requires Core reviewer identity' ((Http-Status 'POST' "$url/$analysisId/mcp-capabilities" $adminHeaders $scopeBody) -eq 403)
$grantResponse=Invoke-WebRequest "$url/$analysisId/mcp-capabilities" -Method Post -Headers $headers -ContentType 'application/json' -Body $scopeBody
$grant=$grantResponse.Content|ConvertFrom-Json
Check 'Local capability is short lived, scoped and never a Core JWT' ($grantResponse.StatusCode -eq 201 -and $grant.capability -match '^[A-Za-z0-9_-]{43}$' -and $grant.toolScope.Count -eq 3 -and ([DateTimeOffset]$grant.expiresAt)-[DateTimeOffset]::UtcNow -le [TimeSpan]::FromMinutes(5))
$env:FINGUARD_MCP_CAPABILITY=$grant.capability
try {
    $clientResult=& uv run --project (Join-Path $root 'tools/mcp-bridge') python (Join-Path $root 'tools/mcp-bridge/scripts/acceptance_client.py') --analysis-id $analysisId 2> (Join-Path $output 'mcp-client.stderr.log')
    if($LASTEXITCODE -ne 0) { throw 'Local STDIO acceptance failed; inspect mcp-client.stderr.log' }
    $report.mcp=$clientResult|ConvertFrom-Json
    Check 'Real STDIO initialize/list/call and bounded error paths pass' ($report.mcp.initialized -and $report.mcp.successfulReads -eq 2 -and $report.mcp.budgetRejected -and $report.mcp.unknownToolRejected)
    Check 'Agent and MCP together never exceed four shared slots' ((Sql "SELECT COUNT(*) FROM $copilotDb.analysis_tool_execution WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));") -eq '4')
    Invoke-WebRequest "$url/$analysisId/mcp-capabilities" -Method Delete -Headers $headers | Out-Null
    Check 'Explicit revocation denies existing bridge capability' ((Http-Status 'GET' "$url/$analysisId/mcp-tools" @{Authorization="Bearer $($grant.capability)"} '') -eq 401)
    $next=Invoke-RestMethod "$url/$analysisId/mcp-capabilities" -Method Post -Headers $headers -ContentType 'application/json' -Body $scopeBody
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($next.capability))).ToLowerInvariant()
    $null=Sql "UPDATE $copilotDb.analysis_mcp_capability SET expires_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE capability_sha256='$hash';"
    $env:FINGUARD_MCP_CAPABILITY=$next.capability
    $expiredResult=& uv run --project (Join-Path $root 'tools/mcp-bridge') python (Join-Path $root 'tools/mcp-bridge/scripts/acceptance_client.py') --analysis-id $analysisId --expect-denied 2> (Join-Path $output 'mcp-expired.stderr.log')
    if($LASTEXITCODE -ne 0) {throw 'Expired STDIO capability was not denied'}
    $report.mcpExpiry=$expiredResult|ConvertFrom-Json
    Check 'Real STDIO client cannot list tools after capability expiry' $report.mcpExpiry.capabilityDenied
    Check 'MCP never calls a model or writes Core business state' (([int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls) -eq 2 -and (Sql ('CHECKSUM TABLE '+($coreTables -join ','))) -eq $fingerprintBefore)
    $report.mcpStoredCapabilities=Sql "SELECT capability_sha256,revision,expires_at,revoked_at FROM $copilotDb.analysis_mcp_capability;"
} finally { Remove-Item Env:FINGUARD_MCP_CAPABILITY -ErrorAction SilentlyContinue }
