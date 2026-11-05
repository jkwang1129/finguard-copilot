$branches=@(
    @{scenario='direct_final';status='SUCCEEDED';code=$null;http=1;tools=1},
    @{scenario='format_repair';status='SUCCEEDED';code=$null;http=2;tools=1},
    @{scenario='fact_mismatch';status='FAILED';code='FACT_VALIDATION_FAILED';http=1;tools=1},
    @{scenario='policy_conflict';status='FAILED';code='FACT_VALIDATION_FAILED';http=1;tools=1},
    @{scenario='output_injection';status='FAILED';code='FACT_VALIDATION_FAILED';http=1;tools=1},
    @{scenario='unknown_tool';status='FAILED';code='MODEL_OUTPUT_INVALID';http=1;tools=1},
    @{scenario='extra_arguments';status='FAILED';code='MODEL_OUTPUT_INVALID';http=1;tools=1},
    @{scenario='url_argument';status='FAILED';code='MODEL_OUTPUT_INVALID';http=1;tools=1},
    @{scenario='query_injection';status='FAILED';code='MODEL_OUTPUT_INVALID';http=1;tools=1},
    @{scenario='too_many_tools';status='FAILED';code='MODEL_BUDGET_EXCEEDED';http=1;tools=1},
    @{scenario='second_tools';status='FAILED';code='MODEL_OUTPUT_INVALID';http=2;tools=2},
    @{scenario='provider_unknown';status='FAILED';code='MODEL_UNAVAILABLE';http=1;tools=1},
    @{scenario='retrieval_retry';status='SUCCEEDED';code=$null;http=1;tools=2}
)
$report.branchMatrix=@()
$collection="$($env:COPILOT_QDRANT_COLLECTION_PREFIX)-$($env:E2E_INDEX_VERSION)"
foreach($branch in $branches){
    Reset-AgentFixtureQuota
    Invoke-RestMethod 'http://127.0.0.1:18765/_scenario' -Method Post -ContentType 'application/json' -Body (@{scenario=$branch.scenario}|ConvertTo-Json)|Out-Null
    $points=$null
    try {
        if($branch.scenario -eq 'retrieval_retry'){
            $scrolled=Invoke-RestMethod "http://127.0.0.1:6333/collections/$collection/points/scroll" -Method Post -ContentType 'application/json' -Body '{"limit":16,"with_payload":true,"with_vector":true}'
            $points=@($scrolled.result.points|ForEach-Object{@{id=$_.id;vector=$_.vector;payload=$_.payload}})
            Invoke-RestMethod "http://127.0.0.1:6333/collections/$collection/points/delete?wait=true" -Method Post -ContentType 'application/json' -Body (@{points=@($points|ForEach-Object{$_.id})}|ConvertTo-Json -Depth 10)|Out-Null
        }
        $httpBefore=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
        $created=Invoke-RestMethod $url -Method Post -Headers @{Authorization=$headers.Authorization;'Idempotency-Key'="branch-$runId-$($branch.scenario)"} -ContentType 'application/json' -Body $body
        $analysisId=$created.analysisId;$page=Continue-AgentIfWaiting
        $record=Agent-Evidence $branch.scenario $httpBefore;$report.branchMatrix+=$record
        Check "Graph branch $($branch.scenario) matches status/calls/slots and preserves Core" ($record.status -eq $branch.status -and $record.failureCode -eq $branch.code -and $record.actualHttpCalls -eq $branch.http -and [int]$record.modelClaims -eq $branch.http -and [int]$record.toolReceipts -eq $branch.tools -and $record.coreChecksum -eq $fingerprintBefore)
        if($branch.scenario -eq 'provider_unknown'){Check 'Unknown provider response keeps its immutable hold' $record.calls.Contains('UNKNOWN_HELD')}
        $stream=(Invoke-WebRequest "$url/$analysisId/events" -Headers $headers -TimeoutSec 15).Content
        $sequences=@([regex]::Matches($stream,'(?m)^id:(\d+)')|ForEach-Object{$_.Groups[1].Value})
        $cursor=$sequences[1];$reconnected=(Invoke-WebRequest "$url/$analysisId/events" -Headers @{Authorization=$headers.Authorization;'Last-Event-ID'=$cursor} -TimeoutSec 15).Content
        $later=@([regex]::Matches($reconnected,'(?m)^id:(\d+)')|ForEach-Object{$_.Groups[1].Value})
        Check "Graph branch $($branch.scenario) SSE reconnect exactly replays committed events" (($sequences -join ',') -eq $record.events -and ($later -join ',') -eq (($sequences|Select-Object -Skip 2)-join ','))
    } finally {
        if($null -ne $points){Invoke-RestMethod "http://127.0.0.1:6333/collections/$collection/points?wait=true" -Method Put -ContentType 'application/json' -Body (@{points=$points}|ConvertTo-Json -Depth 20)|Out-Null}
    }
}
Invoke-RestMethod 'http://127.0.0.1:18765/_scenario' -Method Post -ContentType 'application/json' -Body '{"scenario":"tool_final"}'|Out-Null
