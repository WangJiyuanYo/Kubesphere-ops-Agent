param([switch]$Restart)
$ErrorActionPreference = 'Stop'
if (!$Restart) { throw '此脚本将更新本机8100的SQLite/DEMO服务。请显式传入 -Restart。' }
$inspectionRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$inspectionSource = Join-Path $inspectionRoot 'target/deployment-ops-agent-0.1.0-SNAPSHOT.jar'
$inspectionRuntime = Join-Path $inspectionRoot 'runtime/deployment-ops-agent-20261113-local-platform.jar'
$inspectionManifest = Join-Path $inspectionRoot 'runtime/chat-inspection-verified-build.json'
$inspectionJava = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe'
foreach ($inspectionFile in @($inspectionSource, $inspectionRuntime, $inspectionManifest, $inspectionJava)) {
    if (!(Test-Path -LiteralPath $inspectionFile)) { throw ('缺少更新前提文件，未停止服务：' + $inspectionFile) }
}
$inspectionBuild = Get-Content -LiteralPath $inspectionManifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ((Get-FileHash -LiteralPath $inspectionSource -Algorithm SHA256).Hash.ToLowerInvariant() -ne $inspectionBuild.sha256) {
    throw '构建包与已验证版本不同，未停止服务；请重新验证后更新构建清单。'
}
$inspectionListeners = @(Get-NetTCPConnection -LocalPort 8100 -State Listen)
if ($inspectionListeners.Count -ne 1) { throw '8100监听身份不唯一，未停止服务。' }
$inspectionOld = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $inspectionListeners[0].OwningProcess)
$inspectionCommand = $inspectionOld.CommandLine.Replace('\', '/')
if ($inspectionOld.Name -ne 'java.exe' -or !$inspectionCommand.Contains($inspectionRuntime.Replace('\', '/')) -or
    !$inspectionCommand.Contains('--spring.profiles.active=sqlite') -or
    !$inspectionCommand.Contains((Join-Path $inspectionRoot 'data/agent.db').Replace('\', '/')) -or
    !$inspectionCommand.Contains((Join-Path $inspectionRoot 'data/console-master.key').Replace('\', '/')) -or
    $inspectionCommand.Contains('--agent.mode=KUBERNETES') -or $inspectionCommand.Contains('--agent.platform-catalog.enabled=true')) {
    throw '8100不是预期的本机SQLite/DEMO/目录关闭进程，未停止服务。'
}
$inspectionBackup = & python (Join-Path $PSScriptRoot 'backup-local-store.py') $inspectionRoot
if ($LASTEXITCODE -ne 0) { throw '活动工作检查或配套在线备份失败，未停止服务。' }
$inspectionBackup | Write-Host
$inspectionPrior = Join-Path $inspectionRoot ('runtime/agent-before-readonly-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jar')
Copy-Item -LiteralPath $inspectionRuntime -Destination $inspectionPrior
$inspectionCurrent = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $inspectionOld.ProcessId)
if (!$inspectionCurrent -or $inspectionCurrent.CreationDate -ne $inspectionOld.CreationDate -or
    $inspectionCurrent.CommandLine -ne $inspectionOld.CommandLine) { throw '原进程身份已变化，未停止其他进程。' }
Stop-Process -Id $inspectionOld.ProcessId
Wait-Process -Id $inspectionOld.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $inspectionSource -Destination $inspectionRuntime -Force
$inspectionArguments = @('-jar', ('"' + $inspectionRuntime + '"'), '--spring.profiles.active=sqlite',
    '--server.address=127.0.0.1', '--server.port=8100', '--agent.mode=DEMO',
    ('"--agent.sqlite.file=' + (Join-Path $inspectionRoot 'data/agent.db') + '"'),
    ('"--agent.console.key-file=' + (Join-Path $inspectionRoot 'data/console-master.key') + '"'),
    '--agent.platform-catalog.enabled=false', '--agent.kubectl.enabled=false')
$inspectionChild = Start-Process -FilePath $inspectionJava -ArgumentList $inspectionArguments -WorkingDirectory $inspectionRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $inspectionRoot 'runtime/chat-inspection-agent.log') `
    -RedirectStandardError (Join-Path $inspectionRoot 'runtime/chat-inspection-agent-error.log')
$inspectionDeadline = [DateTime]::UtcNow.AddSeconds(45)
while ([DateTime]::UtcNow -lt $inspectionDeadline) {
    $inspectionChild.Refresh()
    if ($inspectionChild.HasExited) { throw '新版进程已退出；请检查runtime启动日志。原Jar与配套数据库备份已保留。' }
    try {
        $inspectionHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:8100/actuator/health' -TimeoutSec 2
        if ($inspectionHealth.status -eq 'UP') {
            Write-Host ('新版Agent已启动，PID=' + $inspectionChild.Id + '；请刷新8100页面并重新登录。')
            Write-Host '选择连接并勾选“自动只读排查”，回复下方可查看实际采证请求。'
            return
        }
    } catch { }
    Start-Sleep -Milliseconds 400
}
throw '健康观测超时；仍存活的进程保持运行，未自动重复重启。请检查runtime启动日志。'
