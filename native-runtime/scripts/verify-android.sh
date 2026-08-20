#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

runtime=""
ndk="${ANDROID_NDK_ROOT:-}"
abis=""

while (($#)); do
    case "$1" in
        --runtime) runtime="${2:?missing --runtime value}"; shift 2 ;;
        --ndk) ndk="${2:?missing --ndk value}"; shift 2 ;;
        --abis) abis="${2:?missing --abis value}"; shift 2 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done

[[ -n "$runtime" && -f "$runtime/manifest.json" ]] || {
    printf 'Runtime manifest not found under %s\n' "$runtime" >&2
    exit 2
}
[[ -n "$ndk" && -f "$ndk/source.properties" ]] || {
    printf 'Android NDK directory is required\n' >&2
    exit 2
}

readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$readelf" ]] || {
    printf 'llvm-readelf not found: %s\n' "$readelf" >&2
    exit 1
}

if [[ -z "$abis" ]]; then
    abis="$(python3 - "$runtime/manifest.json" <<'PY'
import json, sys
print(",".join(json.load(open(sys.argv[1], encoding="utf-8"))["abis"]))
PY
)"
fi
IFS=',' read -r -a selected_abis <<<"$abis"

python3 - "$runtime/manifest.json" "$abis" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
runtime = manifest_path.parent
selected_abis = sys.argv[2].split(",")
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest["abis"] != selected_abis:
    raise SystemExit(
        f"manifest ABI order {manifest['abis']} does not match verification request {selected_abis}"
    )

libraries = manifest["libraries"]
expected_paths = {
    f"jniLibs/{abi}/{library}"
    for abi in selected_abis
    for library in libraries
}
records = manifest["artifacts"]
record_paths = [record["path"] for record in records]
if len(record_paths) != len(set(record_paths)):
    raise SystemExit("manifest contains duplicate artifact paths")
if set(record_paths) != expected_paths:
    missing = sorted(expected_paths - set(record_paths))
    extra = sorted(set(record_paths) - expected_paths)
    raise SystemExit(f"manifest artifact closure mismatch; missing={missing}, extra={extra}")

for abi in selected_abis:
    directory = runtime / "jniLibs" / abi
    actual = {path.name for path in directory.glob("*.so")}
    expected = set(libraries)
    if actual != expected:
        raise SystemExit(
            f"{abi} library set mismatch; missing={sorted(expected - actual)}, "
            f"extra={sorted(actual - expected)}"
        )

for record in records:
    path = runtime / record["path"]
    size = path.stat().st_size
    if size != record["sizeBytes"]:
        raise SystemExit(f"size mismatch for {record['path']}")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != record["sha256"]:
        raise SystemExit(f"SHA-256 mismatch for {record['path']}")
PY

mapfile -t packaged_libraries < <(python3 - "$runtime/manifest.json" <<'PY'
import json, sys
for value in json.load(open(sys.argv[1], encoding="utf-8"))["libraries"]:
    print(value)
PY
)
mapfile -t system_libraries < <(python3 - "$runtime/manifest.json" <<'PY'
import json, sys
for value in json.load(open(sys.argv[1], encoding="utf-8"))["systemLibraries"]:
    print(value)
PY
)

known_dependency() {
    local dependency="$1"
    local candidate
    for candidate in "${packaged_libraries[@]}" "${system_libraries[@]}"; do
        [[ "$dependency" == "$candidate" ]] && return 0
    done
    return 1
}

failures=0
for abi in "${selected_abis[@]}"; do
    while IFS= read -r -d '' library; do
        headers="$($readelf -lW "$library")"
        while IFS= read -r alignment; do
            value=$((alignment))
            if ((value < 0x4000)); then
                printf 'FAIL %s: LOAD alignment %s is below 0x4000\n' "$library" "$alignment" >&2
                failures=$((failures + 1))
            fi
        done < <(awk '$1 == "LOAD" { print $NF }' <<<"$headers")
        if ! grep -q 'GNU_RELRO' <<<"$headers"; then
            printf 'FAIL %s: GNU_RELRO is missing\n' "$library" >&2
            failures=$((failures + 1))
        fi
        if grep -q 'GNU_STACK.*RWE' <<<"$headers"; then
            printf 'FAIL %s: executable GNU stack is present\n' "$library" >&2
            failures=$((failures + 1))
        fi
        dynamic="$($readelf -dW "$library")"
        if grep -q 'TEXTREL' <<<"$dynamic"; then
            printf 'FAIL %s: TEXTREL is present\n' "$library" >&2
            failures=$((failures + 1))
        fi
        if grep -Eq '\((RPATH|RUNPATH)\)' <<<"$dynamic"; then
            printf 'FAIL %s: RPATH/RUNPATH must not be embedded\n' "$library" >&2
            failures=$((failures + 1))
        fi
        if ! grep -Eq '\((FLAGS|FLAGS_1)\).*(BIND_NOW|NOW)' <<<"$dynamic"; then
            printf 'FAIL %s: immediate binding is missing\n' "$library" >&2
            failures=$((failures + 1))
        fi
        soname="$(sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p' <<<"$dynamic")"
        if [[ "$soname" != "$(basename "$library")" ]]; then
            printf 'FAIL %s: SONAME is %s\n' "$library" "$soname" >&2
            failures=$((failures + 1))
        fi
        while IFS= read -r dependency; do
            if ! known_dependency "$dependency"; then
                printf 'FAIL %s: undeclared dependency %s\n' "$library" "$dependency" >&2
                failures=$((failures + 1))
            fi
        done < <(sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' <<<"$dynamic")
        printf 'PASS %s\n' "${library#"$runtime/"}"
    done < <(find "$runtime/jniLibs/$abi" -maxdepth 1 -type f -name '*.so' -print0 | sort -z)

    cli_symbols="$($readelf -Ws "$runtime/jniLibs/$abi/libffmpeg_sdk_cli.so")"
    for symbol in ffmpeg_sdk_execute ffmpeg_sdk_cancel; do
        if ! grep -q "$symbol" <<<"$cli_symbols"; then
            printf 'FAIL %s: exported runner symbol %s is missing\n' "$abi" "$symbol" >&2
            failures=$((failures + 1))
        fi
    done
    if grep -Eq '[[:space:]]main(@|$)' <<<"$cli_symbols"; then
        printf 'FAIL %s: runner unexpectedly exports main\n' "$abi" >&2
        failures=$((failures + 1))
    fi

    bridge_symbols="$($readelf -Ws "$runtime/jniLibs/$abi/libffmpeg_sdk_bridge.so")"
    for symbol in JNI_OnLoad nativeInitialize nativeVersion nativeConfiguration nativeLicense \
        nativeComponents nativeProbe nativeCancelProbe nativeExecute nativeCancel; do
        if ! grep -q "$symbol" <<<"$bridge_symbols"; then
            printf 'FAIL %s: JNI bridge symbol %s is missing\n' "$abi" "$symbol" >&2
            failures=$((failures + 1))
        fi
    done
done

((failures == 0)) || {
    printf '%s native verification failure(s)\n' "$failures" >&2
    exit 1
}
