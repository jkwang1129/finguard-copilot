[CmdletBinding()]
param(
    [string]$EnvironmentFile='C:/dev/worktrees/finguard-copilot-phase1/.env.local',
    [switch]$Execute,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$root=Split-Path -Parent $PSScriptRoot
$saved=@{};Get-ChildItem Env: | ForEach-Object {$saved[$_.Name]=$_.Value}
$redisStarted=$false;$paidStarted=$false;$nativeExit=1;$state=$null;$infra=$null;$smoke=$null
$statePath=Join-Path $root '.superpowers/sdd/2026-11-04-semantic-quality-requalification/main-paid-batch.json'
try {
    if($Execute -eq $ValidateOnly){throw 'Execute or ValidateOnly required'}
    $manifest=Get-Content (Join-Path $root 'docs/evaluations/semantic-main-guard-requalification-manifest.json') -Raw | ConvertFrom-Json -DateKind String
    if($manifest.status -ne 'AUTHORIZED_READY' -or $manifest.componentMaxRequests -ne 11 -or $manifest.mainMaxRequests -ne 12 -or [decimal]$manifest.componentMaxCny -ne 1.10 -or [decimal]$manifest.processSmokeMaxCny -ne 0.10 -or [decimal]$manifest.mainMaxCny -ne 1.20 -or $manifest.automaticRetries -ne 0 -or [decimal]$manifest.perCallMaxCny -ne 0.10){throw 'Exact frozen requalification limits/status required'}
    $verification=$manifest.prepaidVerification
    $parsedFreeze=[DateTimeOffset]::MinValue
    $validFreeze=$manifest.frozenAt -is [string] -and $manifest.frozenAt -cmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,7})?(Z|\+00:00)\z' -and
        [DateTimeOffset]::TryParse($manifest.frozenAt,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$parsedFreeze) -and $parsedFreeze.Offset -eq [TimeSpan]::Zero
    if($verification.status -cne 'VERIFIED_CURRENT_TREE' -or
        ($verification.javaTests -isnot [int] -and $verification.javaTests -isnot [long]) -or $verification.javaTests -le 0 -or
        $verification.failures -ne 0 -or $verification.errors -ne 0 -or $verification.skipped -ne 0 -or
        $verification.packagePassed -isnot [bool] -or $verification.packagePassed -ne $true -or
        $verification.sourceUnchangedThroughFullSuite -isnot [bool] -or $verification.sourceUnchangedThroughFullSuite -ne $true -or
        $verification.fullJavaLogSha256 -isnot [string] -or $verification.packageLogSha256 -isnot [string] -or
        $verification.fullJavaLogSha256 -notmatch '\A[a-fA-F0-9]{64}\z' -or $verification.packageLogSha256 -notmatch '\A[a-fA-F0-9]{64}\z' -or -not $validFreeze){
        throw 'Current-tree verification and UTC freeze required'
    }
    $now=[DateTimeOffset]::UtcNow
    if($now -lt [DateTimeOffset]::Parse($manifest.priceValidFrom) -or $now -ge [DateTimeOffset]::Parse($manifest.priceValidUntil)){throw 'Reviewed main-model price window expired'}
    foreach($entry in $manifest.verifiedSourceShas.PSObject.Properties){
        if((Get-FileHash (Join-Path $root $entry.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value){throw 'Frozen quality source/input changed'}
    }
    if(@($manifest.verifiedSourceShas.PSObject.Properties).Count -lt 391){throw 'Complete reviewed source freeze required'}
    foreach($requiredSource in @('pom.xml','scripts/run-semantic-main-requalification.ps1','scripts/prepare-semantic-requalification.ps1','scripts/run-business-e2e.ps1','src/main/java/com/finguard/copilot/analysis/application/ModelInvocationException.java','src/main/java/com/finguard/copilot/analysis/application/ModelValidationStage.java','src/main/java/com/finguard/copilot/analysis/application/ModelAnalysisOrchestrator.java','src/main/java/com/finguard/copilot/agent/runtime/NativeReviewedPrice.java','src/test/java/com/finguard/copilot/agent/qualify/SemanticMainModelQualityMain.java','src/test/java/com/finguard/copilot/agent/qualify/SemanticRequalificationPreparationMain.java','src/test/java/com/finguard/copilot/agent/qualify/SemanticRequalificationPreparationData.java','src/test/java/com/finguard/copilot/agent/qualify/SemanticRequalificationReporting.java','tools/agent-evaluation/data/semantic-business-v2/cases.jsonl','tools/agent-evaluation/data/semantic-quality-v1/corpus.jsonl')){
        if($null -eq $manifest.verifiedSourceShas.PSObject.Properties[$requiredSource]){throw 'Required runtime source/input missing from freeze'}
    }
    if(@($manifest.verifiedArtifactShas.PSObject.Properties).Count -lt 3){throw 'Compiled component artifacts must be frozen'}
    foreach($entry in $manifest.verifiedArtifactShas.PSObject.Properties){
        $artifactPath=if([IO.Path]::IsPathRooted($entry.Name)){$entry.Name}else{Join-Path $root $entry.Name}
        if((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value){throw 'Frozen component bytecode/classpath/dependency changed'}
    }
    # Every file reachable through the reviewed local classpath must be frozen, including resources.
    foreach($directory in @('target/classes','target/test-classes')){
        foreach($file in Get-ChildItem -LiteralPath (Join-Path $root $directory) -Recurse -File){
            $relative=[IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/')
            if($null -eq $manifest.verifiedArtifactShas.PSObject.Properties[$relative]){throw 'Loaded bytecode/resource missing from freeze'}
        }
    }
    $classpathFile='target/native-qualification-classpath.txt'
    if($null -eq $manifest.verifiedArtifactShas.PSObject.Properties[$classpathFile]){throw 'Actual component classpath missing from freeze'}
    foreach($dependency in ((Get-Content (Join-Path $root $classpathFile) -Raw).Trim() -split ';')){
        if(-not [IO.Path]::IsPathRooted($dependency) -or $null -eq $manifest.verifiedArtifactShas.PSObject.Properties[$dependency]){throw 'Loaded dependency missing from freeze'}
    }
    if(Test-Path -LiteralPath $statePath){throw 'Main quality batch already started; inspect reports/database, no retry'}
    $infra=Get-Content (Join-Path $root '.superpowers/sdd/2026-11-04-semantic-quality-requalification/quality-infrastructure.json') -Raw | ConvertFrom-Json -DateKind String
    if($infra.status -ne 'REQUALIFICATION_PREPARED' -or $infra.database -notmatch '^fg_sq_[0-9_]+$' -or $infra.qdrantName -notmatch '^fg-semantic-requalification-[0-9_]+$'){throw 'Owned prepared infrastructure required'}
    $preparation=Join-Path $infra.output 'preparation.json'
    if((Get-FileHash $preparation -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.preparationSha256 -or $infra.preparationSha256 -ne $manifest.preparationSha256){throw 'Frozen preparation changed'}
    $launcherRelative=[IO.Path]::GetRelativePath($root,(Join-Path $infra.output 'classpath.jar')).Replace('\','/')
    if($null -eq $manifest.verifiedArtifactShas.PSObject.Properties[$launcherRelative]){throw 'Actual launcher classpath JAR missing from freeze'}
    $preparedReport=Get-Content -LiteralPath $preparation -Raw | ConvertFrom-Json -DateKind String
    if($preparedReport.status -ne 'REQUALIFICATION_PREPARED' -or $preparedReport.publicationReadinessOnly -ne $true -or $preparedReport.holdoutExecutions -ne 0 -or $preparedReport.holdoutQualityPassed -ne $false -or [double]$preparedReport.minimumScore -ne 0.8569634500000001 -or $preparedReport.originalPreparationSha256 -ne 'c66bac3d56a163141b0aee29460cc00cfb549fa9bfd625cf53a82a9c04c9b386' -or $preparedReport.corpusSha256 -ne '9fdd43cb211ec5a230f4deaaaf6e0c1d1a7bbc6b8b47e61b82e59e21698fe982'){throw 'Frozen requalification preparation identity mismatch'}
    if($infra.embeddingEndpoint -ne 'http://127.0.0.1:8766' -or $infra.embeddingVersion -ne 'e5-small-int8-55102a6d5c23b708db337173' -or $infra.embeddingManifestSha256 -ne '55102a6d5c23b708db337173e13e93954b1d9cf0fb7632cfa3d2ce42b643d10d'){throw 'Exact pinned semantic model required'}
    if((Get-FileHash (Join-Path $root 'target/finguard-copilot-0.0.1-SNAPSHOT.jar') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.copilotJarSha256 -or
        (Get-FileHash 'C:/dev/finguard-core/target/finguard-core-0.0.1-SNAPSHOT.jar' -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.coreJarSha256){throw 'Verified process artifacts changed'}
    if($manifest.provider -ne 'deepseek' -or $manifest.model -ne 'deepseek-flash' -or [decimal]$manifest.inputPricePerMillionCny -ne 2 -or [decimal]$manifest.outputPricePerMillionCny -ne 8 -or $manifest.priceVersion -ne 'deepseek-flash-native-20261104-peak-miss-cny-v1' -or $manifest.priceValidFrom -ne '2026-11-04T00:00:00Z' -or $manifest.priceValidUntil -ne '2026-11-05T00:00:00Z' -or $manifest.holdoutQualityPassed -ne $false){throw 'Exact reviewed provider/price and original failed holdout reference required'}
    if($ValidateOnly){Write-Host 'SEMANTIC_MAIN_VALIDATED component11+process1 maxRequests12 maxCny1.20 no credentials/services/provider HTTP';return}
    $env:COPILOT_AGENT_ENABLED='false';$env:COPILOT_AGENT_RUNTIME_ENABLED='false';$env:COPILOT_AGENT_HUMAN_GATE='false'
    $health=Invoke-RestMethod ($infra.embeddingEndpoint.TrimEnd('/')+'/health') -TimeoutSec 5
    if($health.status -ne 'UP' -or $health.embeddingVersion -ne $infra.embeddingVersion -or $health.artifactManifestSha256 -ne $infra.embeddingManifestSha256){throw 'Pinned semantic service unavailable'}
    if((& docker inspect --format '{{.State.Running}}' $infra.qdrantName) -ne 'true'){throw 'Owned prepared Qdrant unavailable'}
    . (Join-Path $PSScriptRoot 'import-model-environment.ps1') -Path $EnvironmentFile
    if($env:COPILOT_MODEL_PROVIDER -ne 'deepseek' -or $env:COPILOT_MODEL_ID -ne 'deepseek-flash' -or -not $env:COPILOT_MODEL_API_KEY -or $env:COPILOT_MODEL_BASE_URL.TrimEnd('/') -ne 'https://api.deepseek.com'){throw 'Exact approved DeepSeek credential configuration required'}
    $env:COPILOT_AGENT_ENABLED='false';$env:COPILOT_AGENT_RUNTIME_ENABLED='false';$env:COPILOT_AGENT_HUMAN_GATE='false'
    $state=[ordered]@{batch='semantic-main-guard-requalification';status='STARTING';startedAt=[DateTimeOffset]::Now.ToString('o');database=$infra.database;output=$infra.output;maxRequests=12;maxCny='1.20';automaticRetries=0;componentStatus='NOT_RUN';processStatus='NOT_RUN';retrievalHoldoutQualityPassed=[bool]$manifest.holdoutQualityPassed;overallQualityPassed=$false}
    $exclusive=[IO.File]::Open($statePath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes(($state|ConvertTo-Json));$exclusive.Write($bytes,0,$bytes.Length)}finally{$exclusive.Dispose()}
    $paidStarted=$true
    $values=& docker inspect --format '{{json .Config.Env}}' finguard-mysql | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Project MySQL unavailable'}
    $mysql=@{};foreach($line in $values){$parts=$line -split '=',2;$mysql[$parts[0]]=$parts[1]}
    $redisName='fg-semantic-requalification-redis-'+(Get-Date -Format 'yyyyMMdd_HHmmss');$redisPassword=[guid]::NewGuid().ToString('N')
    & docker run -d --name $redisName -p '127.0.0.1::6379' redis:8.2.8-alpine redis-server --requirepass $redisPassword | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Owned quality Redis start failed'}
    $redisStarted=$true
    $ports=& docker inspect --format '{{json .NetworkSettings.Ports}}' $redisName | ConvertFrom-Json
    $env:SEMANTIC_QUALITY_REDIS_PORT=$ports.'6379/tcp'[0].HostPort;$env:SEMANTIC_QUALITY_REDIS_PASSWORD=$redisPassword
    $env:COPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/$($infra.database)"+'?sslMode=DISABLED&allowPublicKeyRetrieval=true'
    $env:COPILOT_DB_USERNAME=$mysql.MYSQL_USER;$env:COPILOT_DB_PASSWORD=$mysql.MYSQL_PASSWORD
    $env:COPILOT_QDRANT_ENDPOINT=$infra.qdrantEndpoint;$env:COPILOT_QDRANT_COLLECTION_PREFIX=$infra.qdrantPrefix
    $env:COPILOT_EMBEDDING_ENDPOINT=$infra.embeddingEndpoint;$env:COPILOT_EMBEDDING_VERSION=$infra.embeddingVersion;$env:COPILOT_EMBEDDING_MANIFEST_SHA256=$infra.embeddingManifestSha256
    $env:SEMANTIC_QUALITY_APPROVED='true';$env:SEMANTIC_QUALITY_CASES_SHA256=$manifest.casesSha256;$env:SEMANTIC_QUALITY_PREPARATION_SHA256=$manifest.preparationSha256
    $componentReport=Join-Path $infra.output 'main-component.json'
    $java=Join-Path $env:JAVA_HOME 'bin/java.exe'
    & $java '-Xmx384m' '-Djdk.httpclient.disableRetryConnect=true' -cp (Join-Path $infra.output 'classpath.jar') com.finguard.copilot.agent.qualify.SemanticMainModelQualityMain $preparation (Join-Path $root 'tools/agent-evaluation/data/semantic-business-v2/cases.jsonl') $componentReport *> (Join-Path $infra.output 'main-component-execution.log')
    $componentExit=$LASTEXITCODE
    $state.componentExitCode=$componentExit
    $component=Get-Content $componentReport -Raw | ConvertFrom-Json -DateKind String
    $state.componentStatus=$component.status;$state.componentRequests=$component.providerAttempts
    $state.componentKnownEstimatedCny=$component.knownEstimatedCny;$state.componentHeldCny=$component.heldCny
    $state|ConvertTo-Json|Set-Content -LiteralPath $statePath -Encoding utf8
    $accountingSafe=$component.accountingIntegrityPassed -eq $true -and $component.maxRequests -eq 11 -and $component.caseDenominator -eq 11 -and [decimal]$component.componentCapCny -eq 1.10 -and $component.providerAttempts -le 11 -and [decimal]$component.knownEstimatedCny -le 1.10 -and $null -ne $component.heldCny -and [decimal]$component.heldCny -eq 0 -and $component.lifecycleChecks.primaryRestored -eq $true
    if($accountingSafe){
        $env:COPILOT_AGENT_ENABLED='false';$env:COPILOT_AGENT_RUNTIME_ENABLED='false';$env:COPILOT_AGENT_HUMAN_GATE='false'
        $env:COPILOT_PRICE_VALID_FROM=$manifest.priceValidFrom;$env:COPILOT_PRICE_VALID_UNTIL=$manifest.priceValidUntil
        $smokeLog=Join-Path $infra.output 'main-process-execution.log'
        $state.processStatus='STARTING';$state.processReservedCny='0.10'
        $state|ConvertTo-Json|Set-Content -LiteralPath $statePath -Encoding utf8
        & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'run-business-e2e.ps1') -CoreRoot 'C:/dev/finguard-core' -EnvironmentFile $EnvironmentFile -Provider deepseek -ModelId deepseek-flash -SmokeBudgetCny 0.10 -InputPricePerMillionCny 2 -OutputPricePerMillionCny 8 -PriceTableVersion $manifest.priceVersion -SemanticEmbedding -EmbeddingEndpoint $infra.embeddingEndpoint -EmbeddingVersion $infra.embeddingVersion -EmbeddingManifestSha256 $infra.embeddingManifestSha256 -IsolatedRedis *> $smokeLog
        $state.processExitCode=$LASTEXITCODE
        $reportLine=@(Get-Content $smokeLog|Where-Object {$_ -match '^REPORT '})
        if($reportLine.Count -eq 1){
            $smokeReport=$reportLine[0].Substring(7);$state.processReport=$smokeReport
            $smoke=Get-Content $smokeReport -Raw | ConvertFrom-Json;$state.processStatus=$smoke.status
        }else{$state.processStatus='FAILED_REPORT_UNAVAILABLE'}
    }else{$state.processStatus='NOT_RUN_UNCERTAIN_OR_INTEGRITY_FAILURE'}
    $processKnown=[decimal]0;$processHeld=[decimal]0
    if($accountingSafe){
        if($null -ne $smoke -and $smoke.budget.status -eq 'SETTLED'){$processKnown=[decimal]$smoke.budget.settledCny}
        else{$processHeld=[decimal]'0.10'}
    }
    $state.processKnownEstimatedCny=$processKnown.ToString('0.00000000',[Globalization.CultureInfo]::InvariantCulture)
    $state.processHeldCny=$processHeld.ToString('0.00000000',[Globalization.CultureInfo]::InvariantCulture)
    $state.knownEstimatedCny=if($null -ne $component.knownEstimatedCny){([decimal]$component.knownEstimatedCny+$processKnown).ToString('0.00000000',[Globalization.CultureInfo]::InvariantCulture)}else{$null}
    $state.unknownHeldCny=if($null -ne $component.heldCny){([decimal]$component.heldCny+$processHeld).ToString('0.00000000',[Globalization.CultureInfo]::InvariantCulture)}else{$null}
    $state.status=if($componentExit -eq 0 -and $state.processStatus -eq 'PASSED' -and $state.processExitCode -eq 0){'MEASUREMENT_COMPLETE'}else{'MEASUREMENT_COMPLETE_WITH_FAILURES'}
    $state.overallQualityPassed=$manifest.holdoutQualityPassed -eq $true -and $state.status -eq 'MEASUREMENT_COMPLETE'
    $state.finishedAt=[DateTimeOffset]::Now.ToString('o')
    $state|ConvertTo-Json|Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "SEMANTIC_MAIN component=$($state.componentStatus) process=$($state.processStatus) componentRequests=$($state.componentRequests)"
    Write-Host "SEMANTIC_MAIN_REPORT $componentReport"
    $nativeExit=if($state.status -eq 'MEASUREMENT_COMPLETE'){0}else{2}
} catch {
    if($paidStarted -and $null -ne $state){
        $state.status='STOPPED_UNCERTAIN';$state.knownEstimatedCny=$null;$state.unknownHeldCny='1.20'
        $state.failureType=$_.Exception.GetType().Name;$state.finishedAt=[DateTimeOffset]::Now.ToString('o')
        $state|ConvertTo-Json|Set-Content -LiteralPath $statePath -Encoding utf8
    }
    if(-not $paidStarted){throw}
    throw 'Requalification stopped; inspect the durable state and sanitized local reports; no automatic retry'
} finally {
    if($redisStarted){& docker stop $redisName 2>$null | Out-Null}
    if($paidStarted -and $null -ne $infra){& docker stop $infra.qdrantName 2>$null | Out-Null}
    foreach($entry in @(Get-ChildItem Env:)){if(-not $saved.ContainsKey($entry.Name)){[Environment]::SetEnvironmentVariable($entry.Name,$null,'Process')}}
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
exit $nativeExit
