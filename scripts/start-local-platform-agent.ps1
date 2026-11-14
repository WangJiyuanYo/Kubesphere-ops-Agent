param(
    [switch]$Restart,
    [string]$JavaExecutable = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe'
)
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw '此入口仅用于本机 Windows SQLite 联调。' }
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
Add-Type -AssemblyName System.Net.Http
$agentLocalRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$agentLocalJar = Join-Path $agentLocalRoot 'runtime/deployment-ops-agent-20261113-local-platform.jar'
$agentLocalTokenFile = Join-Path $agentLocalRoot 'secrets/local-platform/token.dpapi'
if (!(Test-Path -LiteralPath $JavaExecutable) -or !(Test-Path -LiteralPath $agentLocalJar)) { throw '未找到本机 JDK21 或已验证的运行包，未停止服务。' }
if (!(Test-Path -LiteralPath $agentLocalTokenFile)) { throw '请先运行 configure-local-platform.cmd 配置有效平台身份。' }
$agentLocalListeners = @(Get-NetTCPConnection -LocalPort 8100 -State Listen -ErrorAction SilentlyContinue)
if ($agentLocalListeners.Count -gt 1) { throw '8100监听身份不唯一，未停止服务。' }
$agentLocalPrevious = $null
if ($agentLocalListeners.Count -eq 1) {
    if (!$Restart) { throw 'Agent正在运行。需要加载目录配置时，请显式指定 -Restart。' }
    $agentLocalPrevious = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $agentLocalListeners[0].OwningProcess)
    if ($agentLocalPrevious.Name -ne 'java.exe' -or
        !$agentLocalPrevious.CommandLine.Replace('\', '/').Contains($agentLocalJar.Replace('\', '/')) -or
        !$agentLocalPrevious.CommandLine.Contains('--spring.profiles.active=sqlite')) {
        throw '8100不是本项目预期的本机SQLite进程，未停止服务。'
    }
}
$agentLocalSecure = Get-Content -LiteralPath $agentLocalTokenFile -Raw | ConvertTo-SecureString
$agentLocalPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($agentLocalSecure)
$agentLocalPreviousToken = $env:PLATFORM_TOKEN
try {
    $env:PLATFORM_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($agentLocalPtr)
    # 再次验证已有平台身份，失败时原Agent继续运行。
    & (Join-Path $PSScriptRoot 'configure-local-platform.ps1')
    $agentLocalBackup = & python (Join-Path $PSScriptRoot 'backup-local-store.py') $agentLocalRoot
    if ($LASTEXITCODE -ne 0) { throw '活动工作检查或配套备份失败，未停止服务。' }
    $agentLocalBackup | Write-Host
    if ($agentLocalPrevious) {
        $agentLocalCurrent = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $agentLocalPrevious.ProcessId)
        if (!$agentLocalCurrent -or $agentLocalCurrent.CreationDate -ne $agentLocalPrevious.CreationDate -or
            $agentLocalCurrent.CommandLine -ne $agentLocalPrevious.CommandLine) { throw '原进程身份已变化，未停止其他进程。' }
        Stop-Process -Id $agentLocalPrevious.ProcessId
        Wait-Process -Id $agentLocalPrevious.ProcessId -ErrorAction SilentlyContinue
    }
    $agentLocalArguments = @('-jar', ('"' + $agentLocalJar + '"'), '--spring.profiles.active=sqlite',
        '--server.address=127.0.0.1', '--server.port=8100', '--agent.mode=DEMO',
        ('"--agent.sqlite.file=' + (Join-Path $agentLocalRoot 'data/agent.db') + '"'),
        ('"--agent.console.key-file=' + (Join-Path $agentLocalRoot 'data/console-master.key') + '"'),
        '--agent.platform-catalog.enabled=true', '--agent.platform-catalog.allow-loopback-http=true',
        '--agent.platform-base-url=http://127.0.0.1:8081/api/admin/integrations/kubesphere/agent')
    $agentLocalChild = Start-Process -FilePath $JavaExecutable -ArgumentList $agentLocalArguments -WorkingDirectory $agentLocalRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $agentLocalRoot 'runtime/local-platform-agent.log') `
        -RedirectStandardError (Join-Path $agentLocalRoot 'runtime/local-platform-agent-error.log')
    $agentLocalHandler = [Net.Http.HttpClientHandler]::new()
    $agentLocalHandler.AllowAutoRedirect = $false
    $agentLocalHandler.UseProxy = $false
    $agentLocalClient = [Net.Http.HttpClient]::new($agentLocalHandler)
    $agentLocalClient.Timeout = [TimeSpan]::FromSeconds(2)
    try {
        $agentLocalDeadline = [DateTime]::UtcNow.AddSeconds(45)
        while ([DateTime]::UtcNow -lt $agentLocalDeadline) {
            $agentLocalChild.Refresh()
            if ($agentLocalChild.HasExited) { throw 'Agent进程已退出，请检查runtime目录中的启动日志。' }
            try {
                $agentLocalHealth = $agentLocalClient.GetStringAsync('http://127.0.0.1:8100/actuator/health').GetAwaiter().GetResult() | ConvertFrom-Json
                if ($agentLocalHealth.status -eq 'UP') {
                    Write-Host "Agent已启动，PID=$($agentLocalChild.Id)，地址：http://127.0.0.1:8100/"
                    Write-Host '只读平台目录已启用；真实恢复仍保持DEMO。请在部署任务页查看目录同步结果。'
                    return
                }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        throw 'Agent进程仍在运行，但健康状态尚未确认；请检查日志，不要重复启动。'
    } finally { $agentLocalClient.Dispose() }
} finally {
    $env:PLATFORM_TOKEN = $agentLocalPreviousToken
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($agentLocalPtr)
    $agentLocalSecure.Dispose()
}
