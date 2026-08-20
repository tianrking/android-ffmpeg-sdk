param(
    [string]$Distribution = "Ubuntu-26.04",
    [string]$Abis = "arm64-v8a,armeabi-v7a,x86_64,x86",
    [int]$Jobs = 0,
    [string]$NdkPath = "",
    [string]$WslCachePath = "",
    [string]$WslWorkPath = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$buildScript = Join-Path $repoRoot "native-runtime\scripts\build-android.sh"
if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) {
    throw "Build script was not found: $buildScript"
}

function Convert-ToWslPath([string]$Path) {
    $converted = & wsl.exe -d $Distribution -- wslpath -a $Path
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($converted)) {
        throw "Unable to convert Windows path for WSL: $Path"
    }
    return $converted.Trim()
}

$wslScript = Convert-ToWslPath $buildScript
if ([string]::IsNullOrWhiteSpace($WslCachePath) -or
    [string]::IsNullOrWhiteSpace($WslWorkPath)) {
    $wslHome = (& wsl.exe -d $Distribution -- sh -lc 'printf %s "$HOME"').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslHome)) {
        throw "Unable to resolve the home directory inside WSL distribution $Distribution"
    }
    if ([string]::IsNullOrWhiteSpace($WslCachePath)) {
        $WslCachePath = "$wslHome/.cache/ffmpeg-android-official"
    }
    if ([string]::IsNullOrWhiteSpace($WslWorkPath)) {
        $WslWorkPath = "$wslHome/.cache/ffmpeg-android-build"
    }
}

$arguments = @(
    "-d", $Distribution, "--", "bash", $wslScript,
    "--abis", $Abis,
    "--cache", $WslCachePath,
    "--work", $WslWorkPath
)
if ($Jobs -gt 0) {
    $arguments += @("--jobs", $Jobs.ToString([Globalization.CultureInfo]::InvariantCulture))
}
if (-not [string]::IsNullOrWhiteSpace($NdkPath)) {
    $resolvedNdk = (Resolve-Path -LiteralPath $NdkPath).Path
    $arguments += @("--ndk", (Convert-ToWslPath $resolvedNdk))
}

& wsl.exe @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Official FFmpeg Android build failed with exit code $LASTEXITCODE"
}
