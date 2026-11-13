param([string]$OutputName = 'FT-ICCOA-source.zip')
$ErrorActionPreference = 'Stop'
$repoDir = Split-Path -Parent $PSScriptRoot
Push-Location $repoDir
try {
    $sourcePaths = @(& git ls-files -co --exclude-standard -- app/src server/src docs gradle app/build.gradle.kts app/proguard-rules.pro server/build.gradle.kts build.gradle.kts settings.gradle.kts gradlew gradlew.bat README.md .gitignore) | Sort-Object -Unique
    if ($LASTEXITCODE -ne 0) { throw 'Failed to list source files' }
    Add-Type -AssemblyName System.IO.Compression
    $zipPath = Join-Path (Join-Path $repoDir 'dist') $OutputName
    $stream = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::Create)
    $archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($relative in $sourcePaths) {
            if ($relative -match '(?i)(^|/)(keystore\.properties|local\.properties|[^/]*\.(jks|keystore))$') { throw "Disallowed source path: $relative" }
            $filePath = Join-Path $repoDir $relative
            if (!(Test-Path -LiteralPath $filePath -PathType Leaf)) { continue }
            $entry = $archive.CreateEntry($relative, [System.IO.Compression.CompressionLevel]::Optimal)
            $input = [System.IO.File]::OpenRead($filePath)
            $output = $entry.Open()
            try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
        }
    } finally { $archive.Dispose(); $stream.Dispose() }
    Get-Item -LiteralPath $zipPath | Select-Object Name,Length | ConvertTo-Json
} finally { Pop-Location }
