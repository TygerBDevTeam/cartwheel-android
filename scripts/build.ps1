param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tempRoot = Join-Path $projectRoot '.tools\tmp'
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
$previousTestRepo = $env:CARTWHEEL_TEST_MAVEN_REPO
try {
    $env:TEMP = $tempRoot
    $env:TMP = $tempRoot
    $testRepo = Join-Path $projectRoot '.tools\m2'
    New-Item -ItemType Directory -Force -Path $testRepo | Out-Null
    $fileSystem = New-Object -ComObject Scripting.FileSystemObject
    $env:CARTWHEEL_TEST_MAVEN_REPO = $fileSystem.GetFolder($testRepo).ShortPath
    Push-Location $projectRoot
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --no-daemon --gradle-user-home (Join-Path $projectRoot '.tools\gradle-home') --console=plain
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    $env:CARTWHEEL_TEST_MAVEN_REPO = $previousTestRepo
}
exit $buildExit
