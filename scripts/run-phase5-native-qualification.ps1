[CmdletBinding()]
param(
    [string]$EnvironmentFile='C:/dev/worktrees/finguard-copilot-phase1/.env.local',
    [Parameter(Mandatory=$true)][string]$DependencyGate,
    [switch]$Execute,
    [switch]$Requalification,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$initialEnvironment=@{};Get-ChildItem Env: | ForEach-Object {$initialEnvironment[$_.Name]=$_.Value}
$redisName=$null;$redisStarted=$false;$nativeExit=1;$statePath=$null
try {
    if(-not $Execute -and -not $ValidateOnly){throw 'This entry point requires Execute or ValidateOnly'}
    $gate=Get-Content -LiteralPath $DependencyGate -Raw | ConvertFrom-Json
    if($gate.status -ne 'COMPATIBLE_DEPENDENCIES_VERIFIED_SCOPED_RESIDUALS'){throw 'Dependency verification has not completed'}
    if((Get-FileHash (Join-Path $root 'pom.xml') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $gate.pomSha256){throw 'Verified dependency definition changed'}
    $proposalPath=if($Requalification){'docs/acceptance/phase5-provider-requalification-proposal.json'}else{'docs/acceptance/phase5-provider-qualification-proposal.json'}
    $approval=Get-Content -LiteralPath (Join-Path $root $proposalPath) -Raw | ConvertFrom-Json
    if($approval.status -ne 'AUTHORIZED_READY' -or $approval.maxSyntheticCases -ne 12 -or $approval.maxProviderRequests -ne 24 -or [decimal]$approval.maxCallCny -ne 0.10 -or [decimal]$approval.maxBatchCny -ne 2.00){throw 'Exact approved qualification limits are required'}
    $datasetPath=if($Requalification){'tools/agent-evaluation/data/phase5-native-requalification-v2.jsonl'}else{'tools/agent-evaluation/data/phase5-native-qualification-v1.jsonl'}
    $dataset=Join-Path $root $datasetPath
    $dataSha=(Get-FileHash $dataset -Algorithm SHA256).Hash.ToLowerInvariant()
    if($dataSha -ne $approval.qualificationDatasetSha256){throw 'Frozen qualification data changed'}
    foreach($entry in $approval.verifiedSourceShas.PSObject.Properties){
        if((Get-FileHash (Join-Path $root $entry.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value){throw 'Reviewed qualification source changed'}
    }
    $batch=if($Requalification){'phase5-native-retest-20261104'}else{'phase5-native-20261104'}
    $statePath=Join-Path $root $(if($Requalification){'.superpowers/sdd/2026-11-04-native-tool-budget-requalification/paid-batch.json'}else{'.superpowers/sdd/2026-11-04-dependencies-and-native-qualification/paid-batch.json'})
    if(Test-Path -LiteralPath $statePath){throw 'The approved batch already started; inspect its persistent database/report instead of retrying'}
    if($Requalification -and ($approval.batch -ne $batch -or $approval.qualificationDataset -ne $datasetPath)){throw 'Requalification profile mismatch'}
    if($ValidateOnly){Write-Host "QUALIFICATION_VALIDATED $batch no credentials, containers or HTTP used";return}
    . (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile
    if(-not $env:COPILOT_MODEL_API_KEY){throw 'Project model credential is unavailable'}
    if($env:COPILOT_MODEL_PROVIDER -ne 'deepseek' -or $env:COPILOT_MODEL_BASE_URL.TrimEnd('/') -ne 'https://api.deepseek.com'){throw 'The credential configuration must explicitly belong to the reviewed DeepSeek provider'}
    $output=Join-Path $root "target/native-qualification/$batch"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $timestamp=Get-Date -Format 'yyyyMMdd_HHmmss'
    $database="fg_p5nq_$timestamp"
    $mysqlValues=& docker inspect --format '{{json .Config.Env}}' finguard-mysql | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Project MySQL is unavailable'}
    $mysql=@{};foreach($line in $mysqlValues){$parts=$line -split '=',2;$mysql[$parts[0]]=$parts[1]}
    $user=$mysql.MYSQL_USER
    if($user -notmatch '^[A-Za-z0-9_]+$'){throw 'Unexpected project MySQL username'}
    "CREATE DATABASE $database; GRANT ALL ON $database.* TO '$user'@'%';" | & docker exec -i finguard-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Synthetic qualification database creation failed'}
    $redisName="fg-native-qualification-$timestamp";$redisPassword=[guid]::NewGuid().ToString('N')
    & docker run -d --name $redisName -p '127.0.0.1::6379' redis:8.2.8-alpine redis-server --requirepass $redisPassword | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Qualification Redis start failed'}
    $redisStarted=$true
    $binding=& docker inspect --format '{{json .NetworkSettings.Ports}}' $redisName | ConvertFrom-Json
    $redisPort=$binding.'6379/tcp'[0].HostPort
    $env:COPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/$database"+'?sslMode=DISABLED&allowPublicKeyRetrieval=true'
    $env:COPILOT_DB_USERNAME=$user;$env:COPILOT_DB_PASSWORD=$mysql.MYSQL_PASSWORD
    $env:NATIVE_QUALIFICATION_APPROVED='true';$env:NATIVE_QUALIFICATION_BATCH=$batch
    $env:NATIVE_QUALIFICATION_CASES_SHA256=$dataSha
    $env:NATIVE_QUALIFICATION_REDIS_PORT=$redisPort;$env:NATIVE_QUALIFICATION_REDIS_PASSWORD=$redisPassword
    $classpathFile=Join-Path $root 'target/native-qualification-classpath.txt'
    if(-not (Test-Path -LiteralPath $classpathFile)){throw 'Compile and resolve the exact qualification classpath first'}
    $classpath=(Join-Path $root 'target/test-classes')+';'+(Join-Path $root 'target/classes')+';'+(Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $state=[ordered]@{batch=$batch;status='STARTING';database=$database;startedAt=[DateTimeOffset]::Now.ToString('o');datasetSha256=$dataSha;maxRequests=24;maxBatchCny='2.00';report=(Join-Path $output 'report.json')}
    $exclusive=[IO.File]::Open($statePath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$encoded=[Text.Encoding]::UTF8.GetBytes(($state | ConvertTo-Json));$exclusive.Write($encoded,0,$encoded.Length)}finally{$exclusive.Dispose()}
    $java=if($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java.exe).Source}
    # Manifest indirection avoids Windows command-length and Java17 argument-file charset issues.
    $urls=@($classpath -split ';' | ForEach-Object {
        $entryPath=$_;if(Test-Path -LiteralPath $entryPath -PathType Container){$entryPath=$entryPath.TrimEnd('/','\')+'/'}
        ([Uri]$entryPath).GetComponents([UriComponents]::AbsoluteUri,[UriFormat]::UriEscaped)
    })
    $manifest="Manifest-Version: 1.0`r`n";$line='Class-Path: '+($urls -join ' ')
    while($line.Length -gt 70){$manifest+=$line.Substring(0,70)+"`r`n";$line=' '+$line.Substring(70)}
    $manifest+=$line+"`r`n`r`n"
    $launcher=Join-Path $output 'classpath.jar'
    $archive=[IO.Compression.ZipFile]::Open($launcher,[IO.Compression.ZipArchiveMode]::Create)
    try{$entry=$archive.CreateEntry('META-INF/MANIFEST.MF');$writer=[IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false));try{$writer.Write($manifest)}finally{$writer.Dispose()}}finally{$archive.Dispose()}
    # Credentials are inherited in environment only; command arguments contain no provider key.
    & $java '-Xmx384m' '-Djdk.httpclient.disableRetryConnect=true' '-cp' $launcher 'com.finguard.copilot.agent.qualify.NativeProviderQualificationMain' $dataset (Join-Path $output 'report.json') *> (Join-Path $output 'execution.log')
    $nativeExit=$LASTEXITCODE
    $state.status=if($nativeExit -eq 0){'COMPLETE'}else{'STOPPED_NO_AUTOMATIC_RETRY'}
    $state.finishedAt=[DateTimeOffset]::Now.ToString('o');$state.exitCode=$nativeExit
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    if(Test-Path (Join-Path $output 'report.json')){
        $result=Get-Content (Join-Path $output 'report.json') -Raw | ConvertFrom-Json
        Write-Host "QUALIFICATION status=$($result.status) providerClaims=$($result.providerClaims) estimateCny=$($result.knownEstimatedCny) heldCny=$($result.heldCny)"
    }
    Write-Host "QUALIFICATION_DATABASE $database"
    Write-Host "QUALIFICATION_REPORT $(Join-Path $output 'report.json')"
} finally {
    if($redisStarted){& docker stop $redisName 2>$null | Out-Null}
    foreach($entry in @(Get-ChildItem Env:)){if(-not $initialEnvironment.ContainsKey($entry.Name)){[Environment]::SetEnvironmentVariable($entry.Name,$null,'Process')}}
    foreach($name in $initialEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$initialEnvironment[$name],'Process')}
}
exit $nativeExit
