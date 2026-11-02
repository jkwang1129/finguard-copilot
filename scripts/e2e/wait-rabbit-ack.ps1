function Wait-RabbitAck {
    param(
        [Parameter(Mandatory)][string]$QueueUrl,
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][long]$MinimumAck,
        [ValidateRange(1,60)][int]$TimeoutSeconds = 30
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $queue = Invoke-RestMethod $QueueUrl -Headers $Headers -TimeoutSec 5
        if ($null -ne $queue.message_stats.ack -and [long]$queue.message_stats.ack -ge $MinimumAck -and
                $queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0) { return $queue }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'RabbitMQ ACK evidence did not reach the required count before timeout'
}
