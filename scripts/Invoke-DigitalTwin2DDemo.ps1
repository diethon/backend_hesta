[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$HomeId = '00000000-0000-4000-8000-000000000201',
    [string]$SensorDeviceId = '00000000-0000-4000-8000-000000000605',
    [switch]$SendReading
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:HESTA_DEMO_ACCESS_TOKEN)) {
    throw 'Set $env:HESTA_DEMO_ACCESS_TOKEN to a Bearer access token first.'
}

$headers = @{ Authorization = "Bearer $($env:HESTA_DEMO_ACCESS_TOKEN)" }
$homeUrl = "$BaseUrl/api/v1/homes/$HomeId"

Write-Host 'Digital Twin snapshot:' -ForegroundColor Cyan
(Invoke-RestMethod -Method Get -Uri "$homeUrl/twin" -Headers $headers).result |
    ConvertTo-Json -Depth 12

Write-Host 'Digital Twin 2D layout:' -ForegroundColor Cyan
(Invoke-RestMethod -Method Get -Uri "$homeUrl/twin-layout" -Headers $headers).result |
    ConvertTo-Json -Depth 12

if ($SendReading) {
    $body = @{
        deviceId = $SensorDeviceId
        metricType = 'TEMPERATURE'
        value = 27.8
        unit = '°C'
        observedAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.ffffffzzz')
    } | ConvertTo-Json

    Write-Host 'Posting one mock sensor reading:' -ForegroundColor Cyan
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/dev/sensors/mock-reading" `
        -Headers $headers -ContentType 'application/json' -Body $body |
        ConvertTo-Json -Depth 12
}
