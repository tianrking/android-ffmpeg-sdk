[CmdletBinding()]
param(
    [string]$Ffmpeg = "ffmpeg",
    [string]$Ffprobe = "ffprobe",
    [string]$OutputRoot = (Join-Path $PSScriptRoot "..\build\recipe-smoke")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-MediaTool {
    param(
        [Parameter(Mandatory)]
        [string]$Executable,
        [Parameter(Mandatory)]
        [string[]]$ToolArguments
    )

    & $Executable @ToolArguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE"
    }
}

function Assert-NonEmptyFile {
    param([Parameter(Mandatory)][string]$LiteralPath)

    $item = Get-Item -LiteralPath $LiteralPath -ErrorAction Stop
    if ($item.Length -le 0) {
        throw "Expected a non-empty output: $LiteralPath"
    }
}

Get-Command $Ffmpeg -ErrorAction Stop | Out-Null
Get-Command $Ffprobe -ErrorAction Stop | Out-Null

$runDirectory = Join-Path $OutputRoot ("{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

$sourceA = Join-Path $runDirectory "source-a.mkv"
$sourceB = Join-Path $runDirectory "source-b.mkv"
$captions = Join-Path $runDirectory "captions.srt"
$remuxed = Join-Path $runDirectory "01-remux.mp4"
$h264 = Join-Path $runDirectory "02-h264.mp4"
$hevc = Join-Path $runDirectory "03-hevc.mp4"
$audio = Join-Path $runDirectory "04-audio.flac"
$thumbnail = Join-Path $runDirectory "05-thumbnail.png"
$waveform = Join-Path $runDirectory "06-waveform.png"
$subtitled = Join-Path $runDirectory "07-subtitles.mp4"
$trimmed = Join-Path $runDirectory "08-trim.mp4"
$concatenated = Join-Path $runDirectory "09-concat.mp4"

Invoke-MediaTool $Ffmpeg @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=2",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-shortest", $sourceA
)
Invoke-MediaTool $Ffmpeg @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=1.5",
    "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=1.5",
    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-shortest", $sourceB
)

@"
1
00:00:00,200 --> 00:00:01,500
FFmpeg Android typed subtitle recipe
"@ | Set-Content -LiteralPath $captions -Encoding utf8NoBOM

# Probe and remux.
Invoke-MediaTool $Ffprobe @("-v", "error", "-show_format", "-show_streams", "-of", "json", $sourceA)
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-map", "0:v:0?", "-c:v", "copy", "-map", "0:a:0?", "-c:a", "copy", "-f", "mp4", $remuxed)

# H.264 and HEVC export.
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "1000000", "-c:a", "aac", "-b:a", "128000", "-f", "mp4", $h264)
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "pools=1:frame-threads=1", "-b:v", "1000000", "-c:a", "aac", "-b:a", "128000", "-f", "mp4", $hevc)

# Audio extraction, thumbnail, and waveform.
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-vn", "-map", "0:a:0?", "-c:a", "flac", "-f", "flac", $audio)
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-ss", "0.500", "-i", $sourceA, "-map", "0:v:0", "-an", "-frames:v", "1", "-c:v", "png", "-f", "image2", "-update", "1", $thumbnail)
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-filter_complex", "[0:a:0]showwavespic=s=640x160:split_channels=0:colors=#33B5E5:scale=lin[wave]", "-map", "[wave]", "-frames:v", "1", "-c:v", "png", "-f", "image2", "-update", "1", $waveform)

# Subtitle burn. This applies only FFmpeg filter escaping; no shell command string is built.
$subtitleFilterPath = $captions.Replace("\", "/").Replace(":", "\:")
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-map", "0:v:0", "-c:v", "libx264", "-preset", "ultrafast", "-vf", "subtitles=filename='$subtitleFilterPath':charenc=UTF-8", "-map", "0:a:0?", "-c:a", "copy", "-f", "mp4", $subtitled)

# Accurate trim and filter-based concat.
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-ss", "0.500", "-t", "0.750", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-f", "mp4", $trimmed)
$concatGraph = "[0:v:0]setpts=PTS-STARTPTS[v0];[0:a:0]asetpts=PTS-STARTPTS[a0];[1:v:0]setpts=PTS-STARTPTS[v1];[1:a:0]asetpts=PTS-STARTPTS[a1];[v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]"
Invoke-MediaTool $Ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-i", $sourceA, "-i", $sourceB, "-filter_complex", $concatGraph, "-map", "[outv]", "-c:v", "libx264", "-preset", "ultrafast", "-map", "[outa]", "-c:a", "aac", "-f", "mp4", $concatenated)

$outputs = @($remuxed, $h264, $hevc, $audio, $thumbnail, $waveform, $subtitled, $trimmed, $concatenated)
foreach ($output in $outputs) {
    Assert-NonEmptyFile $output
    Invoke-MediaTool $Ffprobe @("-v", "error", "-show_entries", "format=format_name,duration", "-of", "json", $output)
}
foreach ($media in @($remuxed, $h264, $hevc, $audio, $subtitled, $trimmed, $concatenated)) {
    Invoke-MediaTool $Ffmpeg @("-v", "error", "-i", $media, "-f", "null", "NUL")
}

$summary = $outputs | ForEach-Object {
    $item = Get-Item -LiteralPath $_
    [pscustomobject]@{
        File = $item.Name
        Bytes = $item.Length
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash
    }
}
$summary | Format-Table -AutoSize
Write-Host "Recipe smoke verification passed. Artifacts: $runDirectory"
