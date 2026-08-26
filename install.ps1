$ErrorActionPreference = "Stop"

$repository = if ($env:KTC_TO_GRADLE_REPOSITORY) { $env:KTC_TO_GRADLE_REPOSITORY } else { "Heapy/ktc-to-gradle" }
$version = if ($env:KTC_TO_GRADLE_VERSION) { $env:KTC_TO_GRADLE_VERSION } else { "latest" }
$downloadRoot = if ($env:KTC_TO_GRADLE_DOWNLOAD_ROOT) { $env:KTC_TO_GRADLE_DOWNLOAD_ROOT } else { "https://github.com/$repository/releases" }
$installDir = if ($env:KTC_TO_GRADLE_INSTALL_DIR) {
    $env:KTC_TO_GRADLE_INSTALL_DIR
} else {
    Join-Path $env:LOCALAPPDATA "Programs\ktc-to-gradle"
}

$asset = "ktc-to-gradle-windows-x64.tar.gz"
if ($version -eq "latest") {
    $baseUrl = "$downloadRoot/latest/download"
} else {
    $tag = if ($version.StartsWith("v")) { $version } else { "v$version" }
    if ($tag -notmatch "^v[A-Za-z0-9._+-]+$") {
        throw "Invalid release tag '$tag'"
    }
    $baseUrl = "$downloadRoot/download/$tag"
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ktc-to-gradle-install-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tempDir | Out-Null

try {
    $archive = Join-Path $tempDir $asset
    $checksumFile = "$archive.sha256"
    Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$asset" -OutFile $archive
    Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$asset.sha256" -OutFile $checksumFile

    $expected = ((Get-Content -Raw $checksumFile) -split "\s+")[0].ToLowerInvariant()
    $actual = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) {
        throw "SHA-256 checksum mismatch: expected $expected, got $actual"
    }

    & tar.exe -xzf $archive -C $tempDir
    if ($LASTEXITCODE -ne 0) {
        throw "Could not extract $asset"
    }

    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    $destination = Join-Path $installDir "ktc-to-gradle.exe"
    Copy-Item -Force (Join-Path $tempDir "ktc-to-gradle.exe") $destination

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $pathEntries = @($userPath -split ";" | Where-Object { $_ })
    if ($pathEntries -notcontains $installDir) {
        $newPath = (@($pathEntries) + $installDir) -join ";"
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "Added $installDir to the user PATH. Open a new terminal before running the command."
    }

    Write-Host "Installed ktc-to-gradle native binary to $destination"
} finally {
    Remove-Item -Recurse -Force $tempDir
}
