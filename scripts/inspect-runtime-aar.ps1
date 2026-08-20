param(
    [Parameter(Mandatory = $true)]
    [string]$Aar
)

$ErrorActionPreference = "Stop"
$aarPath = (Resolve-Path -LiteralPath $Aar).Path
$file = Get-Item -LiteralPath $aarPath
$hash = Get-FileHash -LiteralPath $aarPath -Algorithm SHA256
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$extractPath = Join-Path $tempBase ("ffmpeg-android-aar-" + [Guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Path $extractPath | Out-Null
    Expand-Archive -LiteralPath $aarPath -DestinationPath $extractPath
    $libraries = Get-ChildItem -LiteralPath (Join-Path $extractPath "jni") -Recurse -Filter "*.so"

    [PSCustomObject]@{
        Path = $aarPath
        SizeBytes = $file.Length
        Sha256 = $hash.Hash
        Abis = @($libraries | ForEach-Object { $_.Directory.Name } | Sort-Object -Unique)
        NativeLibraries = @(
            $libraries | ForEach-Object {
                [PSCustomObject]@{
                    Abi = $_.Directory.Name
                    Name = $_.Name
                    SizeBytes = $_.Length
                    Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
                }
            }
        )
    } | ConvertTo-Json -Depth 5
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
