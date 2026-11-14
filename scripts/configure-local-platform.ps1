param(
    [string]$BaseUrl = 'http://127.0.0.1:8081/api/admin/integrations/kubesphere/agent',
    [string]$SecretDirectory = (Join-Path $PSScriptRoot '../secrets/local-platform')
)
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw '此脚本使用 Windows 当前用户加密，请在 Windows 中运行。' }
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
Add-Type -AssemblyName System.Net.Http
$platformUri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$platformUri) -or
    $platformUri.Scheme -ne 'http' -or $platformUri.Host -notin @('127.0.0.1', '[::1]') -or
    $BaseUrl -notmatch '^http://(127\.0\.0\.1|\[::1\])(?::[0-9]{1,5})?(/|$)' -or
    $platformUri.UserInfo -or $platformUri.Query -or $platformUri.Fragment -or $BaseUrl.EndsWith('/')) {
    throw '本机接入只接受不带凭据、查询参数或末尾斜杠的回环 IP HTTP 地址。'
}
$platformSecureToken = $null
$platformBstr = [IntPtr]::Zero
$platformClient = $null
$platformHandler = $null
try {
    $platformSecureToken = if ($env:PLATFORM_TOKEN) {
        ConvertTo-SecureString -String $env:PLATFORM_TOKEN -AsPlainText -Force
    } else {
        Read-Host '请输入当前本机租户平台登录 Token（仅 Token，不含 Bearer，不会回显）' -AsSecureString
    }
    $platformBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($platformSecureToken)
    $platformTokenValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($platformBstr)
    if ([string]::IsNullOrWhiteSpace($platformTokenValue) -or $platformTokenValue -match '\s' -or $platformTokenValue.Length -gt 8192) {
        throw 'Token 为空或格式无效，未保存。'
    }
    $platformHandler = [Net.Http.HttpClientHandler]::new()
    $platformHandler.AllowAutoRedirect = $false
    $platformHandler.UseProxy = $false
    $platformClient = [Net.Http.HttpClient]::new($platformHandler)
    $platformClient.Timeout = [TimeSpan]::FromSeconds(10)
    $platformClient.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $platformTokenValue)
    $platformClient.DefaultRequestHeaders.Accept.ParseAdd('application/json')
    $platformResponse = $null
    try {
        $platformResponse = $platformClient.GetAsync($BaseUrl + '/provisioning-instances?page=1', [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $platformHttpStatus = [int]$platformResponse.StatusCode
        if ($platformHttpStatus -in @(401, 403)) { throw '平台身份无效或没有目录查看权限，请重新登录并核对账号授权。' }
        if ($platformHttpStatus -eq 404) { throw '平台目录接口不存在，请先在本机后端加载 Agent 契约。' }
        if ($platformHttpStatus -ne 200) { throw "平台目录暂不可用（HTTP $platformHttpStatus），未保存凭据。" }
        $platformStream = $platformResponse.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $platformBody = [IO.MemoryStream]::new()
        try {
            $platformBuffer = [byte[]]::new(8192)
            $platformReadDeadline = [DateTime]::UtcNow.AddSeconds(10)
            while ($true) {
                if ([DateTime]::UtcNow -ge $platformReadDeadline) { throw '平台目录读取超时，未保存凭据。' }
                $platformRead = $platformStream.ReadAsync($platformBuffer, 0, $platformBuffer.Length)
                if (-not $platformRead.Wait(10000)) { throw '平台目录读取超时，未保存凭据。' }
                $platformReadSize = $platformRead.GetAwaiter().GetResult()
                if ($platformReadSize -eq 0) { break }
                if ($platformBody.Length + $platformReadSize -gt 2MB) { throw '平台目录响应超过读取上限，未保存凭据。' }
                $platformBody.Write($platformBuffer, 0, $platformReadSize)
            }
            $platformJson = [Text.Encoding]::UTF8.GetString($platformBody.ToArray()) | ConvertFrom-Json
        } finally { $platformBody.Dispose(); $platformStream.Dispose() }
        if ($platformJson.code -ne 200 -or $null -eq $platformJson.data -or
            $platformJson.data.items -isnot [Array] -or @($platformJson.data.items).Count -gt 100 -or $platformJson.data.hasMore -isnot [bool]) {
            throw '平台目录响应不兼容，未保存凭据。'
        }
        foreach ($platformEntry in $platformJson.data.items) {
            if ($platformEntry.id -isnot [string] -or $platformEntry.id -notmatch '^[1-9][0-9]{0,18}$' -or
                $platformEntry.siteId -isnot [string] -or $platformEntry.siteId -notmatch '^[1-9][0-9]{0,18}$' -or
                [string]::IsNullOrWhiteSpace($platformEntry.version)) { throw '平台目录响应不兼容，未保存凭据。' }
        }
    } catch {
        # 不打印可能包含响应正文、Token 或上游细节的异常对象。
        $platformSafeErrors = @('平台身份无效或没有目录查看权限，请重新登录并核对账号授权。',
            '平台目录接口不存在，请先在本机后端加载 Agent 契约。',
            '平台目录读取超时，未保存凭据。', '平台目录响应超过读取上限，未保存凭据。', '平台目录响应不兼容，未保存凭据。')
        if ($_.Exception.Message -in $platformSafeErrors -or $_.Exception.Message -match '^平台目录暂不可用（HTTP [0-9]{3}），未保存凭据。$') {
            throw $_.Exception.Message
        }
        throw '平台目录请求失败，请检查本机后端、网络及接口格式，未保存凭据。'
    } finally { if ($platformResponse) { $platformResponse.Dispose() } }

    $platformSecretPath = [IO.Path]::GetFullPath($SecretDirectory)
    [IO.Directory]::CreateDirectory($platformSecretPath) | Out-Null
    $platformCurrentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $platformAcl = Get-Acl -LiteralPath $platformSecretPath
    if ($platformAcl.GetOwner([Security.Principal.SecurityIdentifier]) -ne $platformCurrentUser) {
        throw '凭据目录不属于当前 Windows 用户，未保存凭据。'
    }
    $platformAcl.SetAccessRuleProtection($true, $false)
    foreach ($platformExistingRule in @($platformAcl.Access)) { $platformAcl.RemoveAccessRuleSpecific($platformExistingRule) }
    $platformRule = [Security.AccessControl.FileSystemAccessRule]::new($platformCurrentUser,
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
        [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
    $platformAcl.AddAccessRule($platformRule)
    $platformDirectoryInfo = [IO.DirectoryInfo]::new($platformSecretPath)
    if ($PSVersionTable.PSEdition -eq 'Desktop') {
        $platformDirectoryInfo.SetAccessControl($platformAcl)
    } else {
        [IO.FileSystemAclExtensions]::SetAccessControl($platformDirectoryInfo, $platformAcl)
    }
    $platformEncrypted = ConvertFrom-SecureString -SecureString $platformSecureToken
    $platformTokenFile = Join-Path $platformSecretPath 'token.dpapi'
    $platformTempFile = Join-Path $platformSecretPath ('token-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($platformTempFile, $platformEncrypted, [Text.Encoding]::UTF8)
        Move-Item -LiteralPath $platformTempFile -Destination $platformTokenFile -Force
    } finally { if (Test-Path -LiteralPath $platformTempFile) { Remove-Item -LiteralPath $platformTempFile } }
    [IO.File]::WriteAllText((Join-Path $platformSecretPath 'base-url.txt'), $BaseUrl, [Text.Encoding]::UTF8)
    Write-Host "目录鉴权通过，当前页 $(@($platformJson.data.items).Count) 条记录。"
    Write-Host "Token 已按当前 Windows 用户加密保存：$platformTokenFile"
    Write-Host '这是平台登录会话 Token；过期或后端重启后请重新登录并运行此脚本。'
} finally {
    $platformTokenValue = $null
    if ($platformBstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($platformBstr) }
    if ($platformSecureToken) { $platformSecureToken.Dispose() }
    if ($platformClient) { $platformClient.Dispose() } elseif ($platformHandler) { $platformHandler.Dispose() }
}
