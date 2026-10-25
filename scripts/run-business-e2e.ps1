[CmdletBinding()]
param(
    [string]$CoreRoot = 'C:\dev\worktrees\finguard-core-phase1',
    [string]$MysqlContainer = 'finguard-mysql',
    [string]$RabbitContainer = 'finguard-rabbitmq',
    [string]$RedisContainer = 'finguard-redis',
    [ValidateSet('deepseek','qwen','zhipu')][string]$Provider,
    [string]$ModelId,
    [string]$EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'),
    [switch]$PlanOnly,
    [switch]$KeepRunning
)
$ErrorActionPreference = 'Stop'
$initialEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $initialEnvironment[$_.Name] = $_.Value }
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile
$credentialProvider = $env:COPILOT_MODEL_PROVIDER
if (-not $Provider) { $Provider = if ($credentialProvider) { $credentialProvider.ToLowerInvariant() } else { 'deepseek' } }
if ($credentialProvider -and $Provider -ne $credentialProvider.ToLowerInvariant()) {
    throw 'Requested provider does not match the configured credential provider; supply matching environment configuration.'
}
$providerBaseUrls = @{
    deepseek='https://api.deepseek.com'
    qwen='https://dashscope.aliyuncs.com/compatible-mode/v1'
    zhipu='https://open.bigmodel.cn/api/paas/v4'
}
if (-not $providerBaseUrls.ContainsKey($Provider)) { throw 'Unsupported credential provider.' }
$modelBaseUrl = $providerBaseUrls[$Provider]
if ($env:COPILOT_MODEL_BASE_URL -and $env:COPILOT_MODEL_BASE_URL.TrimEnd('/') -ne $modelBaseUrl) {
    throw 'Configured model base URL does not match the provider allowlist.'
}
if (-not $ModelId) {
    $ModelId = if ($env:COPILOT_MODEL_ID) { $env:COPILOT_MODEL_ID } else {
        @{deepseek='deepseek-flash';qwen='qwen-plus';zhipu='glm-5.2'}[$Provider]
    }
}
$profile = "real-$Provider"
$promptVersion = if ($env:COPILOT_PROMPT_VERSION) { $env:COPILOT_PROMPT_VERSION } else { 'review-analysis-v2' }
if ($PlanOnly) {
    @{provider=$Provider;model=$ModelId;profile=$profile;baseUrl=$modelBaseUrl;
      chatCompletionsPath='/chat/completions';prompt=$promptVersion;maxModelCalls=1;
      coreRoot=$CoreRoot;scope='isolated synthetic business E2E, fixed embeddings; not semantic quality'} | ConvertTo-Json -Compress
    foreach ($entry in @(Get-ChildItem Env:)) {
        if (-not $initialEnvironment.ContainsKey($entry.Name)) { [Environment]::SetEnvironmentVariable($entry.Name,$null,'Process') }
    }
    foreach ($name in $initialEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$initialEnvironment[$name],'Process') }
    return
}
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$coreDb = "fg_core_e2e_$runId"
$copilotDb = "fg_copilot_e2e_$runId"
$vhost = "fg-e2e-$runId"
$qdrant = "fg-e2e-qdrant-$runId"
$output = Join-Path $root "target\business-e2e\$runId"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$reportPath = Join-Path $output 'report.json'
$report = [ordered]@{ runId=$runId; startedAt=[DateTimeOffset]::Now.ToString('o'); status='RUNNING';
    coreDatabase=$coreDb; copilotDatabase=$copilotDb; rabbitVhost=$vhost;
    qdrantContainer=$qdrant; provider=$Provider; model=$ModelId; prompt=$promptVersion;
    maxModelCalls=1; embedding='fixed-v1'; checks=@() }
