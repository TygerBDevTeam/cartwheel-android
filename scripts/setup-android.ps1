$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
$repository = Join-Path $toolRoot 'android-repository.xml'
if (-not (Test-Path -LiteralPath $repository)) {
    Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-1.xml' -OutFile $repository
}
[xml]$metadata = Get-Content -Raw -LiteralPath $repository
$package = $metadata.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;19.0']")
$archive = $package.SelectSingleNode(".//*[local-name()='archive'][*[local-name()='host-os' and text()='windows']]")
$url = $archive.SelectSingleNode(".//*[local-name()='url']").InnerText
$checksum = $archive.SelectSingleNode(".//*[local-name()='checksum']").InnerText
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $zipPath = Join-Path $toolRoot 'commandline-tools.zip'
    Invoke-WebRequest "https://dl.google.com/android/repository/$url" -OutFile $zipPath
    if ((Get-FileHash -LiteralPath $zipPath -Algorithm SHA1).Hash.ToLowerInvariant() -ne $checksum.ToLowerInvariant()) { throw 'Android tools checksum does not match Google metadata.' }
    Expand-Archive -LiteralPath $zipPath -DestinationPath (Join-Path $toolRoot 'sdk-unpacked') -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'cmdline-tools') | Out-Null
    Move-Item -LiteralPath (Join-Path $toolRoot 'sdk-unpacked\cmdline-tools') -Destination (Join-Path $sdkRoot 'cmdline-tools\latest')
}
1..20 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses
if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }
& $sdkManager "--sdk_root=$sdkRoot" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
$sdkProperty = $sdkRoot.Replace('\', '/').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value "sdk.dir=$sdkProperty" -Encoding ascii
Write-Output 'Android build tools are ready.'
