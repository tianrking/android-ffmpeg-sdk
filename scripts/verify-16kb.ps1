param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,

    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkRoot,

    [string]$BuildToolsVersion = "36.0.0",

    [string]$NdkVersion
)

$ErrorActionPreference = "Stop"

$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$sdkPath = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path
$toolSuffix = if ($IsWindows -or $PSVersionTable.PSEdition -eq "Desktop") { ".exe" } else { "" }
$hostTag = if ($IsWindows -or $PSVersionTable.PSEdition -eq "Desktop") {
    "windows-x86_64"
} elseif ($IsMacOS) {
    "darwin-x86_64"
} else {
    "linux-x86_64"
}
$zipalign = Join-Path (Join-Path (Join-Path $sdkPath "build-tools") $BuildToolsVersion) ("zipalign" + $toolSuffix)

if (-not (Test-Path -LiteralPath $zipalign -PathType Leaf)) {
    throw "zipalign was not found at $zipalign"
}

Write-Host "Checking APK ZIP alignment: $apkPath"
& $zipalign -v -c -P 16 4 $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK 16 KB ZIP alignment verification failed"
}

if ([string]::IsNullOrWhiteSpace($NdkVersion)) {
    Write-Warning "No NDK version supplied; ELF LOAD alignment and GNU_RELRO were not checked."
    exit 0
}

$ndkRoot = Join-Path (Join-Path $sdkPath "ndk") $NdkVersion
$llvmBin = Join-Path (Join-Path (Join-Path $ndkRoot "toolchains") "llvm") "prebuilt"
$llvmBin = Join-Path (Join-Path $llvmBin $hostTag) "bin"
$objdump = Join-Path $llvmBin ("llvm-objdump" + $toolSuffix)
$readelf = Join-Path $llvmBin ("llvm-readelf" + $toolSuffix)

foreach ($tool in @($objdump, $readelf)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required NDK tool was not found: $tool"
    }
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$extractPath = Join-Path $tempBase ("ffmpeg-android-16kb-" + [Guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Path $extractPath | Out-Null
    Expand-Archive -LiteralPath $apkPath -DestinationPath $extractPath

    $libraries = Get-ChildItem -LiteralPath (Join-Path $extractPath "lib") -Recurse -Filter "*.so"
    if (-not $libraries) {
        throw "APK does not contain native shared libraries"
    }

    $failures = [Collections.Generic.List[string]]::new()
    foreach ($library in $libraries) {
        $failureCountBeforeLibrary = $failures.Count
        $programHeaders = & $objdump -p $library.FullName 2>&1
        if ($LASTEXITCODE -ne 0) {
            $failures.Add("$($library.FullName): llvm-objdump failed")
            continue
        }

        $loadLines = $programHeaders | Select-String -Pattern "^\s*LOAD\s"
        if (-not $loadLines) {
            $failures.Add("$($library.FullName): no LOAD segments found")
            continue
        }

        foreach ($line in $loadLines) {
            if ($line.Line -notmatch "align\s+2\*\*(\d+)") {
                $failures.Add("$($library.FullName): unrecognized LOAD alignment: $($line.Line.Trim())")
            } elseif ([int]$Matches[1] -lt 14) {
                $failures.Add("$($library.FullName): LOAD alignment is 2**$($Matches[1]), expected >= 2**14")
            }
        }

        $elfHeaders = & $readelf -l $library.FullName 2>&1
        if ($LASTEXITCODE -ne 0 -or ($elfHeaders -join "`n") -notmatch "GNU_RELRO") {
            $failures.Add("$($library.FullName): GNU_RELRO was not found")
        }

        if ($failures.Count -eq $failureCountBeforeLibrary) {
            Write-Host "PASS $($library.FullName.Substring($extractPath.Length + 1))"
        }
    }

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        throw "$($failures.Count) native verification check(s) failed"
    }

    Write-Host "Verification successful: ZIP alignment, ELF LOAD alignment, and GNU_RELRO."
}
finally {
    if (Test-Path -LiteralPath $extractPath) {
        $resolvedExtractPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $extractPath).Path)
        if (-not $resolvedExtractPath.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unexpected temporary path: $resolvedExtractPath"
        }
        Remove-Item -LiteralPath $resolvedExtractPath -Recurse -Force
    }
}
