[CmdletBinding()]
param(
    [string]$EmbeddingEndpoint='http://127.0.0.1:8766',
    [Parameter(Mandatory=$true)][string]$EmbeddingVersion,
    [Parameter(Mandatory=$true)][string]$EmbeddingManifestSha256
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$savedEnvironment=@{}
Get-ChildItem Env: | ForEach-Object {$savedEnvironment[$_.Name]=$_.Value}
$statePath=Join-Path $root '.superpowers/sdd/2026-11-04-human-calibration-real-rag-quality/quality-infrastructure.json'
$qdrantStarted=$false;$prepared=$false;$state=$null;$nativeExit=1
try {
    if(Test-Path -LiteralPath $statePath){throw 'Semantic preparation already has a recorded attempt; inspect it before starting another'}
    $uri=[Uri]$EmbeddingEndpoint
    if($uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -lt 1 -or $uri.AbsolutePath -ne '/' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $EmbeddingManifestSha256 -notmatch '^[a-f0-9]{64}$'){throw 'Pinned loopback semantic endpoint required'}
    $health=Invoke-RestMethod -Uri ($EmbeddingEndpoint.TrimEnd('/')+'/health') -TimeoutSec 5
    if($health.status -ne 'UP' -or $health.dimensions -ne 384 -or $health.embeddingVersion -ne $EmbeddingVersion -or $health.artifactManifestSha256 -ne $EmbeddingManifestSha256){throw 'Semantic model identity/health mismatch'}
    $pack=Join-Path $root 'tools/agent-evaluation/data/semantic-quality-v1/manifest.json'
    $packSha=(Get-FileHash $pack -Algorithm SHA256).Hash.ToLowerInvariant()
    if($packSha -ne '566755e5f5a0d8b7370e7c54dd5c0630737960ce37f7fd43c5af060d14d1ac61'){throw 'Frozen semantic pack manifest changed'}
    if(-not(Test-Path (Join-Path $root 'target/test-classes/com/finguard/copilot/agent/qualify/SemanticRagPreparationMain.class'))){throw 'Compile the reviewed preparation CLI first'}
    $timestamp=Get-Date -Format 'yyyyMMdd_HHmmss'
    $database="fg_sq_$timestamp";$qdrantName="fg-semantic-quality-$timestamp"
    $output=Join-Path $root "target/semantic-quality/$timestamp"
    New-Item -ItemType Directory -Path $output | Out-Null
    $state=[ordered]@{status='STARTING_UNPAID';database=$database;qdrantName=$qdrantName;output=$output;startedAt=[DateTimeOffset]::Now.ToString('o');embeddingEndpoint=$EmbeddingEndpoint;embeddingVersion=$EmbeddingVersion;embeddingManifestSha256=$EmbeddingManifestSha256;dataManifestSha256=$packSha}
    $exclusive=[IO.File]::Open($statePath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes(($state|ConvertTo-Json));$exclusive.Write($bytes,0,$bytes.Length)}finally{$exclusive.Dispose()}
    $values=& docker inspect --format '{{json .Config.Env}}' finguard-mysql | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Shared project MySQL unavailable'}
    $mysql=@{};foreach($line in $values){$parts=$line -split '=',2;$mysql[$parts[0]]=$parts[1]}
    $user=$mysql.MYSQL_USER;if($user -notmatch '^[A-Za-z0-9_]+$'){throw 'Unexpected project database username'}
    "CREATE DATABASE $database; GRANT ALL ON $database.* TO '$user'@'%';" | & docker exec -i finguard-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Isolated semantic database creation failed'}
    & docker run -d --name $qdrantName -p '127.0.0.1::6333' qdrant/qdrant:v1.15.5 | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Owned Qdrant start failed'}
    $qdrantStarted=$true
    $bindings=& docker inspect --format '{{json .NetworkSettings.Ports}}' $qdrantName | ConvertFrom-Json
    $qdrantEndpoint='http://127.0.0.1:'+$bindings.'6333/tcp'[0].HostPort
    $ready=$false
    for($n=0;$n -lt 40;$n++){
        try{if((Invoke-WebRequest -Uri ($qdrantEndpoint+'/readyz') -TimeoutSec 2).StatusCode -eq 200){$ready=$true;break}}catch{}
        Start-Sleep -Milliseconds 250
    }
    if(-not $ready){throw 'Owned Qdrant readiness failed'}
    $state.qdrantEndpoint=$qdrantEndpoint;$state.qdrantPrefix="semantic-$timestamp"
    $env:COPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/$database"+'?sslMode=DISABLED&allowPublicKeyRetrieval=true'
    $env:COPILOT_DB_USERNAME=$user;$env:COPILOT_DB_PASSWORD=$mysql.MYSQL_PASSWORD
    $env:COPILOT_QDRANT_ENDPOINT=$qdrantEndpoint;$env:COPILOT_QDRANT_COLLECTION_PREFIX=$state.qdrantPrefix
    $env:COPILOT_EMBEDDING_ENDPOINT=$EmbeddingEndpoint;$env:COPILOT_EMBEDDING_VERSION=$EmbeddingVersion;$env:COPILOT_EMBEDDING_MANIFEST_SHA256=$EmbeddingManifestSha256
    $env:COPILOT_MODEL_API_KEY=$null;$env:SEMANTIC_QUALITY_APPROVED=$null
    $classpath=(Join-Path $root 'target/test-classes')+';'+(Join-Path $root 'target/classes')+';'+(Get-Content (Join-Path $root 'target/native-qualification-classpath.txt') -Raw).Trim()
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
    $java=Join-Path $env:JAVA_HOME 'bin/java.exe'
    & $java '-Xmx384m' -cp $launcher com.finguard.copilot.agent.qualify.SemanticRagPreparationMain (Join-Path $root 'tools/agent-evaluation/data/semantic-quality-v1') $output *> (Join-Path $output 'preparation-execution.log')
    $nativeExit=$LASTEXITCODE
    $report=Get-Content (Join-Path $output 'preparation.json') -Raw | ConvertFrom-Json
    $prepared=$nativeExit -eq 0 -and $report.status -eq 'RAG_EVALUATED'
    $state.status=if($prepared){'PREPARED_UNPAID'}else{'PREPARATION_FAILED'}
    $state.preparationSha256=(Get-FileHash (Join-Path $output 'preparation.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    $state.preparationExitCode=$nativeExit;$state.preparedAt=[DateTimeOffset]::Now.ToString('o')
    $state|ConvertTo-Json|Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "SEMANTIC_PREPARATION status=$($state.status) holdoutQualityPassed=$($report.holdoutQualityPassed) threshold=$($report.minimumScore)"
    Write-Host "SEMANTIC_PREPARATION_REPORT $(Join-Path $output 'preparation.json')"
} finally {
    if($qdrantStarted -and -not $prepared){& docker stop $qdrantName 2>$null | Out-Null}
    foreach($entry in @(Get-ChildItem Env:)){if(-not $savedEnvironment.ContainsKey($entry.Name)){[Environment]::SetEnvironmentVariable($entry.Name,$null,'Process')}}
    foreach($name in $savedEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process')}
}
exit $nativeExit
