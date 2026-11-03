[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9]{14}$')][string]$RunId,
    [Parameter(Mandatory)][guid]$BrowserAnalysisId,
    [string]$MysqlContainer='finguard-mysql'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$output=Join-Path $root "target/business-e2e/$RunId"
$seed=Get-Content -LiteralPath (Join-Path $output 'browser-session.json') -Raw | ConvertFrom-Json
if ($seed.username -cne 'e2e-reviewer' -or $seed.runId -ne $RunId) {throw 'Only matching local synthetic browser fixtures are supported'}
$coreDb="fg_core_e2e_$RunId"; $copilotDb="fg_copilot_e2e_$RunId"; $redis="fg-e2e-redis-$RunId"
$url='http://127.0.0.1:8081/api/copilot/review-analyses'
$checks=@();$stopped=$false
function Check([string]$name,[bool]$passed) {
    $script:checks+=@{name=$name;passed=$passed};if(-not $passed){throw "Acceptance failed: $name"};Write-Host "PASS $name"
}
function Sql([string]$query) {
    $result=$query | & docker exec -i $MysqlContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'
    if($LASTEXITCODE -ne 0){throw 'MySQL acceptance query failed'};return ($result -join "`n")
}
$report=[ordered]@{runId=$RunId;browserAnalysisId=$BrowserAnalysisId.ToString();fixture=$true;paidCalls=0;status='RUNNING'}
$client=$null;$response=$null
try {
    $identity=Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body (@{username=$seed.username;password=$seed.password}|ConvertTo-Json)
    $headers=@{Authorization="Bearer $($identity.accessToken)"}
    $page=Invoke-RestMethod "$url/$BrowserAnalysisId/view" -Headers $headers
    Check 'Browser-created analysis succeeded and immutable UNSURE feedback reached MySQL' ($page.status -eq 'SUCCEEDED' -and $page.feedbackVerdict -eq 'UNSURE' -and
        (Sql "SELECT COUNT(*) FROM $copilotDb.review_feedback WHERE analysis_id=UNHEX(REPLACE('$BrowserAnalysisId','-','')) AND verdict='UNSURE';") -eq '1')
    $coreState=Sql "SELECT CONCAT(status,':',version,':',COALESCE(reviewed_by,0),':',COALESCE(decision_note,'')) FROM $coreDb.review_tasks WHERE id=1;"
    Check 'Browser workflow left the Core review task pending at version zero' ($coreState -eq 'PENDING:0:0:')
    $before=Sql "CHECKSUM TABLE $coreDb.accounts,$coreDb.transactions,$coreDb.import_jobs,$coreDb.reconciliation_jobs,$coreDb.reconciliation_results,$coreDb.risk_hits,$coreDb.review_tasks,$coreDb.audit_logs;"
    $foreign=[guid]::NewGuid().ToString();$pending=[guid]::NewGuid().ToString()
    $null=Sql @"
INSERT INTO $copilotDb.analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash)
VALUES(UNHEX(REPLACE('$foreign','-','')),'GENERATING','other-synthetic-reviewer',1,0,'foreign-$foreign',UNHEX(REPEAT('0',64))),
(UNHEX(REPLACE('$pending','-','')),'GENERATING','e2e-reviewer',1,0,'fault-$pending',UNHEX(REPEAT('0',64)));
INSERT INTO $copilotDb.analysis_event_cursor VALUES(UNHEX(REPLACE('$pending','-','')),1);
INSERT INTO $copilotDb.analysis_event VALUES(UNHEX(REPLACE('$pending','-','')),1,'STATE_CHANGED','GENERATING',NULL,NOW(6));
"@
    foreach($suffix in @('view','events')) {
        $status=(Invoke-WebRequest "$url/$foreign/$suffix" -Headers $headers -SkipHttpErrorCheck -TimeoutSec 10).StatusCode
        Check "Cross-owner $suffix read is hidden with 404" ($status -eq 404)
    }
    Check 'Only the run-owned isolated Redis instance is selected for fault injection' ((& docker inspect --format '{{.Name}}' $redis) -eq "/$redis")
    & docker stop $redis | Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not stop isolated Redis'};$stopped=$true
    $resume=@{Authorization=$headers.Authorization;'Last-Event-ID'='2'}
    $text=(Invoke-WebRequest "$url/$BrowserAnalysisId/events" -Headers $resume -TimeoutSec 15).Content
    $ids=@([regex]::Matches($text,'(?m)^id:(\d+)') | ForEach-Object {$_.Groups[1].Value})
    Check 'With Redis stopped, terminal SSE replay resumes from MySQL at sequence three' (($ids -join ',') -eq '3,4,5,6')
    # Open a nonterminal stream, then commit synthetic state/events while Redis is down.
    # This is fault injection, not an extra model or Worker run.
    $handler=[Net.Http.HttpClientHandler]::new();$handler.UseProxy=$false
    $client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(20)
    $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$identity.accessToken)
    $response=$client.GetAsync("$url/$pending/events",[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    Check 'Nonterminal SSE opens while Redis is unavailable' ([int]$response.StatusCode -eq 200)
    $null=Sql @"
START TRANSACTION;
UPDATE $copilotDb.analysis_run SET status='FAILED',failure_code='MODEL_UNAVAILABLE' WHERE id=UNHEX(REPLACE('$pending','-',''));
UPDATE $copilotDb.analysis_event_cursor SET last_sequence=3 WHERE analysis_id=UNHEX(REPLACE('$pending','-',''));
INSERT INTO $copilotDb.analysis_event VALUES
(UNHEX(REPLACE('$pending','-','')),2,'STATE_CHANGED','VALIDATING',NULL,NOW(6)),
(UNHEX(REPLACE('$pending','-','')),3,'STATE_CHANGED','FAILED','MODEL_UNAVAILABLE',NOW(6));
COMMIT;
"@
    $live=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $liveIds=@([regex]::Matches($live,'(?m)^id:(\d+)') | ForEach-Object {$_.Groups[1].Value})
    Check 'A live stream polls newly committed MySQL events in order without Redis wakeups' (($liveIds -join ',') -eq '1,2,3' -and $live.Contains('MODEL_UNAVAILABLE'))
    $failed=Invoke-RestMethod "$url/$pending/view" -Headers $headers
    Check 'Failed analysis exposes a controlled error and no model body or fabricated zero cost' ($failed.status -eq 'FAILED' -and $null -eq $failed.result -and $null -eq $failed.cost.estimatedCost)
    Check 'Fault injection and replay did not modify Core business tables' ($before -eq (Sql "CHECKSUM TABLE $coreDb.accounts,$coreDb.transactions,$coreDb.import_jobs,$coreDb.reconciliation_jobs,$coreDb.reconciliation_results,$coreDb.risk_hits,$coreDb.review_tasks,$coreDb.audit_logs;"))
    $report.resumedSequences=$ids;$report.liveSequences=$liveIds;$report.failedAnalysisId=$pending;$report.foreignAnalysisId=$foreign
    $report.coreTaskState=$coreState;$report.fixtureHttpCalls=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
    $report.status='PASSED'
} finally {
    if($response){$response.Dispose()};if($client){$client.Dispose()}
    if($stopped){& docker start $redis | Out-Null}
    $report.checks=$checks;$report.finishedAt=[DateTimeOffset]::Now.ToString('o')
    if($report.status -eq 'RUNNING'){$report.status='FAILED'}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'review-console-report.json') -Encoding utf8
}
