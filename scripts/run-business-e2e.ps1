[CmdletBinding()]
param(
    [string]$CoreRoot = 'C:\dev\worktrees\finguard-core-phase1',
    [string]$MysqlContainer = 'finguard-mysql',
    [string]$RabbitContainer = 'finguard-rabbitmq',
    [string]$RedisContainer = 'finguard-redis',
    [ValidateSet('deepseek','qwen','zhipu')][string]$Provider,
    [string]$ModelId,
    [string]$EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'),
    [ValidateRange(0.00000001,10)][decimal]$SmokeBudgetCny = 0.10,
    [Nullable[decimal]]$InputPricePerMillionCny,
    [Nullable[decimal]]$OutputPricePerMillionCny,
    [string]$PriceTableVersion,
    [switch]$LocalModelStub,
    [switch]$AgentAcceptance,
    [switch]$AgentFaultMatrix,
    [switch]$McpAcceptance,
    [switch]$AgentBranchMatrix,
    [switch]$AgentInfrastructureMatrix,
    [switch]$IsolatedRedis,
    [switch]$ExpectPolicyRejection,
    [switch]$ExpectFactRejection,
    [switch]$ExpectMissingPolicy,
    [switch]$ExpectBudgetRejection,
    [switch]$ExpectRedisFailure,
    [switch]$PlanOnly,
    [switch]$BrowserAcceptance,
    [switch]$KeepRunning,
    [switch]$SemanticEmbedding,
    [string]$EmbeddingEndpoint='http://127.0.0.1:8766',
    [string]$EmbeddingVersion,
    [string]$EmbeddingManifestSha256
)
$ErrorActionPreference = 'Stop'
$initialEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $initialEnvironment[$_.Name] = $_.Value }
$root = Split-Path -Parent $PSScriptRoot
if($SemanticEmbedding){
    $embeddingUri=[Uri]$EmbeddingEndpoint
    if($embeddingUri.Scheme -ne 'http' -or $embeddingUri.Host -ne '127.0.0.1' -or $embeddingUri.Port -lt 1 -or $embeddingUri.UserInfo -or $embeddingUri.Query -or $embeddingUri.Fragment -or $embeddingUri.AbsolutePath -ne '/' -or -not $EmbeddingVersion -or $EmbeddingManifestSha256 -notmatch '^[a-f0-9]{64}$'){throw 'Pinned loopback semantic embedding configuration required'}
}
if ($AgentFaultMatrix) { $AgentAcceptance=$true }
if ($McpAcceptance) { $AgentAcceptance=$true }
if ($AgentBranchMatrix -or $AgentInfrastructureMatrix) { $AgentAcceptance=$true }
if ($AgentAcceptance -and -not $LocalModelStub) { throw 'AgentAcceptance requires LocalModelStub; no paid native provider qualification is authorized' }
if ($BrowserAcceptance -and (-not $LocalModelStub -or -not $KeepRunning)) { throw 'BrowserAcceptance requires LocalModelStub and KeepRunning; synthetic local credentials only' }
if ($BrowserAcceptance -and -not (Test-Path -LiteralPath (Join-Path $CoreRoot 'src/main/resources/static/demo/index.html'))) {
    throw 'BrowserAcceptance requires a Core checkout with the review console; specify -CoreRoot C:\dev\finguard-core'
}
if (-not $LocalModelStub) { . (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile }
if ($LocalModelStub) {
    $env:COPILOT_MODEL_PROVIDER='deepseek'; $env:COPILOT_MODEL_API_KEY='local-test-only'
    $env:COPILOT_MODEL_BASE_URL='http://127.0.0.1:18765'; $env:COPILOT_MODEL_ID='local-test-stub'
    $Provider='deepseek'; $ModelId='local-test-stub'
    $env:P4_STUB_RECOMMENDATION=if ($ExpectPolicyRejection) { 'IGNORED' } else { 'CONFIRMED' }
}
if (($ExpectPolicyRejection -or $ExpectFactRejection -or $ExpectMissingPolicy -or $ExpectBudgetRejection -or $ExpectRedisFailure) -and -not $LocalModelStub) { throw 'Rejection scenarios require LocalModelStub' }
if ((@($ExpectPolicyRejection,$ExpectFactRejection,$ExpectMissingPolicy,$ExpectBudgetRejection,$ExpectRedisFailure) | Where-Object { $_ }).Count -gt 1) { throw 'Choose one rejection scenario' }
$expectedCalls=if ($ExpectBudgetRejection -or $ExpectRedisFailure) { 0 } else { 1 }
if ($LocalModelStub) { $env:P4_SEED_OTHER_POLICY = if ($ExpectMissingPolicy) { 'true' } else { 'false' } }
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
if ($LocalModelStub) { $modelBaseUrl = 'http://127.0.0.1:18765' }
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
$customPrice = $PSBoundParameters.ContainsKey('InputPricePerMillionCny') -or $PSBoundParameters.ContainsKey('OutputPricePerMillionCny') -or $PSBoundParameters.ContainsKey('PriceTableVersion')
if ($customPrice -and ($null -eq $InputPricePerMillionCny -or $null -eq $OutputPricePerMillionCny -or [string]::IsNullOrWhiteSpace($PriceTableVersion))) {
    throw 'Price override requires both CNY rates and PriceTableVersion'
}
if ($LocalModelStub) {
    $InputPricePerMillionCny=0; $OutputPricePerMillionCny=0; $PriceTableVersion='smoke-fixture-zero-v1'
    if ($ExpectBudgetRejection) { $InputPricePerMillionCny=2; $OutputPricePerMillionCny=8; $PriceTableVersion='smoke-fixture-priced-v1' }
} elseif (-not $customPrice -and $Provider -eq 'deepseek' -and $ModelId -eq 'deepseek-flash') {
    # Official 2026-11-02 peak/cache-miss CNY rates; no tools/search calls in this smoke.
    $InputPricePerMillionCny=2; $OutputPricePerMillionCny=8; $PriceTableVersion='smoke-deepseek-flash-20261102-peak-cny'
}
$estimatedUpperBoundCny=$null
if ($null -ne $InputPricePerMillionCny -and $null -ne $OutputPricePerMillionCny) {
    if ($InputPricePerMillionCny -lt 0 -or $OutputPricePerMillionCny -lt 0 -or
            (-not $LocalModelStub -and ($InputPricePerMillionCny -eq 0 -or $OutputPricePerMillionCny -eq 0))) { throw 'Real model CNY rates must be positive' }
    $estimatedUpperBoundCny=(32000*$InputPricePerMillionCny+2000*$OutputPricePerMillionCny)/1000000
    if ($estimatedUpperBoundCny -gt $SmokeBudgetCny) { throw 'Conservative reservation exceeds smoke budget' }
}
if ($PlanOnly) {
    @{provider=$Provider;model=$ModelId;profile=$profile;baseUrl=$modelBaseUrl;
      chatCompletionsPath='/chat/completions';prompt=$promptVersion;maxModelCalls=1;
      coreRoot=$CoreRoot;currency='CNY';priceTableVersion=$PriceTableVersion;smokeBudgetCny=$SmokeBudgetCny;
      estimatedUpperBoundCny=$estimatedUpperBoundCny;
      fixture=[bool]$LocalModelStub;expectedCalls=$expectedCalls;
      scope=$(if($SemanticEmbedding){'isolated synthetic business E2E, pinned semantic embeddings, one-document policy smoke; not broad retrieval quality'}else{'isolated synthetic business E2E, fixed embeddings; not semantic quality'})} | ConvertTo-Json -Compress
    foreach ($entry in @(Get-ChildItem Env:)) {
        if (-not $initialEnvironment.ContainsKey($entry.Name)) { [Environment]::SetEnvironmentVariable($entry.Name,$null,'Process') }
    }
    foreach ($name in $initialEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$initialEnvironment[$name],'Process') }
    return
}
if ($null -eq $estimatedUpperBoundCny) { throw 'Live smoke requires reviewed CNY price override for this model' }
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$coreDb = "fg_core_e2e_$runId"
$copilotDb = "fg_copilot_e2e_$runId"
$vhost = "fg-e2e-$runId"
$qdrant = "fg-e2e-qdrant-$runId"
$isolatedRedisContainer = "fg-e2e-redis-$runId"
$redisStarted = $false
$output = Join-Path $root "target\business-e2e\$runId"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$reportPath = Join-Path $output 'report.json'
$report = [ordered]@{ runId=$runId; startedAt=[DateTimeOffset]::Now.ToString('o'); status='RUNNING';
    coreDatabase=$coreDb; copilotDatabase=$copilotDb; rabbitVhost=$vhost;
    qdrantContainer=$qdrant; provider=$Provider; model=$ModelId; prompt=$promptVersion;
    maxModelCalls=1; embedding=$(if($SemanticEmbedding){$EmbeddingVersion}else{'fixed-v1'}); embeddingManifestSha256=$EmbeddingManifestSha256; currency='CNY';priceTableVersion=$PriceTableVersion;
    fixture=[bool]$LocalModelStub;expectedCalls=$expectedCalls;
    harnessSha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();
    smokeBudgetCny=$SmokeBudgetCny;estimatedUpperBoundCny=$estimatedUpperBoundCny;
    inputPricePerMillionCny=$InputPricePerMillionCny;outputPricePerMillionCny=$OutputPricePerMillionCny;
    copilotJarSha256=(Get-FileHash (Join-Path $root 'target\finguard-copilot-0.0.1-SNAPSHOT.jar') -Algorithm SHA256).Hash.ToLowerInvariant();
    coreJarSha256=(Get-FileHash (Join-Path $CoreRoot 'target\finguard-core-0.0.1-SNAPSHOT.jar') -Algorithm SHA256).Hash.ToLowerInvariant(); checks=@() }
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
function Health([string]$Url, [Diagnostics.Process]$Process, [string]$ExpectedStatus='UP') {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw 'Service exited during startup; inspect the run-specific service log' }
        try { if ((Invoke-RestMethod "$Url/actuator/health" -TimeoutSec 5 -SkipHttpErrorCheck).status -eq $ExpectedStatus) { return } } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Health check timed out: $Url"
}
function Start-Java([string]$Name, [string]$Directory, [string]$Jar) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { $java = (Get-Command java.exe).Source }
    $process = Start-Process -FilePath $java -ArgumentList @('-Xmx384m','-Djdk.httpclient.disableRetryConnect=true','-jar',$Jar) -WorkingDirectory $Directory `
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
function Start-AmqpProxy([string]$Name){
    $python=(Get-Command python.exe -ErrorAction Stop).Source
    return Start-Process -FilePath $python -ArgumentList @((Join-Path $root 'scripts/e2e/amqp_fixture_proxy.py')) -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output "$Name.log") -RedirectStandardError (Join-Path $output "$Name.stderr.log")
}
try {
    if (-not $env:COPILOT_MODEL_API_KEY) { throw 'Set COPILOT_MODEL_API_KEY in the current process before running' }
    $ports = @(8080,8081,9091,6333); if ($LocalModelStub) { $ports += 18765 }; if ($LocalModelStub -or $IsolatedRedis) { $ports += 16380 }; if($AgentInfrastructureMatrix){$ports+=15679}
    foreach ($port in $ports) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already occupied" }
    }
    if ($LocalModelStub) {
        $python = (Get-Command python.exe -ErrorAction Stop).Source
        $stub = Start-Process -FilePath $python -ArgumentList @((Join-Path $PSScriptRoot 'e2e\local_model_stub.py')) `
            -WorkingDirectory $root -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $output 'stub.log') -RedirectStandardError (Join-Path $output 'stub.stderr.log')
        $processes += $stub
    }
    if ($LocalModelStub -or $IsolatedRedis) {
        & docker run -d --name $isolatedRedisContainer -p '127.0.0.1:16380:6379' -e REDIS_PASSWORD=isolated-test-only redis:8.2.8-alpine redis-server --requirepass isolated-test-only | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Isolated Redis start failed' }
        $redisStarted = $true
        $RedisContainer = $isolatedRedisContainer
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
    # Spring's direct environment properties override Core's legacy RABBITMQ_* placeholders.
    # Bind both applications to this run before starting either JVM, including reused shells.
    $env:SPRING_RABBITMQ_HOST='127.0.0.1'; $env:SPRING_RABBITMQ_PORT='5672'
    $env:SPRING_RABBITMQ_USERNAME=$rabbit.RABBITMQ_DEFAULT_USER
    $env:SPRING_RABBITMQ_PASSWORD=$rabbit.RABBITMQ_DEFAULT_PASS; $env:SPRING_RABBITMQ_VIRTUAL_HOST=$vhost
    Check 'Run-owned RabbitMQ configuration is bound before Core startup' (
        -not [string]::IsNullOrWhiteSpace($env:SPRING_RABBITMQ_USERNAME) -and
        -not [string]::IsNullOrWhiteSpace($env:SPRING_RABBITMQ_PASSWORD) -and $env:SPRING_RABBITMQ_VIRTUAL_HOST -eq $vhost)
    $env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT=if ($LocalModelStub -or $IsolatedRedis) { '16380' } else { '6380' }; $env:REDIS_PASSWORD=$redis.REDIS_PASSWORD
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
    if ($BrowserAcceptance) {
        $entry=Invoke-WebRequest 'http://127.0.0.1:8080/demo/index.html' -TimeoutSec 10 -SkipHttpErrorCheck
        Check 'Configured Core review entry serves an actual HTML console' ($entry.StatusCode -eq 200 -and $entry.Headers['Content-Type'] -like '*text/html*')
        $report.coreCommit=(& git -C $CoreRoot rev-parse HEAD).Trim()
        $report.coreJarSha256=(Get-FileHash (Join-Path $CoreRoot 'target/finguard-core-0.0.1-SNAPSHOT.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $env:SERVER_PORT='8081'; $env:COPILOT_MANAGEMENT_PORT='9091'; $env:COPILOT_MANAGEMENT_ADDRESS='127.0.0.1'; $env:SPRING_PROFILES_ACTIVE=$profile
    if($AgentInfrastructureMatrix){$amqpProxy=Start-AmqpProxy 'amqp-proxy';$processes+=$amqpProxy;$env:SPRING_RABBITMQ_PORT='15679'}
    $env:COPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/$copilotDb"+'?sslMode=DISABLED&allowPublicKeyRetrieval=true'
    $env:COPILOT_DB_USERNAME=$dbUser; $env:COPILOT_DB_PASSWORD=$mysql.MYSQL_PASSWORD
    $env:COPILOT_MODEL_PROVIDER=$Provider; $env:COPILOT_MODEL_ID=$ModelId
    $env:COPILOT_REDIS_HOST=$env:REDIS_HOST; $env:COPILOT_REDIS_PORT=if ($ExpectRedisFailure) { '1' } else { $env:REDIS_PORT }; $env:COPILOT_REDIS_PASSWORD=$env:REDIS_PASSWORD
    $env:COPILOT_GOVERNANCE_FIXTURE_MODE=if ($LocalModelStub) { 'true' } else { 'false' }
    $env:COPILOT_MAX_CALL_CNY=$SmokeBudgetCny.ToString([Globalization.CultureInfo]::InvariantCulture)
    $env:COPILOT_MAX_GLOBAL_DAILY_CNY=if ($ExpectBudgetRejection) { '0.00000001' } else { '10' }
    $env:COPILOT_MODEL_BASE_URL=$modelBaseUrl; $env:COPILOT_PROMPT_VERSION=$promptVersion
    $env:COPILOT_MODEL_CHAT_COMPLETIONS_PATH='/chat/completions'
    $env:COPILOT_MODEL_TIMEOUT='60s'; $env:COPILOT_MAX_MODEL_CALLS='1'
    $env:COPILOT_MAX_INPUT_TOKENS='32000'; $env:COPILOT_MAX_OUTPUT_TOKENS='2000'
    $env:COPILOT_MODEL_INPUT_TOKEN_LIMIT='32000'; $env:COPILOT_MODEL_OUTPUT_TOKEN_LIMIT='2000'
    $env:COPILOT_PRICE_TABLE_VERSION=$PriceTableVersion; $env:COPILOT_MODEL_CURRENCY='CNY'
    $env:COPILOT_MODEL_INPUT_PRICE_PER_MILLION=$InputPricePerMillionCny.ToString([Globalization.CultureInfo]::InvariantCulture)
    $env:COPILOT_MODEL_OUTPUT_PRICE_PER_MILLION=$OutputPricePerMillionCny.ToString([Globalization.CultureInfo]::InvariantCulture)
    $env:FINGUARD_CORE_BASE_URL='http://127.0.0.1:8080'
    $env:COPILOT_QDRANT_ENDPOINT='http://127.0.0.1:6333'
    $env:COPILOT_QDRANT_COLLECTION_PREFIX="e2e-$runId"
    if($SemanticEmbedding){
        $env:COPILOT_EMBEDDING_MODE='semantic';$env:COPILOT_EMBEDDING_DIMENSIONS='384'
        $env:COPILOT_EMBEDDING_ENDPOINT=$EmbeddingEndpoint;$env:COPILOT_EMBEDDING_VERSION=$EmbeddingVersion
        $env:COPILOT_EMBEDDING_MANIFEST_SHA256=$EmbeddingManifestSha256
    }else{$env:COPILOT_EMBEDDING_MODE='fixed';$env:COPILOT_EMBEDDING_DIMENSIONS='8';$env:COPILOT_EMBEDDING_VERSION='fixed-v1'}
    $env:COPILOT_RETRIEVAL_MINIMUM_SCORE='-1.0' # Single-document process smoke; multi-document thresholds are evaluated separately.
    $env:E2E_INDEX_VERSION="published-$runId"
    if ($AgentAcceptance) {
        $env:COPILOT_AGENT_ENABLED='true'; $env:COPILOT_AGENT_RUNTIME_ENABLED='true'
        $env:COPILOT_AGENT_MAX_ANALYSIS_CNY='0.10'; $env:COPILOT_AGENT_HUMAN_GATE='true'
    }
    $copilot = Start-Java 'copilot' $root (Join-Path $root 'target\finguard-copilot-0.0.1-SNAPSHOT.jar')
    $expectedHealth=if ($ExpectRedisFailure) { 'DOWN' } else { 'UP' }
    $processes += $copilot; Health 'http://127.0.0.1:9091' $copilot $expectedHealth
    Check "Copilot $profile profile health $expectedHealth" $true
    $report.flywayVersion=Sql "SELECT version FROM $copilotDb.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;"
    $requiredVersions = @(Get-ChildItem -LiteralPath (Join-Path $root 'src/main/resources/db/migration') -Filter 'V*__*.sql' |
        ForEach-Object { if ($_.Name -match '^V([0-9]+)__') { $Matches[1] } })
    $appliedVersions = @((Sql "SELECT version FROM $copilotDb.flyway_schema_history WHERE success=1 ORDER BY installed_rank;") -split "`n" | ForEach-Object { $_.Trim() })
    $report.flywayVersions=$appliedVersions
    Check 'All current migrations, events, trace linkage, governance and exact JSON text schema are active' (@($requiredVersions | Where-Object { $_ -notin $appliedVersions }).Count -eq 0 -and
        (Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$copilotDb' AND table_name IN ('analysis_input_snapshot','analysis_result') AND column_name IN ('facts_json','result_json') AND data_type='longtext';") -eq '2')
    $report.corePid=$core.Id; $report.copilotPid=$copilot.Id
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $classpath = (Join-Path $root 'target\classes')+';'+(Get-Content -Raw (Join-Path $root 'target\e2e-classpath.txt')).Trim()
    $seed = & $java --class-path $classpath (Join-Path $PSScriptRoot 'e2e\PublishedKnowledgeSeed.java') 2> (Join-Path $output 'knowledge-seed.stderr.log')
    if ($LASTEXITCODE -ne 0) { throw 'Knowledge build/evaluation/publication failed; inspect knowledge-seed.stderr.log' }
    $report.knowledge = ($seed | Select-Object -Last 1) | ConvertFrom-Json
    Check 'Published knowledge retrieved from Qdrant and checksum-verified MySQL source' ($report.knowledge.status -eq 'PUBLISHED')
    $observedAmount = if ($ExpectFactRejection) { '110000.10' } else { '120000.10' }
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
 VALUES(@result,'LARGE_AMOUNT','AMOUNT_AT_OR_ABOVE_THRESHOLD',$observedAmount,100000.00,'Synthetic threshold reached');
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
    if ($AgentAcceptance) {
        . (Join-Path $PSScriptRoot 'e2e/phase5_acceptance.ps1')
        return
    }
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
    if ($LocalModelStub) {
        $report.actualModelHttpCalls=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
        Check 'Actual fixture HTTP calls match admission decision' ($report.actualModelHttpCalls -eq $expectedCalls)
    }
    $report.modelUsage = Sql "SELECT outcome,input_tokens,output_tokens,latency_ms FROM $copilotDb.model_usage_ledger;"
    if ($expectedCalls -eq 1) {
        $report.modelAccounting = (Sql "SELECT JSON_OBJECT('provider',provider,'modelVersion',model_configuration_version,'promptVersion',prompt_version,'inputTokens',input_tokens,'outputTokens',output_tokens,'currency',currency,'priceTableVersion',price_table_version,'estimatedCost',CAST(estimated_cost AS CHAR)) FROM $copilotDb.model_usage_ledger;") | ConvertFrom-Json
        Check 'Model ledger uses the frozen CNY price table and stays within smoke budget' (
            $report.modelAccounting.currency -eq 'CNY' -and $report.modelAccounting.priceTableVersion -eq $PriceTableVersion -and
            [decimal]$report.modelAccounting.estimatedCost -le $SmokeBudgetCny)
    } else { $report.modelAccounting=$null }
    $report.manifest = (Sql "SELECT JSON_OBJECT('sha256',manifest_sha256,'modelVersion',JSON_UNQUOTE(JSON_EXTRACT(manifest_json,'$.model.version')),'priceVersion',JSON_UNQUOTE(JSON_EXTRACT(manifest_json,'$.price.version')),'indexVersion',JSON_UNQUOTE(JSON_EXTRACT(manifest_json,'$.index.version')),'inputUpperBound',JSON_EXTRACT(manifest_json,'$.inputUpperBound'),'reservedEstimateCny',JSON_UNQUOTE(JSON_EXTRACT(manifest_json,'$.reservedEstimateCny')),'fixture',JSON_EXTRACT(manifest_json,'$.fixture')) FROM $copilotDb.model_run_manifest;") | ConvertFrom-Json
    Check 'Immutable manifest binds this model price and published index' (
        (Sql "SELECT COUNT(*) FROM $copilotDb.model_run_manifest;") -eq '1' -and
        $report.manifest.priceVersion -eq $PriceTableVersion -and $report.manifest.indexVersion -eq $env:E2E_INDEX_VERSION -and
        $report.manifest.modelVersion.StartsWith('p4-model-'))
    if ($expectedCalls -eq 1) {
        $report.budget=(Sql "SELECT JSON_OBJECT('status',status,'reservedCny',CAST(reserved_cny AS CHAR),'settledCny',CAST(settled_cny AS CHAR)) FROM $copilotDb.model_budget_reservation;") | ConvertFrom-Json
        Check 'Budget is settled once and all holds released' ($report.budget.status -eq 'SETTLED' -and
            $report.manifest.modelVersion -eq $report.modelAccounting.modelVersion -and
            [decimal]$report.budget.settledCny -eq [decimal]$report.modelAccounting.estimatedCost -and
            (Sql "SELECT COUNT(*) FROM $copilotDb.model_budget_account WHERE held_cny<>0;") -eq '0' -and
            (Sql "SELECT COUNT(*) FROM $copilotDb.model_budget_event WHERE event_type='SETTLED';") -eq '1')
    } else {
        $report.budget=$null
        $expectedFailure=if ($ExpectBudgetRejection) { 'MODEL_BUDGET_EXCEEDED' } else { 'MODEL_CAPACITY_UNAVAILABLE' }
        Check 'Admission reaches the expected failed terminal state without charge' ($result.status -eq 'FAILED' -and $result.failureCode -eq $expectedFailure -and
            (Sql "SELECT COUNT(*) FROM $copilotDb.model_budget_reservation;") -eq '0')
    }
    if ($expectedCalls -eq 0) {
        Check 'Admission rejected without a usable result' ((Sql "SELECT COUNT(*) FROM $copilotDb.analysis_result;") -eq '0')
    } elseif ($ExpectPolicyRejection -or $ExpectFactRejection -or $ExpectMissingPolicy) {
        Check 'Incorrect directional recommendation reaches failed terminal state' ($result.status -eq 'FAILED' -and $result.failureCode -eq 'FACT_VALIDATION_FAILED')
        Check 'Rejected recommendation is not persisted as result' ((Sql "SELECT COUNT(*) FROM $copilotDb.analysis_result;") -eq '0')
    } else {
        Check 'Analysis reaches SUCCEEDED through Outbox/RabbitMQ/Worker' ($result.status -eq 'SUCCEEDED')
        Check 'Model result contains the published policy citation' ($result.result.policyCitations.documentId -contains 'finguard-large-amount-review-policy')
    }
    Check 'Core business decision remains PENDING and version unchanged' ((Sql "SELECT CONCAT(status,':',version,':',COALESCE(reviewed_by,0),':',COALESCE(decision_note,'')) FROM $coreDb.review_tasks WHERE id=$taskId;") -eq $before)
    Check 'Core business and audit table checksums unchanged' ((Sql ('CHECKSUM TABLE '+($coreTables -join ','))) -eq $fingerprintBefore)
    Check 'Invocation ledger count matches actual admission' ((Sql "SELECT COUNT(*) FROM $copilotDb.model_usage_ledger;") -eq [string]$expectedCalls)
    Check 'Durable model call claim count matches actual admission' ((Sql "SELECT COUNT(*) FROM $copilotDb.model_call_reservation;") -eq [string]$expectedCalls)
    $report.providerEvidence=(Sql "SELECT JSON_OBJECT('count',COUNT(*),'requestIdAvailable',MAX(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.providerRequestIdSha256')))=64),'returnedModelAvailable',MAX(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.returnedModelSha256')))=64),'httpStatus',MAX(JSON_EXTRACT(details_json,'$.httpStatus'))) FROM $copilotDb.model_budget_event WHERE event_type='PROVIDER_RESPONSE';") | ConvertFrom-Json
    Check 'Provider correlation evidence is persisted without raw response' ($report.providerEvidence.count -eq $expectedCalls -and
        ($expectedCalls -eq 0 -or ($report.providerEvidence.requestIdAvailable -eq 1 -and $report.providerEvidence.returnedModelAvailable -eq 1)))
    Check 'Outbox published and Worker receipt persisted' ((Sql "SELECT COUNT(*) FROM $copilotDb.outbox_event o JOIN $copilotDb.analysis_message_receipt r ON r.event_id=o.id WHERE o.published_at IS NOT NULL;") -eq '1')
    $ledgerBefore=Sql "CHECKSUM TABLE $copilotDb.model_usage_ledger;"
    $governanceBefore=Sql "CHECKSUM TABLE $copilotDb.model_run_manifest,$copilotDb.model_budget_account,$copilotDb.model_budget_reservation,$copilotDb.model_budget_event;"
    $payload=Sql "SELECT CAST(payload_json AS CHAR) FROM $copilotDb.outbox_event LIMIT 1;"
    $rabbitBasic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($rabbit.RABBITMQ_DEFAULT_USER+':'+$rabbit.RABBITMQ_DEFAULT_PASS))
    $rabbitHeaders=@{Authorization='Basic '+$rabbitBasic}
    $encodedVhost=[Uri]::EscapeDataString($vhost)
    . (Join-Path $PSScriptRoot 'e2e/wait-rabbit-ack.ps1')
    $queueUrl="http://127.0.0.1:15672/api/queues/$encodedVhost/finguard.copilot.analysis.request.v1"
    # Establish the initial ACK before publishing; stale management statistics
    # for the first delivery must not be mistaken for the duplicate's ACK.
    $initialAcks=if ($ExpectRedisFailure) { 3 } else { 1 }
    $beforeQueue=Wait-RabbitAck -QueueUrl $queueUrl -Headers $rabbitHeaders -MinimumAck $initialAcks
    $dlqUrl="http://127.0.0.1:15672/api/queues/$encodedVhost/finguard.copilot.analysis.dlq.v1"
    $expectedDeadLetters=if ($result.status -eq 'FAILED') { 1 } else { 0 }
    $dlqDeadline=[DateTime]::UtcNow.AddSeconds(30)
    do {
        $beforeDlq=Invoke-RestMethod $dlqUrl -Headers $rabbitHeaders -TimeoutSec 5
        if ($beforeDlq.messages -eq $expectedDeadLetters) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $dlqDeadline)
    Check 'Initial dead-letter depth matches the terminal outcome' ($beforeDlq.messages -eq $expectedDeadLetters)
    $published=Invoke-RestMethod "http://127.0.0.1:15672/api/exchanges/$encodedVhost/finguard.copilot.analysis.v1/publish" `
        -Method Post -Headers $rabbitHeaders -ContentType 'application/json' -Body (@{properties=@{content_type='application/json'};
        routing_key='analysis.requested';payload=$payload;payload_encoding='string'}|ConvertTo-Json -Depth 4)
    Check 'Duplicate terminal event routed through actual RabbitMQ exchange' ([bool]$published.routed)
    $queue=Wait-RabbitAck -QueueUrl $queueUrl -Headers $rabbitHeaders -MinimumAck ([long]$beforeQueue.message_stats.ack+1)
    $dlq=Invoke-RestMethod $dlqUrl -Headers $rabbitHeaders -TimeoutSec 5
    $report.rabbitRedelivery=@{ackBefore=[long]$beforeQueue.message_stats.ack;ackAfter=[long]$queue.message_stats.ack;
        ready=$queue.messages_ready;unacknowledged=$queue.messages_unacknowledged;
        deadLetterBefore=$beforeDlq.messages;deadLetterMessages=$dlq.messages}
    Check 'Duplicate ACK observed without new ledger write, receipt or call claim' ($dlq.messages -eq $beforeDlq.messages -and
        (Sql "CHECKSUM TABLE $copilotDb.model_run_manifest,$copilotDb.model_budget_account,$copilotDb.model_budget_reservation,$copilotDb.model_budget_event;") -eq $governanceBefore -and
        (Sql "CHECKSUM TABLE $copilotDb.model_usage_ledger;") -eq $ledgerBefore -and
        (Sql "SELECT COUNT(*) FROM $copilotDb.analysis_message_receipt;") -eq '1' -and
        (Sql "SELECT COUNT(*) FROM $copilotDb.model_call_reservation;") -eq [string]$expectedCalls)
    if ($LocalModelStub) {
        $report.actualModelHttpCallsAfterDuplicate=[int](Invoke-RestMethod 'http://127.0.0.1:18765/_calls').calls
        Check 'Duplicate event did not issue another fixture HTTP request' ($report.actualModelHttpCallsAfterDuplicate -eq $expectedCalls)
    }
    $report.recommendation=$result.result.recommendation
    $report.citationCount=if ($null -eq $result.result) { 0 } else { @($result.result.policyCitations).Count }
    $page = Invoke-RestMethod "$url/$analysisId/view" -Headers $headers
    $inexactCostFields = @('estimatedCost','heldCny','inputTokens','outputTokens') | Where-Object {
        $value=$page.cost.$_
        $null -ne $value -and $value -isnot [string]
    }
    Check 'Authorized UI projection returns exact IDs, amounts and accounting values as strings' ($page.schemaVersion -eq 1 -and
        $page.reviewTaskId -is [string] -and $null -ne $page.cost -and @($inexactCostFields).Count -eq 0 -and
        ($result.status -ne 'SUCCEEDED' -or ($page.result.businessEvidence[0].amount -is [string] -and $page.result.businessEvidence[0].transactionId -is [string])))
    Check 'UI accounting distinguishes rejected admission from settled usage' (
        ($expectedCalls -eq 0 -and $page.cost.accountingStatus -eq 'NOT_RECORDED' -and
            $null -eq $page.cost.estimatedCost -and $null -eq $page.cost.heldCny -and -not $page.cost.callSlotClaimed) -or
        ($expectedCalls -eq 1 -and $page.cost.accountingStatus -eq 'SETTLED' -and
            $page.cost.estimatedCost -is [string] -and $page.cost.heldCny -eq '0' -and $page.cost.callSlotClaimed))
    foreach ($suffix in @('view','events')) {
        Check "Unauthenticated $suffix read rejected with 401" ((Http-Status 'GET' "$url/$analysisId/$suffix" @{} '') -eq 401)
        Check "ADMIN $suffix read rejected with 403" ((Http-Status 'GET' "$url/$analysisId/$suffix" $adminHeaders '') -eq 403)
        Check "Unknown $suffix read rejected with 404" ((Http-Status 'GET' "$url/00000000-0000-0000-0000-000000000001/$suffix" $headers '') -eq 404)
    }
    $stream=(Invoke-WebRequest "$url/$analysisId/events" -Headers $headers -TimeoutSec 15).Content
    $sequences=@([regex]::Matches($stream,'(?m)^id:(\d+)') | ForEach-Object { [long]$_.Groups[1].Value })
    $persisted=Sql "SELECT GROUP_CONCAT(sequence_number ORDER BY sequence_number) FROM $copilotDb.analysis_event WHERE analysis_id=UNHEX(REPLACE('$analysisId','-',''));"
    Check 'SSE frames equal the complete persisted monotonic event sequence' ($sequences.Count -ge 2 -and ($sequences -join ',') -eq $persisted)
    $resume=@{Authorization=$headers.Authorization;'Last-Event-ID'=[string]$sequences[1]}
    $resumed=(Invoke-WebRequest "$url/$analysisId/events" -Headers $resume -TimeoutSec 15).Content
    $later=@([regex]::Matches($resumed,'(?m)^id:(\d+)') | ForEach-Object { [long]$_.Groups[1].Value })
    Check 'Last-Event-ID reconnect replays only subsequent persisted events' (($later -join ',') -eq (($sequences | Select-Object -Skip 2) -join ','))
    $future=@{Authorization=$headers.Authorization;'Last-Event-ID'='9223372036854775807'}
    Check 'Future SSE cursor rejected with 409' ((Http-Status 'GET' "$url/$analysisId/events" $future '') -eq 409)
    $report.progress=@{sequences=$sequences; resumedSequences=$later; uiProjectionSchema=$page.schemaVersion}
    if ($BrowserAcceptance) {
        # Ephemeral SYNTHETIC credentials for local browser verification only. Never copy into acceptance artifacts.
        @{username='e2e-reviewer';password=$env:FINGUARD_AUTH_BOOTSTRAP_REVIEWER_PASSWORD;reviewTaskId=[string]$taskId;analysisId=$analysisId;runId=$runId} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'browser-session.json') -Encoding UTF8
    }
    $report.status='PASSED'
} catch {
    $report.status='FAILED'
    $report.error=$_.Exception.Message
    Write-Host "FAILED $($report.error)"
} finally {
    if (-not $KeepRunning) {
        foreach ($process in $processes) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } }
        if ($qdrantStarted) { & docker stop $qdrant 2>$null | Out-Null }
        if ($redisStarted) { & docker stop $isolatedRedisContainer 2>$null | Out-Null }
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
