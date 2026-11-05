# Each case really terminates the run-owned JVM. Only the expired lease timestamp is advanced on recovery.
if(-not $LocalModelStub -or -not $redisStarted -or $isolatedRedisContainer -ne "fg-e2e-redis-$runId") { throw 'Fault matrix requires this run-owned loopback fixture' }
$matrix=@(
    @{point='TOOL_BEFORE_CLAIM';status='SUCCEEDED';http=2;reads=2},
    @{point='TOOL_AFTER_CLAIM';status='FAILED';http=0;reads=0},
    @{point='TOOL_AFTER_READ';status='FAILED';http=0;reads=1},
    @{point='TOOL_AFTER_PERSIST';status='SUCCEEDED';http=2;reads=2},
    @{point='MODEL_BEFORE_CLAIM';status='SUCCEEDED';http=2;reads=2},
    @{point='MODEL_AFTER_CLAIM';status='FAILED';http=0;reads=1},
    @{point='MODEL_AFTER_RESPONSE';status='FAILED';http=1;reads=1},
    @{point='MODEL_AFTER_PERSIST';status='FAILED';http=1;reads=1},
    @{point='CHECKPOINT_AFTER_COMMIT';status='SUCCEEDED';http=2;reads=2},
    @{point='BEFORE_ACK';status='SUCCEEDED';http=2;reads=2}
)
$report.faultMatrix=@();$env:COPILOT_AGENT_HUMAN_GATE='false';$env:COPILOT_AGENT_FIXTURE_PROBES='true'
foreach($case in $matrix) {
    if(-not $copilot.HasExited) { Stop-Process -Id $copilot.Id -Force; $copilot.WaitForExit() }
    # Reset only the run-owned isolated Redis, so a killed owner's TTL cannot mask the next fault point.
    & docker exec -e REDISCLI_AUTH=isolated-test-only $isolatedRedisContainer redis-cli FLUSHDB | Out-Null
    if($LASTEXITCODE -ne 0) {throw 'Isolated Redis reset failed'}
    $env:COPILOT_AGENT_FIXTURE_FAULT_POINT=$case.point
    $label="fault-$($case.point)"
    $copilot=Start-Java $label $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar')
    $processes+=$copilot; Health 'http://127.0.0.1:9091' $copilot
    $beforeCalls=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
    $caseHeaders=@{Authorization=$headers.Authorization;'Idempotency-Key'="$runId-$($case.point)"}
    $created=Invoke-RestMethod $url -Method Post -Headers $caseHeaders -ContentType 'application/json' -Body $body
    $analysisId=$created.analysisId
    $deathDeadline=[DateTime]::UtcNow.AddSeconds(90)
    do { Start-Sleep -Milliseconds 500;$copilot.Refresh() } while(-not $copilot.HasExited -and [DateTime]::UtcNow -lt $deathDeadline)
    Check "Fault $($case.point) killed only its owned JVM at the intended window" ($copilot.HasExited -and $copilot.ExitCode -eq 86)
    $beforeRecovery=Sql "SELECT revision,next_node,model_calls,tool_calls FROM $copilotDb.analysis_checkpoint WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
    $env:COPILOT_AGENT_FIXTURE_FAULT_POINT=''
    $null=Sql "UPDATE $copilotDb.analysis_checkpoint SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE analysis_id=UNHEX(REPLACE('$analysisId','-','')) AND owner_token IS NOT NULL;"
    $recoveryLabel="recover-$($case.point)"
    $copilot=Start-Java $recoveryLabel $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar')
    $processes+=$copilot; Health 'http://127.0.0.1:9091' $copilot
    $page=Await-Agent @('SUCCEEDED','FAILED')
    $http=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls-$beforeCalls
    $readCount=0
    foreach($log in @($label,$recoveryLabel)) {
        $readCount+=@((Get-Content -LiteralPath (Join-Path $output "$log.log"))|Where-Object{$_ -eq "PHASE5_TOOL_READ $analysisId"}).Count
    }
    $calls=Sql "SELECT call_ordinal,status,outcome_type,CAST(reserved_cny AS CHAR),CAST(settled_cny AS CHAR) FROM $copilotDb.agent_model_call WHERE analysis_id=UNHEX(REPLACE('$analysisId','-','')) ORDER BY call_ordinal;"
    $tools=Sql "SELECT slot,status FROM $copilotDb.analysis_tool_execution WHERE analysis_id=UNHEX(REPLACE('$analysisId','-','')) ORDER BY slot;"
    $events=Sql "SELECT GROUP_CONCAT(sequence_number ORDER BY sequence_number) FROM $copilotDb.analysis_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
    $receipts=Sql "SELECT COUNT(*) FROM $copilotDb.analysis_message_receipt WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
    $outbox=Sql "SELECT COUNT(*) FROM $copilotDb.outbox_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
    $fingerprintAfter=Sql ('CHECKSUM TABLE '+($coreTables -join ','))
    $entry=@{point=$case.point;analysisId=$analysisId;expectedStatus=$case.status;status=$page.status;failureCode=$page.failureCode;
        actualHttpCalls=$http;expectedHttpCalls=$case.http;actualToolReads=$readCount;expectedToolReads=$case.reads;
        beforeRecovery=$beforeRecovery;calls=$calls;tools=$tools;eventSequences=$events;receiptCount=$receipts;outboxCount=$outbox;
        coreChecksum=$fingerprintAfter;leaseRecovery='expired ownership timestamp after actual JVM halt; claims and memory untouched'}
    $report.faultMatrix+=$entry
    Check "Fault $($case.point) recovers without extra model/tool executions or business writes" ($page.status -eq $case.status -and $http -eq $case.http -and $readCount -eq $case.reads -and $fingerprintAfter -eq $fingerprintBefore)
    if($case.point -in @('MODEL_AFTER_CLAIM','MODEL_AFTER_RESPONSE')) {
        Check "Fault $($case.point) keeps the uncertain call held permanently" ($calls.Contains('UNKNOWN_HELD') -and -not $calls.Contains('RELEASED_NOT_SENT'))
    }
}
