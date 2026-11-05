# Dot-sourced only by the isolated, loopback-only parent harness. No credentials in reports.
$report.maxModelCalls=2; $report.expectedCalls=2; $report.workflow='agent-v1'
function Await-Agent([string[]]$Statuses) {
    $deadline=[DateTime]::UtcNow.AddSeconds(100)
    do {
        Start-Sleep -Milliseconds 500
        $view=Invoke-RestMethod "$url/$analysisId/view" -Headers $headers
    } while ($view.status -notin $Statuses -and [DateTime]::UtcNow -lt $deadline)
    return $view
}
function Agent-Evidence([string]$Scenario,[int]$HttpBefore) {
    $state=Invoke-RestMethod "$url/$analysisId/view" -Headers $headers
    return @{scenario=$Scenario;analysisId=$analysisId;status=$state.status;failureCode=$state.failureCode;
        actualHttpCalls=([int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls-$HttpBefore);
        modelClaims=(Sql "SELECT COUNT(*) FROM $copilotDb.agent_model_call WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));");
        toolReceipts=(Sql "SELECT COUNT(*) FROM $copilotDb.analysis_tool_execution WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));");
        tools=(Sql "SELECT slot,tool_name,status FROM $copilotDb.analysis_tool_execution WHERE analysis_id=UNHEX(REPLACE('$analysisId','-','')) ORDER BY slot;");
        calls=(Sql "SELECT call_ordinal,status,outcome_type,CAST(reserved_cny AS CHAR),CAST(settled_cny AS CHAR) FROM $copilotDb.agent_model_call WHERE analysis_id=UNHEX(REPLACE('$analysisId','-','')) ORDER BY call_ordinal;");
        events=(Sql "SELECT GROUP_CONCAT(sequence_number ORDER BY sequence_number) FROM $copilotDb.analysis_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));");
        outbox=(Sql "SELECT COUNT(*) FROM $copilotDb.outbox_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));");
        receipts=(Sql "SELECT COUNT(*) FROM $copilotDb.analysis_message_receipt WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));");
        coreChecksum=(Sql ('CHECKSUM TABLE '+($coreTables -join ',')))}
}
function Reset-AgentFixtureQuota {
    if(-not $redisStarted -or $isolatedRedisContainer -ne "fg-e2e-redis-$runId"){throw 'Quota reset requires the run-owned isolated Redis'}
    & docker exec -e REDISCLI_AUTH=isolated-test-only $isolatedRedisContainer redis-cli FLUSHDB | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Run-owned Redis reset failed'}
}
function Continue-AgentIfWaiting {
    $current=Await-Agent @('WAITING_FOR_HUMAN','SUCCEEDED','FAILED')
    if($current.status -eq 'WAITING_FOR_HUMAN'){
        $resumeRequest=@{expectedRevision=$current.agent.revision;action='CONTINUE'}|ConvertTo-Json
        Invoke-WebRequest "$url/$analysisId/resume" -Method Post -Headers @{Authorization=$headers.Authorization;'Idempotency-Key'="continue-$analysisId"} -ContentType 'application/json' -Body $resumeRequest|Out-Null
        $current=Await-Agent @('SUCCEEDED','FAILED')
    }
    return $current
}
Check 'Agent unauthenticated creation rejected' ((Http-Status 'POST' $url @{} $body) -eq 401)
$accepted=Invoke-WebRequest $url -Method Post -Headers $headers -ContentType 'application/json' -Body $body
Check 'Agent accepted with HTTP 202' ($accepted.StatusCode -eq 202)
$analysisId=($accepted.Content|ConvertFrom-Json).analysisId; $report.analysisId=$analysisId
$page=Await-Agent @('WAITING_FOR_HUMAN','SUCCEEDED','FAILED')
Check 'Native tool selection pauses durably before execution' ($page.status -eq 'WAITING_FOR_HUMAN' -and $page.agent.plannedTools -contains 'get_task_state' -and $page.agent.modelCalls -eq 1 -and $page.agent.toolCalls -eq 1)
$revision=$page.agent.revision
Check 'Revision is an exact string' ($revision -is [string])
$resumeBody=@{expectedRevision=$revision;action='CONTINUE'}|ConvertTo-Json
$resumeHeaders=@{Authorization=$headers.Authorization;'Idempotency-Key'="resume-$runId"}
foreach($suffix in @('trace','view')) {
    Check "Agent $suffix requires fresh reviewer identity" ((Http-Status 'GET' "$url/$analysisId/$suffix" $adminHeaders '') -eq 403)
}
Check 'Resume rejects missing identity' ((Http-Status 'POST' "$url/$analysisId/resume" @{} $resumeBody) -eq 401)
Check 'Resume rejects stale revision' ((Http-Status 'POST' "$url/$analysisId/resume" $resumeHeaders (@{expectedRevision='0';action='CONTINUE'}|ConvertTo-Json)) -eq 409)
Stop-Process -Id $copilot.Id -Force
$copilot.WaitForExit()
$copilot=Start-Java 'copilot-restarted' $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar')
$processes += $copilot; Health 'http://127.0.0.1:9091' $copilot
$afterRestart=Invoke-RestMethod "$url/$analysisId/view" -Headers $headers
Check 'Owned JVM restart preserves waiting revision and counters' ($afterRestart.status -eq 'WAITING_FOR_HUMAN' -and $afterRestart.agent.revision -eq $revision -and $afterRestart.agent.modelCalls -eq 1 -and $afterRestart.agent.toolCalls -eq 1)
$continued=Invoke-WebRequest "$url/$analysisId/resume" -Method Post -Headers $resumeHeaders -ContentType 'application/json' -Body $resumeBody
Check 'Reauthorized continuation accepted with HTTP 202' ($continued.StatusCode -eq 202)
$again=Invoke-RestMethod "$url/$analysisId/resume" -Method Post -Headers $resumeHeaders -ContentType 'application/json' -Body $resumeBody
Check 'Duplicate continuation replays the same receipt' ($again.replayed -eq $true)
$page=Await-Agent @('SUCCEEDED','FAILED');$report.analysisStatus=$page.status; $report.failureCode=$page.failureCode
Check 'Resumed native graph reaches validated success' ($page.status -eq 'SUCCEEDED')
$trace=Invoke-RestMethod "$url/$analysisId/trace" -Headers $headers
$report.actualModelHttpCalls=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
$report.agentCalls=Sql "SELECT call_ordinal,status,input_tokens,output_tokens,CAST(settled_cny AS CHAR) FROM $copilotDb.agent_model_call ORDER BY call_ordinal;"
$report.toolExecutions=Sql "SELECT slot,tool_name,status FROM $copilotDb.analysis_tool_execution ORDER BY slot;"
Check 'Two native HTTP calls and two settled immutable claims' ($report.actualModelHttpCalls -eq 2 -and (Sql "SELECT COUNT(*) FROM $copilotDb.agent_model_call WHERE status='SETTLED';") -eq '2')
Check 'Both persisted tool executions are audited exactly once' ($trace.tools.Count -eq 2 -and $trace.calls.Count -eq 2 -and $page.agent.toolCalls -eq 2)
Check 'Graph never falls back to the legacy paid-call ledger' ((Sql "SELECT COUNT(*) FROM $copilotDb.model_call_reservation;") -eq '0')
$fingerprintAfter=Sql ('CHECKSUM TABLE '+($coreTables -join ','))
$report.coreChecksumBefore=$fingerprintBefore;$report.coreChecksumAfter=$fingerprintAfter
Check 'All Core business table checksums unchanged' ($fingerprintAfter -eq $fingerprintBefore)
$stream=(Invoke-WebRequest "$url/$analysisId/events" -Headers $headers -TimeoutSec 15).Content
$sequences=@([regex]::Matches($stream,'(?m)^id:(\d+)')|ForEach-Object{[long]$_.Groups[1].Value})
$persisted=Sql "SELECT GROUP_CONCAT(sequence_number ORDER BY sequence_number) FROM $copilotDb.analysis_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
Check 'Graph SSE replays every committed controlled state' (($sequences -join ',') -eq $persisted -and $stream.Contains('WAITING_FOR_HUMAN'))
$report.eventSequences=$sequences
if($McpAcceptance) { . (Join-Path $PSScriptRoot 'phase5_mcp_acceptance.ps1') }
if($AgentBranchMatrix) { . (Join-Path $PSScriptRoot 'phase5_branch_matrix.ps1') }
if($AgentInfrastructureMatrix) { . (Join-Path $PSScriptRoot 'phase5_infrastructure_matrix.ps1') }
if($AgentFaultMatrix) { . (Join-Path $PSScriptRoot 'phase5_fault_matrix.ps1') }
if($BrowserAcceptance) {
    @{username='e2e-reviewer';password=$env:FINGUARD_AUTH_BOOTSTRAP_REVIEWER_PASSWORD;reviewTaskId=[string]$taskId;analysisId=$analysisId;runId=$runId}|ConvertTo-Json|Set-Content (Join-Path $output 'browser-session.json') -Encoding UTF8
}
$report.status='PASSED'
