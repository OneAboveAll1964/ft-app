param(
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:assembleRelease')
)

$ErrorActionPreference = 'Stop'
$repoDir = Split-Path -Parent $PSScriptRoot
$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS

try {
    $env:JAVA_HOME = Join-Path $PSScriptRoot 'jdk17\jdk-17.0.20.1+1'
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $pipeDir = Join-Path $repoDir '.build-tmp'
    New-Item -ItemType Directory -Path $pipeDir -Force | Out-Null
    # Apply to the wrapper, Gradle daemon, compiler and test JVMs.
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=`"$pipeDir`"".Trim()
    Push-Location $repoDir
    try {
        & "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9\gradle-8.14\bin\gradle.bat" @Tasks -g "$PSScriptRoot\gradle-review" --project-cache-dir "$PSScriptRoot\gradle-review\project-cache" --console=plain --no-daemon --no-watch-fs '-Dorg.gradle.native=false'
        $buildExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}

exit $buildExitCode
