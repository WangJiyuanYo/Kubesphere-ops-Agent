param(
    [string]$BaseUrl = 'http://127.0.0.1:8098',
    [string]$Username = 'operator',
    [string]$Password = 'demo-local-only'
)
$ErrorActionPreference = 'Stop'
$opsDemoAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:${Password}"))
$opsDemoHeaders = @{ Authorization = "Basic $opsDemoAuth" }
$opsDemoRequest = Get-Content -LiteralPath "$PSScriptRoot/../examples/start-demo.json" -Raw | ConvertFrom-Json
$opsDemoRequest.requestKey = 'demo_' + [guid]::NewGuid().ToString('N')
$opsDemoRun = Invoke-RestMethod -Uri "$BaseUrl/api/runs" -Method Post -Headers $opsDemoHeaders -ContentType 'application/json' -Body ($opsDemoRequest | ConvertTo-Json -Depth 8)
Write-Host "任务已创建：$($opsDemoRun.id)"
$opsDemoDeadline = [DateTime]::UtcNow.AddMinutes(3)
$opsDemoPrevious = ''
while ([DateTime]::UtcNow -lt $opsDemoDeadline) {
    $opsDemoRun = Invoke-RestMethod -Uri "$BaseUrl/api/runs/$($opsDemoRun.id)" -Headers $opsDemoHeaders
    if ($opsDemoRun.state -ne $opsDemoPrevious) {
        Write-Host "状态：$($opsDemoRun.state)，恢复次数：$($opsDemoRun.attempts)"
        $opsDemoPrevious = $opsDemoRun.state
    }
    if ($opsDemoRun.state -eq 'SUCCEEDED') {
        $opsDemoRun | ConvertTo-Json -Depth 10
        exit 0
    }
    if ($opsDemoRun.state -in @('FAILED', 'MANUAL_REQUIRED', 'CANCELLED')) {
        throw "任务已停止：$($opsDemoRun.state)"
    }
    Start-Sleep -Seconds 2
}
throw '演示运行超时，请查询任务详情'
