param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-p]{32}$')]
    [string]$ExtensionId,
    [Parameter(Mandatory = $true)]
    [string]$NativeHostPath,
    [ValidateSet('chrome', 'brave', 'edge')]
    [string]$Browser = 'chrome',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$hostLauncher = (Resolve-Path -LiteralPath $NativeHostPath).Path
$distribution = Split-Path (Split-Path $hostLauncher -Parent) -Parent
if (!(Test-Path -LiteralPath (Join-Path $distribution 'lib') -PathType Container)) {
    throw 'Expected build\install\GrabX\bin\grabx-native-host.bat. Run .\gradlew.bat installDist on Windows first.'
}
if (!(Test-Path -LiteralPath (Join-Path $distribution 'lib\GrabX.jar')) -or
    !(Get-ChildItem -LiteralPath (Join-Path $distribution 'lib') -Filter 'javafx-graphics-*-win*.jar')) {
    throw 'Build installDist on Windows first. A macOS/Linux distribution has incompatible JavaFX libraries.'
}
if ($JavaHome) {
    $javaExecutable = (Resolve-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe')).Path
} else {
    $javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
}
$javaVersion = & $javaExecutable --version
if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch '(?:openjdk|java) (\d+)' -or [int]$Matches[1] -lt 21) {
    throw 'GrabX requires Java 21 or newer. Pass -JavaHome with the JDK used by IntelliJ.'
}

# Chrome does not inherit IntelliJ's selected JDK. Pin the native host to the
# installed JDK and stage the complete Windows distribution beside its libraries.
$target = Join-Path $env:LOCALAPPDATA 'GrabX\browser-bridge'
$appHome = Join-Path $target 'app'
New-Item -ItemType Directory -Path $target -Force | Out-Null
if ([IO.Path]::GetFullPath($distribution).TrimEnd('\') -ne [IO.Path]::GetFullPath($appHome).TrimEnd('\')) {
    $staging = Join-Path $target ('staging-' + [Guid]::NewGuid().ToString('N'))
    $backup = Join-Path $target ('previous-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    try {
        Get-ChildItem -LiteralPath $distribution | Copy-Item -Destination $staging -Recurse -Force
        if (Test-Path -LiteralPath $appHome) { Move-Item -LiteralPath $appHome -Destination $backup }
        try { Move-Item -LiteralPath $staging -Destination $appHome }
        catch {
            if (Test-Path -LiteralPath $backup) { Move-Item -LiteralPath $backup -Destination $appHome }
            throw
        }
        if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Recurse -Force }
    } finally {
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    }
}
$wrapper = Join-Path $target 'grabx-native-host.cmd'
# Percent signs in literal batch values must be doubled. Quoting preserves spaces
# and ampersands in user profile / JDK paths. Delayed expansion stays disabled.
$batchJava = $javaExecutable.Replace('%', '%%')
$batchHome = $appHome.Replace('%', '%%')
$batch = "@echo off`r`nchcp 65001 >nul`r`nsetlocal DisableDelayedExpansion`r`nset `"GRABX_APP_HOME=$batchHome`"`r`n`"$batchJava`" -cp `"$batchHome\lib\*`" com.grabx.app.grabx.browser.BrowserNativeHostMain`r`n"
[IO.File]::WriteAllText($wrapper, $batch, (New-Object Text.UTF8Encoding($false)))

$manifestPath = Join-Path $target 'com.grabx.browser_bridge.json'
$manifest = @{
    name = 'com.grabx.browser_bridge'
    description = 'GrabX browser bridge'
    path = $wrapper
    type = 'stdio'
    allowed_origins = @("chrome-extension://$ExtensionId/")
} | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText($manifestPath, $manifest, (New-Object Text.UTF8Encoding($false)))

$registryRoots = switch ($Browser) {
    'chrome' { @('HKCU:\Software\Google\Chrome\NativeMessagingHosts') }
    'brave' { @('HKCU:\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts', 'HKCU:\Software\Google\Chrome\NativeMessagingHosts') }
    'edge' { @('HKCU:\Software\Microsoft\Edge\NativeMessagingHosts') }
}
foreach ($root in $registryRoots) {
    $key = New-Item -Path "$root\com.grabx.browser_bridge" -Force
    $key.SetValue('', $manifestPath, [Microsoft.Win32.RegistryValueKind]::String)
    $key.Close()
}
Write-Host "Installed GrabX bridge for $Browser (current user)."
Write-Host "Java: $javaExecutable"
Write-Host 'Restart the browser and reload the extension. Re-run after rebuilding GrabX.'
