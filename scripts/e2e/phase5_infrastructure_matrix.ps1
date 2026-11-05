$report.infrastructure=@()
Reset-AgentFixtureQuota
& docker stop $isolatedRedisContainer|Out-Null
$httpBefore=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
try{
    $created=Invoke-RestMethod $url -Method Post -Headers @{Authorization=$headers.Authorization;'Idempotency-Key'="redis-outage-$runId"} -ContentType 'application/json' -Body $body
    $analysisId=$created.analysisId;$page=Await-Agent @('FAILED','SUCCEEDED')
    $record=Agent-Evidence 'redis_outage' $httpBefore;$report.infrastructure+=$record
    Check 'Actual isolated Redis outage fails closed before model HTTP/claim' ($page.status -eq 'FAILED' -and $page.failureCode -eq 'MODEL_CAPACITY_UNAVAILABLE' -and $record.actualHttpCalls -eq 0 -and $record.modelClaims -eq '0')
}finally{& docker start $isolatedRedisContainer|Out-Null;Health 'http://127.0.0.1:9091' $copilot}
Reset-AgentFixtureQuota
Stop-Process -Id $amqpProxy.Id -Force;$amqpProxy.WaitForExit()
$httpBefore=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
$accepted=Invoke-WebRequest $url -Method Post -Headers @{Authorization=$headers.Authorization;'Idempotency-Key'="rabbit-outage-$runId"} -ContentType 'application/json' -Body $body
$analysisId=($accepted.Content|ConvertFrom-Json).analysisId
Start-Sleep -Seconds 2
$pending=Sql "SELECT COUNT(*) FROM $copilotDb.outbox_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-','')) AND published_at IS NULL;"
Check 'AMQP connection outage retains accepted analysis in durable Outbox' ($accepted.StatusCode -eq 202 -and [int]$pending -ge 1 -and ([int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls) -eq $httpBefore)
# Restore the run-owned connection, first with Graph disabled: frozen Agent runs must never enter P4.
Stop-Process -Id $copilot.Id -Force;$copilot.WaitForExit();$env:COPILOT_AGENT_RUNTIME_ENABLED='false'
$amqpProxy=Start-AmqpProxy 'amqp-proxy-restored';$processes+=$amqpProxy
$copilot=Start-Java 'copilot-runtime-disabled' $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar');$processes+=$copilot;Health 'http://127.0.0.1:9091' $copilot
$deadline=[DateTime]::UtcNow.AddSeconds(20)
do{
    $deferred=Sql "SELECT COUNT(*) FROM $copilotDb.analysis_message_receipt WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
    if([int]$deferred -gt 0){break};Start-Sleep -Milliseconds 500
}while([DateTime]::UtcNow -lt $deadline)
Check 'Disabled Agent runtime defers the frozen run without legacy fallback' ([int]$deferred -ge 1 -and (Sql "SELECT COUNT(*) FROM $copilotDb.model_call_reservation WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));") -eq '0' -and ([int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls) -eq $httpBefore)
Stop-Process -Id $copilot.Id -Force;$copilot.WaitForExit();$env:COPILOT_AGENT_RUNTIME_ENABLED='true'
$copilot=Start-Java 'copilot-runtime-restored' $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar');$processes+=$copilot;Health 'http://127.0.0.1:9091' $copilot
$page=Continue-AgentIfWaiting;$record=Agent-Evidence 'rabbit_outage_and_runtime_toggle' $httpBefore;$report.infrastructure+=$record
Check 'Rabbit recovery and runtime reenable finish the original analysis exactly within two calls' ($page.status -eq 'SUCCEEDED' -and $record.actualHttpCalls -eq 2 -and $record.modelClaims -eq '2')
$savedMax=$env:COPILOT_MAX_CALL_CNY;$savedInput=$env:COPILOT_MODEL_INPUT_PRICE_PER_MILLION;$savedOutput=$env:COPILOT_MODEL_OUTPUT_PRICE_PER_MILLION;$savedPrice=$env:COPILOT_PRICE_TABLE_VERSION
try{
    Stop-Process -Id $copilot.Id -Force;$copilot.WaitForExit()
    $env:COPILOT_MAX_CALL_CNY='0.00000001';$env:COPILOT_MODEL_INPUT_PRICE_PER_MILLION='2';$env:COPILOT_MODEL_OUTPUT_PRICE_PER_MILLION='8';$env:COPILOT_PRICE_TABLE_VERSION='p56-priced-fixture-v1'
    $copilot=Start-Java 'copilot-budget-rejection' $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar');$processes+=$copilot;Health 'http://127.0.0.1:9091' $copilot
    Reset-AgentFixtureQuota;$httpBefore=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
    $created=Invoke-RestMethod $url -Method Post -Headers @{Authorization=$headers.Authorization;'Idempotency-Key'="budget-rejection-$runId"} -ContentType 'application/json' -Body $body
    $analysisId=$created.analysisId;$page=Await-Agent @('FAILED','SUCCEEDED');$record=Agent-Evidence 'insufficient_call_budget' $httpBefore;$report.infrastructure+=$record
    Check 'Priced fixture budget rejection performs no provider HTTP or claim' ($page.status -eq 'FAILED' -and $page.failureCode -eq 'MODEL_BUDGET_EXCEEDED' -and $record.actualHttpCalls -eq 0 -and $record.modelClaims -eq '0')
}finally{
    if(-not $copilot.HasExited){Stop-Process -Id $copilot.Id -Force;$copilot.WaitForExit()}
    $env:COPILOT_MAX_CALL_CNY=$savedMax;$env:COPILOT_MODEL_INPUT_PRICE_PER_MILLION=$savedInput;$env:COPILOT_MODEL_OUTPUT_PRICE_PER_MILLION=$savedOutput;$env:COPILOT_PRICE_TABLE_VERSION=$savedPrice
    $copilot=Start-Java 'copilot-budget-restored' $root (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar');$processes+=$copilot;Health 'http://127.0.0.1:9091' $copilot
}
Check 'Infrastructure faults never write Core business tables' ((Sql ('CHECKSUM TABLE '+($coreTables -join ','))) -eq $fingerprintBefore)
