param(
    [string]$GradleTask = "build"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $projectRoot "gradlew.bat"
$wrapperProps = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.properties"
$versionsDir = Join-Path $projectRoot "versions"
$libsDir = Join-Path $projectRoot "build\libs"
$outDir = Join-Path $projectRoot "dist\multi-version"

if (!(Test-Path $gradle)) {
    throw "gradlew.bat not found at $gradle"
}

if (!(Test-Path $wrapperProps)) {
    throw "gradle-wrapper.properties not found at $wrapperProps"
}

$distributionLine = Select-String -Path $wrapperProps -Pattern '^\s*distributionUrl\s*=\s*(.+)$' | Select-Object -First 1
if ($null -eq $distributionLine) {
    throw "Could not read distributionUrl from $wrapperProps"
}
$distributionUrl = $distributionLine.Matches[0].Groups[1].Value.Trim() -replace '\\:',':'
$wrapperVersionMatch = [regex]::Match($distributionUrl, 'gradle-([0-9.]+)-bin\.zip')
if (!$wrapperVersionMatch.Success) {
    throw "Could not parse Gradle version from distributionUrl: $distributionUrl"
}
$wrapperVersion = $wrapperVersionMatch.Groups[1].Value

$localGradleBat = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$wrapperVersion-bin") -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName

# Prefer local Gradle binary if available to avoid wrapper download issues.
$gradleCommand = if ($localGradleBat) { $localGradleBat } else { $gradle }
Write-Host "Using Gradle command: $gradleCommand" -ForegroundColor DarkCyan

# One build per version profile in versions/*.properties (26.1.2, 26.2, ...).
$profiles = Get-ChildItem -Path $versionsDir -Filter "*.properties" |
    Sort-Object Name |
    ForEach-Object { $_.BaseName }

if ($profiles.Count -eq 0) {
    throw "No version profiles found in $versionsDir"
}

foreach ($mc in $profiles) {
    Write-Host "=== Building ChestMaster for Minecraft $mc ===" -ForegroundColor Cyan

    Push-Location $projectRoot
    try {
        & $gradleCommand clean $GradleTask "-PmcVersion=$mc"
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed for Minecraft $mc"
        }
    } finally {
        Pop-Location
    }

    $mainJar = Get-ChildItem -Path $libsDir -Filter "chestmaster-mc$mc-*.jar" |
        Where-Object { $_.Name -notlike "*-sources.jar" } |
        Select-Object -First 1

    if ($null -eq $mainJar) {
        throw "Expected jar not found in ${libsDir} for Minecraft $mc"
    }

    # Validate embedded metadata so wrong-target jars are caught immediately.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($mainJar.FullName)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq "fabric.mod.json" } | Select-Object -First 1
        if ($null -eq $entry) {
            throw "fabric.mod.json not found in $($mainJar.FullName)"
        }

        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            $modJson = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Close()
        }

        if ($modJson.version -notlike "*+mc$mc") {
            throw "Version mismatch in $($mainJar.Name): expected suffix '+mc$mc', got '$($modJson.version)'"
        }
        # The minecraft dependency is a range covering the patch series (e.g. ">=26.1 <26.2").
        $mcMinor = ($mc.Split(".")[0..1] -join ".")
        if ($modJson.depends.minecraft -notlike "*$mcMinor*") {
            throw "Minecraft dependency mismatch in $($mainJar.Name): expected range around '$mcMinor', got '$($modJson.depends.minecraft)'"
        }
    } finally {
        $zip.Dispose()
    }

    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    Copy-Item $mainJar.FullName -Destination (Join-Path $outDir $mainJar.Name) -Force

    $sourcesJar = Join-Path $libsDir ($mainJar.Name -replace '\.jar$', '-sources.jar')
    if (Test-Path $sourcesJar) {
        Copy-Item $sourcesJar -Destination (Join-Path $outDir (Split-Path $sourcesJar -Leaf)) -Force
    }
}

Write-Host "Done. Built jars are in: $outDir" -ForegroundColor Green
