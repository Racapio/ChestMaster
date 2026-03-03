param(
    [string]$GradleTask = "build"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $projectRoot "gradlew.bat"
$wrapperProps = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.properties"
$propsFile = Join-Path $projectRoot "gradle.properties"
$libsDir = Join-Path $projectRoot "build\libs"
$outDir = Join-Path $projectRoot "dist\multi-version"

if (!(Test-Path $gradle)) {
    throw "gradlew.bat not found at $gradle"
}

if (!(Test-Path $wrapperProps)) {
    throw "gradle-wrapper.properties not found at $wrapperProps"
}

if (!(Test-Path $propsFile)) {
    throw "gradle.properties not found at $propsFile"
}

$distributionLine = Select-String -Path $wrapperProps -Pattern '^\s*distributionUrl\s*=\s*(.+)$' | Select-Object -First 1
if ($null -eq $distributionLine) {
    throw "Could not read distributionUrl from $wrapperProps"
}
$distributionUrl = $distributionLine.Matches[0].Groups[1].Value.Trim() -replace '\\:','\:'
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

$modVersionLine = Select-String -Path $propsFile -Pattern '^\s*mod_version\s*=\s*(.+)$' | Select-Object -First 1
if ($null -eq $modVersionLine) {
    throw "Could not read mod_version from gradle.properties"
}
$rawVersion = $modVersionLine.Matches[0].Groups[1].Value.Trim()
$baseVersion = $rawVersion -replace '\+mc\d+\.\d+\.\d+$', ''

$variants = @(
    @{
        Minecraft = "1.21.11"
        FabricApi = "0.141.3+1.21.11"
        KotlinLoader = "1.13.9+kotlin.2.3.10"
    },
    @{
        Minecraft = "1.21.10"
        FabricApi = "0.138.0+1.21.10"
        KotlinLoader = "1.13.8+kotlin.2.3.0"
    }
)

foreach ($variant in $variants) {
    $mc = $variant.Minecraft
    $fabricApi = $variant.FabricApi
    $kotlinLoader = $variant.KotlinLoader
    $variantVersion = "$baseVersion+mc$mc"

    Write-Host "=== Building ChestMaster for Minecraft $mc (fabric-api $fabricApi, fabric-language-kotlin $kotlinLoader) ===" -ForegroundColor Cyan

    Push-Location $projectRoot
    try {
        & $gradleCommand clean $GradleTask "-Pmod_version=$variantVersion" "-Pminecraft_version=$mc" "-Pfabric_version=$fabricApi" "-Pkotlin_loader_version=$kotlinLoader"
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed for Minecraft $mc"
        }
    } finally {
        Pop-Location
    }

    $mainJar = Join-Path $libsDir "chestmaster-$variantVersion.jar"
    $sourcesJar = Join-Path $libsDir "chestmaster-$variantVersion-sources.jar"

    if (!(Test-Path $mainJar)) {
        throw "Expected jar not found: $mainJar"
    }

    # Validate embedded metadata so wrong-target jars are caught immediately.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($mainJar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq "fabric.mod.json" } | Select-Object -First 1
        if ($null -eq $entry) {
            throw "fabric.mod.json not found in $mainJar"
        }

        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            $modJson = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Close()
        }

        if ($modJson.version -ne $variantVersion) {
            throw "Version mismatch in ${mainJar}: expected '$variantVersion', got '$($modJson.version)'"
        }
        if ($modJson.depends.minecraft -ne $mc) {
            throw "Minecraft dependency mismatch in ${mainJar}: expected '$mc', got '$($modJson.depends.minecraft)'"
        }
        if ($modJson.depends.'fabric-language-kotlin' -ne ">=$kotlinLoader") {
            throw "fabric-language-kotlin dependency mismatch in ${mainJar}: expected '>=$kotlinLoader', got '$($modJson.depends.'fabric-language-kotlin')'"
        }
    } finally {
        $zip.Dispose()
    }

    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    Copy-Item $mainJar -Destination (Join-Path $outDir (Split-Path $mainJar -Leaf)) -Force

    if (Test-Path $sourcesJar) {
        Copy-Item $sourcesJar -Destination (Join-Path $outDir (Split-Path $sourcesJar -Leaf)) -Force
    }
}

Write-Host "Done. Built jars are in: $outDir" -ForegroundColor Green