$processes = @()
$qdrantStarted = $false
function Check([string]$Name, [bool]$Passed) {
    $script:report.checks += @{name=$Name; passed=$Passed}
    if (-not $Passed) { throw "Acceptance check failed: $Name" }
    Write-Host "PASS $Name"
}
function Container-Environment([string]$Name) {
    $values = & docker inspect --format '{{json .Config.Env}}' $Name | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect container $Name" }
    $map = @{}
    foreach ($line in $values) { $parts = $line -split '=',2; $map[$parts[0]]=$parts[1] }
    return $map
}
function Sql([string]$Query) {
    $result = $Query | & docker exec -i $MysqlContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) { throw 'MySQL acceptance query failed' }
    return ($result -join "`n")
}
function Health([string]$Url, [Diagnostics.Process]$Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw 'Service exited during startup; inspect the run-specific service log' }
        try { if ((Invoke-RestMethod "$Url/actuator/health" -TimeoutSec 3).status -eq 'UP') { return } } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Health check timed out: $Url"
}
function Start-Java([string]$Name, [string]$Directory, [string]$Jar) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { $java = (Get-Command java.exe).Source }
    $process = Start-Process -FilePath $java -ArgumentList @('-Xmx384m','-jar',$Jar) -WorkingDirectory $Directory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output "$Name.log") `
        -RedirectStandardError (Join-Path $output "$Name.stderr.log")
    return $process
}
function Http-Status([string]$Method,[string]$Url,[hashtable]$Headers,[string]$Body) {
    try {
        $parameters = @{UseBasicParsing=$true;Method=$Method;Uri=$Url;Headers=$Headers;TimeoutSec=10}
        if ($Body) { $parameters.Body=$Body; $parameters.ContentType='application/json' }
        return [int](Invoke-WebRequest @parameters).StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}
try {
    if (-not $env:COPILOT_MODEL_API_KEY) { throw 'Set COPILOT_MODEL_API_KEY in the current process before running' }
    foreach ($port in @(8080,8081,6333)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already occupied" }
    }
    $mysql = Container-Environment $MysqlContainer
    $rabbit = Container-Environment $RabbitContainer
    $redis = Container-Environment $RedisContainer
    $dbUser = $mysql.MYSQL_USER
    if ($dbUser -notmatch '^[A-Za-z0-9_]+$') { throw 'Unexpected MySQL username' }
    $null = Sql "CREATE DATABASE $coreDb; CREATE DATABASE $copilotDb; GRANT ALL ON $coreDb.* TO '$dbUser'@'%'; GRANT ALL ON $copilotDb.* TO '$dbUser'@'%';"
    & docker exec $RabbitContainer rabbitmqctl add_vhost $vhost | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ vhost creation failed' }
    & docker exec $RabbitContainer rabbitmqctl set_permissions -p $vhost $rabbit.RABBITMQ_DEFAULT_USER '.*' '.*' '.*' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ permissions failed' }
    & docker run -d --name $qdrant -p '127.0.0.1:6333:6333' qdrant/qdrant:v1.15.5 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Qdrant start failed' }
    $qdrantStarted = $true
    $env:MYSQL_DATABASE=$coreDb; $env:MYSQL_USER=$dbUser; $env:MYSQL_PASSWORD=$mysql.MYSQL_PASSWORD
    $env:MYSQL_HOST='127.0.0.1'; $env:MYSQL_PORT='3306'
    $env:RABBITMQ_HOST='127.0.0.1'; $env:RABBITMQ_PORT='5672'
    $env:RABBITMQ_USERNAME=$rabbit.RABBITMQ_DEFAULT_USER; $env:RABBITMQ_PASSWORD=$rabbit.RABBITMQ_DEFAULT_PASS
    $env:RABBITMQ_VIRTUAL_HOST=$vhost
    $env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT='6380'; $env:REDIS_PASSWORD=$redis.REDIS_PASSWORD
    $random = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
    $env:JWT_SECRET_BASE64=[Convert]::ToBase64String($random)
    $env:FINGUARD_AUTH_BOOTSTRAP_ENABLED='true'
    $env:FINGUARD_AUTH_BOOTSTRAP_ADMIN_USERNAME='e2e-admin'
    $env:FINGUARD_AUTH_BOOTSTRAP_ADMIN_PASSWORD=[guid]::NewGuid().ToString('N')+'Aa1!'
    $env:FINGUARD_AUTH_BOOTSTRAP_REVIEWER_USERNAME='e2e-reviewer'
    $env:FINGUARD_AUTH_BOOTSTRAP_REVIEWER_PASSWORD=[guid]::NewGuid().ToString('N')+'Aa1!'
    $env:SERVER_ADDRESS='127.0.0.1'; $env:SERVER_PORT='8080'
    Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
    $core = Start-Java 'core' $CoreRoot (Join-Path $CoreRoot 'target\finguard-core-0.0.1-SNAPSHOT.jar')
    $processes += $core; Health 'http://127.0.0.1:8080' $core
    Check 'Core health UP with real MySQL/RabbitMQ/Redis' $true
    $env:SERVER_PORT='8081'; $env:SPRING_PROFILES_ACTIVE=$profile
    $env:COPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/$copilotDb"+'?sslMode=DISABLED&allowPublicKeyRetrieval=true'
    $env:COPILOT_DB_USERNAME=$dbUser; $env:COPILOT_DB_PASSWORD=$mysql.MYSQL_PASSWORD
    $env:SPRING_RABBITMQ_HOST='127.0.0.1'; $env:SPRING_RABBITMQ_PORT='5672'
    $env:SPRING_RABBITMQ_USERNAME=$rabbit.RABBITMQ_DEFAULT_USER
    $env:SPRING_RABBITMQ_PASSWORD=$rabbit.RABBITMQ_DEFAULT_PASS; $env:SPRING_RABBITMQ_VIRTUAL_HOST=$vhost
    $env:COPILOT_MODEL_PROVIDER=$Provider; $env:COPILOT_MODEL_ID=$ModelId
    $env:COPILOT_MODEL_BASE_URL=$modelBaseUrl; $env:COPILOT_PROMPT_VERSION=$promptVersion
    $env:COPILOT_MODEL_CHAT_COMPLETIONS_PATH='/chat/completions'
    $env:COPILOT_MODEL_TIMEOUT='60s'; $env:COPILOT_MAX_MODEL_CALLS='1'
    $env:COPILOT_PRICE_TABLE_VERSION='e2e-unpriced-v1'
    $env:FINGUARD_CORE_BASE_URL='http://127.0.0.1:8080'
    $env:COPILOT_QDRANT_ENDPOINT='http://127.0.0.1:6333'
    $env:COPILOT_QDRANT_COLLECTION_PREFIX="e2e-$runId"
    $env:COPILOT_EMBEDDING_VERSION='fixed-v1'; $env:E2E_INDEX_VERSION="published-$runId"
    $copilot = Start-Java 'copilot' $root (Join-Path $root 'target\finguard-copilot-0.0.1-SNAPSHOT.jar')
    $processes += $copilot; Health 'http://127.0.0.1:8081' $copilot
    Check "Copilot $profile profile health UP" $true
    $report.corePid=$core.Id; $report.copilotPid=$copilot.Id
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $classpath = (Join-Path $root 'target\classes')+';'+(Get-Content -Raw (Join-Path $root 'target\e2e-classpath.txt')).Trim()
    $seed = & $java --class-path $classpath (Join-Path $PSScriptRoot 'e2e\PublishedKnowledgeSeed.java') 2> (Join-Path $output 'knowledge-seed.stderr.log')
    if ($LASTEXITCODE -ne 0) { throw 'Knowledge build/evaluation/publication failed; inspect knowledge-seed.stderr.log' }
    $report.knowledge = ($seed | Select-Object -Last 1) | ConvertFrom-Json
    Check 'Published knowledge retrieved from Qdrant and checksum-verified MySQL source' ($report.knowledge.status -eq 'PUBLISHED')
    $null = Sql @"
USE $coreDb;
INSERT INTO accounts(account_no,account_name,account_type) VALUES('E2E-SYNTHETIC','Synthetic acceptance account','BANK');
SET @account=LAST_INSERT_ID();
INSERT INTO import_jobs(original_file_name,file_hash,file_size_bytes,status,total_rows,success_rows,created_by)
 VALUES('synthetic.csv',REPEAT('a',64),100,'SUCCESS',1,1,(SELECT id FROM users WHERE username='e2e-admin'));
SET @import=LAST_INSERT_ID();
INSERT INTO transactions(account_id,import_job_id,external_transaction_no,direction,amount,transaction_time,source)
 VALUES(@account,@import,'E2E-SYNTHETIC-TX','EXPENSE',120000.10,'2026-10-19 10:00:00','CSV_IMPORT');
SET @tx=LAST_INSERT_ID();
INSERT INTO reconciliation_jobs(import_job_id,status,total_count,unmatched_count,created_by,started_at,finished_at)
 VALUES(@import,'COMPLETED',1,1,(SELECT id FROM users WHERE username='e2e-admin'),NOW(),NOW());
SET @job=LAST_INSERT_ID();
INSERT INTO reconciliation_results(reconciliation_job_id,csv_transaction_id,result_type,match_method,reason_code)
 VALUES(@job,@tx,'UNMATCHED','NONE','NO_CANDIDATE');
SET @result=LAST_INSERT_ID();
INSERT INTO risk_hits(reconciliation_result_id,rule_code,reason_code,observed_amount,threshold_amount,reason_summary)
 VALUES(@result,'LARGE_AMOUNT','AMOUNT_AT_OR_ABOVE_THRESHOLD',120000.10,100000.00,'Synthetic threshold reached');
INSERT INTO review_tasks(source_type,risk_hit_id) VALUES('RISK_HIT',LAST_INSERT_ID());
"@
    $taskId = [long](Sql "SELECT id FROM $coreDb.review_tasks LIMIT 1;")
    $before = Sql "SELECT CONCAT(status,':',version,':',COALESCE(reviewed_by,0),':',COALESCE(decision_note,'')) FROM $coreDb.review_tasks WHERE id=$taskId;"
    $coreTables = @('accounts','transactions','import_jobs','reconciliation_jobs','reconciliation_results','risk_hits','review_tasks','audit_logs') | ForEach-Object { "$coreDb.$_" }
    $fingerprintBefore = Sql ('CHECKSUM TABLE '+($coreTables -join ','))
    $reviewer = Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body (@{username='e2e-reviewer';password=$env:FINGUARD_AUTH_BOOTSTRAP_REVIEWER_PASSWORD}|ConvertTo-Json)
    $admin = Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body (@{username='e2e-admin';password=$env:FINGUARD_AUTH_BOOTSTRAP_ADMIN_PASSWORD}|ConvertTo-Json)
    $headers = @{Authorization="Bearer $($reviewer.accessToken)"; 'Idempotency-Key'="e2e-$runId"}
    $adminHeaders = @{Authorization="Bearer $($admin.accessToken)"; 'Idempotency-Key'="e2e-admin-$runId"}
    $identity = Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/me' -Headers $headers
    Check 'Reviewer JWT authenticated by Core' ($identity.roles -contains 'REVIEWER')
    $context = Invoke-RestMethod "http://127.0.0.1:8080/api/review-tasks/$taskId/context" -Headers $headers
    Check 'Core supplies actual sanitized task context' ($context.transaction.amount -eq 120000.10)
    $url = 'http://127.0.0.1:8081/api/copilot/review-analyses'
    $body = @{reviewTaskId=$taskId}|ConvertTo-Json
    Check 'Unauthenticated Copilot request rejected with 401' ((Http-Status 'POST' $url @{} $body) -eq 401)
    Check 'ADMIN without REVIEWER rejected with 403' ((Http-Status 'POST' $url $adminHeaders $body) -eq 403)
    $accepted = Invoke-WebRequest $url -UseBasicParsing -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    Check 'Analysis accepted through authenticated HTTP with 202' ($accepted.StatusCode -eq 202)
    $analysis = $accepted.Content|ConvertFrom-Json; $analysisId=$analysis.analysisId; $report.analysisId=$analysisId
    $replayed = Invoke-RestMethod $url -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    Check 'Identical Idempotency-Key returns the same analysis' ($replayed.analysisId -eq $analysisId)
    $deadline=[DateTime]::UtcNow.AddSeconds(100)
    do {
        Start-Sleep -Seconds 1
        $result=Invoke-RestMethod "$url/$analysisId" -Headers $headers
    } while ($result.status -notin @('SUCCEEDED','FAILED') -and [DateTime]::UtcNow -lt $deadline)
    $report.analysisStatus=$result.status; $report.failureCode=$result.failureCode
    $report.modelUsage = Sql "SELECT outcome,input_tokens,output_tokens,latency_ms FROM $copilotDb.model_usage_ledger;"
    Check 'Analysis reaches SUCCEEDED through Outbox/RabbitMQ/Worker/real model' ($result.status -eq 'SUCCEEDED')
    Check 'Model result contains the published policy citation' ($result.result.policyCitations.documentId -contains 'e2e-large-amount-policy')
    Check 'Core business decision remains PENDING and version unchanged' ((Sql "SELECT CONCAT(status,':',version,':',COALESCE(reviewed_by,0),':',COALESCE(decision_note,'')) FROM $coreDb.review_tasks WHERE id=$taskId;") -eq $before)
    Check 'Core business and audit table checksums unchanged' ((Sql ('CHECKSUM TABLE '+($coreTables -join ','))) -eq $fingerprintBefore)
    Check 'Exactly one model invocation ledger row persisted' ((Sql "SELECT COUNT(*) FROM $copilotDb.model_usage_ledger;") -eq '1')
    Check 'Outbox published and Worker receipt persisted' ((Sql "SELECT COUNT(*) FROM $copilotDb.outbox_event o JOIN $copilotDb.analysis_message_receipt r ON r.event_id=o.id WHERE o.published_at IS NOT NULL;") -eq '1')
    $report.recommendation=$result.result.recommendation
    $report.citationCount=@($result.result.policyCitations).Count
    $report.status='PASSED'
} catch {
    $report.status='FAILED'
    $report.error=$_.Exception.Message
    Write-Host "FAILED $($report.error)"
} finally {
    if (-not $KeepRunning) {
        foreach ($process in $processes) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } }
        if ($qdrantStarted) { & docker stop $qdrant 2>$null | Out-Null }
        $report.servicesLeftRunning=$false
    } else { $report.servicesLeftRunning=$true }
    $report.finishedAt=[DateTimeOffset]::Now.ToString('o')
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host "REPORT $reportPath"
    Write-Host "Databases and RabbitMQ vhost retained: $coreDb / $copilotDb / $vhost"
    foreach ($entry in @(Get-ChildItem Env:)) {
        if (-not $initialEnvironment.ContainsKey($entry.Name)) {
            [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
        }
    }
    foreach ($name in $initialEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $initialEnvironment[$name], 'Process')
    }
}
if ($report.status -ne 'PASSED') { exit 1 }
