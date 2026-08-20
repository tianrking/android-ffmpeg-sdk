#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
export LANG=C
export LC_ALL=C
export TZ=UTC

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
lock_file="$repo_root/native-runtime/ffmpeg.lock.json"
output_dir="$repo_root/native-runtime/prebuilt"
cache_dir="${FFMPEG_ANDROID_CACHE_DIR:-$repo_root/.ffmpeg-cache}"
work_parent="${FFMPEG_ANDROID_WORK_DIR:-$repo_root/.ffmpeg-build}"
ndk_root="${ANDROID_NDK_ROOT:-}"
requested_abis=""
jobs=""
keep_work=0

usage() {
    cat <<'EOF'
Build signed, official FFmpeg sources for Android.

Usage: native-runtime/scripts/build-android.sh [options]
  --output PATH       Generated include/, jniLibs/, pkgconfig/, and manifest.json
  --cache PATH        Download cache (default: .ffmpeg-cache)
  --work PATH         Parent directory for a unique temporary build
  --ndk PATH          Existing Android NDK r29 directory
  --abis CSV          ABI subset; default is all ABIs from ffmpeg.lock.json
  --jobs N            Parallel make jobs
  --keep-work         Preserve the unique build directory for diagnosis
  -h, --help          Show this help
EOF
}

while (($#)); do
    case "$1" in
        --output) output_dir="${2:?missing --output value}"; shift 2 ;;
        --cache) cache_dir="${2:?missing --cache value}"; shift 2 ;;
        --work) work_parent="${2:?missing --work value}"; shift 2 ;;
        --ndk) ndk_root="${2:?missing --ndk value}"; shift 2 ;;
        --abis) requested_abis="${2:?missing --abis value}"; shift 2 ;;
        --jobs) jobs="${2:?missing --jobs value}"; shift 2 ;;
        --keep-work) keep_work=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

for command in python3 curl gpg tar make patch sha256sum; do
    command -v "$command" >/dev/null || {
        printf 'Required command not found: %s\n' "$command" >&2
        exit 1
    }
done

lock_values() {
    python3 - "$lock_file" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
for component in sys.argv[2].split("."):
    value = value[component]
if isinstance(value, list):
    for item in value:
        print(item)
else:
    print(value)
PY
}

require_positive_integer() {
    [[ "$1" =~ ^[1-9][0-9]*$ ]] || {
        printf '%s must be a positive integer; got %s\n' "$2" "$1" >&2
        exit 2
    }
}

verify_sha256() {
    local file="$1"
    local expected="${2,,}"
    local actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || {
        printf 'SHA-256 mismatch for %s\nexpected: %s\nactual:   %s\n' \
            "$file" "$expected" "$actual" >&2
        exit 1
    }
}

download_locked() {
    local url="$1"
    local destination="$2"
    local expected="$3"
    if [[ -f "$destination" ]]; then
        verify_sha256 "$destination" "$expected"
        return
    fi
    local partial="${destination}.partial.$$"
    curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
        --output "$partial" "$url"
    verify_sha256 "$partial" "$expected"
    mv -- "$partial" "$destination"
}

source_url="$(lock_values upstream.source.url)"
source_sha="$(lock_values upstream.source.sha256)"
source_date_epoch="$(lock_values upstream.source.sourceDateEpoch)"
signature_url="$(lock_values upstream.signature.url)"
signature_sha="$(lock_values upstream.signature.sha256)"
key_url="$(lock_values upstream.releaseKey.url)"
key_sha="$(lock_values upstream.releaseKey.sha256)"
key_fingerprint="$(lock_values upstream.releaseKey.fingerprint)"
ndk_url="$(lock_values toolchain.androidNdk.url)"
ndk_sha="$(lock_values toolchain.androidNdk.sha256)"
ndk_revision="$(lock_values toolchain.androidNdk.revision)"
ndk_release="$(lock_values toolchain.androidNdk.release)"
ndk_host="$(lock_values toolchain.androidNdk.host)"
android_api="$(lock_values toolchain.minSdk)"
profile_name="$(lock_values profile.name)"
mapfile -t locked_abis < <(lock_values profile.abis)
mapfile -t locked_libraries < <(lock_values profile.libraries)
mapfile -t sdk_libraries < <(lock_values profile.sdkLibraries)
mapfile -t common_configure < <(lock_values profile.configureArguments)
mapfile -t forbidden_configure < <(lock_values profile.forbiddenConfigureArguments)

if [[ -n "$requested_abis" ]]; then
    IFS=',' read -r -a selected_abis <<<"$requested_abis"
else
    selected_abis=("${locked_abis[@]}")
fi

for abi in "${selected_abis[@]}"; do
    supported=0
    for locked in "${locked_abis[@]}"; do
        [[ "$abi" == "$locked" ]] && supported=1
    done
    ((supported)) || {
        printf 'Unsupported ABI %s; locked ABIs: %s\n' "$abi" "${locked_abis[*]}" >&2
        exit 2
    }
done

if [[ -z "$jobs" ]]; then
    jobs="$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '4')"
fi
require_positive_integer "$jobs" "--jobs"

require_positive_integer "$source_date_epoch" "upstream.source.sourceDateEpoch"
export SOURCE_DATE_EPOCH="$source_date_epoch"

mkdir -p -- "$cache_dir" "$work_parent"
cache_dir="$(cd -- "$cache_dir" && pwd -P)"
work_parent="$(cd -- "$work_parent" && pwd -P)"
output_name="$(basename -- "$output_dir")"
output_parent="$(dirname -- "$output_dir")"
mkdir -p -- "$output_parent"
output_parent="$(cd -- "$output_parent" && pwd -P)"
output_dir="$output_parent/$output_name"
[[ "$output_name" != "." && "$output_name" != ".." && "$output_dir" != "/" && \
   "$output_dir" != "$repo_root" ]] || {
    printf 'Refusing unsafe output directory: %s\n' "$output_dir" >&2
    exit 2
}

source_archive="$cache_dir/ffmpeg-9.0.1.tar.xz"
source_signature="$source_archive.asc"
release_key="$cache_dir/ffmpeg-devel.asc"
download_locked "$source_url" "$source_archive" "$source_sha"
download_locked "$signature_url" "$source_signature" "$signature_sha"
download_locked "$key_url" "$release_key" "$key_sha"

gpg_home="$(mktemp -d "$work_parent/gpg.XXXXXX")"
chmod 700 "$gpg_home"
cleanup_paths=("$gpg_home")
cleanup() {
    local path
    if ((keep_work)); then
        printf 'Preserved build state under %s\n' "$work_parent"
        return
    fi
    for path in "${cleanup_paths[@]}"; do
        [[ -n "$path" && "$path" == "$work_parent"/* && -d "$path" ]] || continue
        rm -rf -- "$path"
    done
}
trap cleanup EXIT

actual_fingerprint="$(GNUPGHOME="$gpg_home" gpg --batch --with-colons \
    --import-options show-only --import "$release_key" 2>/dev/null | \
    awk -F: '$1 == "fpr" { print tolower($10); exit }')"
[[ "$actual_fingerprint" == "${key_fingerprint,,}" ]] || {
    printf 'Unexpected FFmpeg release-key fingerprint: %s\n' "$actual_fingerprint" >&2
    exit 1
}
GNUPGHOME="$gpg_home" gpg --batch --import "$release_key" >/dev/null 2>&1
GNUPGHOME="$gpg_home" gpg --batch --verify "$source_signature" "$source_archive"

if [[ -z "$ndk_root" ]]; then
    command -v unzip >/dev/null || {
        printf 'Required command not found: unzip\n' >&2
        exit 1
    }
    ndk_archive="$cache_dir/android-ndk-${ndk_release}-linux.zip"
    download_locked "$ndk_url" "$ndk_archive" "$ndk_sha"
    ndk_root="$cache_dir/android-ndk-${ndk_release}"
    if [[ ! -f "$ndk_root/source.properties" ]]; then
        ndk_extract="$(mktemp -d "$work_parent/ndk.XXXXXX")"
        cleanup_paths+=("$ndk_extract")
        unzip -q "$ndk_archive" -d "$ndk_extract"
        extracted="$ndk_extract/android-ndk-${ndk_release}"
        [[ -f "$extracted/source.properties" ]] || {
            printf 'NDK archive did not contain the expected directory\n' >&2
            exit 1
        }
        mv -- "$extracted" "$ndk_root"
    fi
fi

ndk_root="$(cd -- "$ndk_root" && pwd -P)"
actual_ndk_revision="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' \
    "$ndk_root/source.properties" | head -n 1)"
[[ "$actual_ndk_revision" == "$ndk_revision" ]] || {
    printf 'NDK revision mismatch: expected %s, got %s\n' \
        "$ndk_revision" "$actual_ndk_revision" >&2
    exit 1
}

toolchain="$ndk_root/toolchains/llvm/prebuilt/$ndk_host"
[[ -x "$toolchain/bin/clang" ]] || {
    printf 'NDK LLVM toolchain not found: %s\n' "$toolchain" >&2
    exit 1
}

build_root="$(mktemp -d "$work_parent/run.XXXXXX")"
cleanup_paths+=("$build_root")
runtime_output="$build_root/runtime-output"
source_root="$build_root/source"
mkdir -p -- "$source_root" "$runtime_output/include" "$runtime_output/jniLibs" \
    "$runtime_output/pkgconfig"
tar -xJf "$source_archive" --strip-components=1 -C "$source_root"

source_version="$(tr -d '\r\n' < "$source_root/VERSION")"
[[ "$source_version" == "9.0.1" ]] || {
    printf 'Unexpected FFmpeg VERSION in signed source: %s\n' "$source_version" >&2
    exit 1
}

patch_path="$repo_root/native-runtime/patches/0001-expose-ffmpeg-runner.patch"
patch_sha="$(python3 - "$lock_file" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["profile"]["patches"][0]["sha256"])
PY
)"
bridge_source="$repo_root/native-runtime/src/ffmpeg_sdk_bridge.cpp"
bridge_source_sha="$(python3 - "$lock_file" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["profile"]["bridgeSources"][0]["sha256"])
PY
)"
cli_makefile="$repo_root/native-runtime/scripts/ffmpeg-cli.mk"
cli_makefile_sha="$(python3 - "$lock_file" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["profile"]["bridgeSources"][1]["sha256"])
PY
)"
cli_map="$repo_root/native-runtime/scripts/ffmpeg-sdk-cli.map"
cli_map_sha="$(python3 - "$lock_file" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["profile"]["bridgeSources"][2]["sha256"])
PY
)"
bridge_map="$repo_root/native-runtime/scripts/ffmpeg-sdk-bridge.map"
bridge_map_sha="$(python3 - "$lock_file" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["profile"]["bridgeSources"][3]["sha256"])
PY
)"
verify_sha256 "$patch_path" "$patch_sha"
verify_sha256 "$bridge_source" "$bridge_source_sha"
verify_sha256 "$cli_makefile" "$cli_makefile_sha"
verify_sha256 "$cli_map" "$cli_map_sha"
verify_sha256 "$bridge_map" "$bridge_map_sha"
patch --batch --forward --fuzz=0 -d "$source_root" -p1 < "$patch_path"

configure_log="$build_root/configure-arguments.tsv"
: > "$configure_log"

abi_settings() {
    case "$1" in
        arm64-v8a)
            abi_arch="aarch64"
            abi_triple="aarch64-linux-android"
            abi_cpu="armv8-a"
            abi_cflags="-march=armv8-a"
            abi_extra_configure=()
            ;;
        armeabi-v7a)
            abi_arch="arm"
            abi_triple="armv7a-linux-androideabi"
            abi_cpu="armv7-a"
            abi_cflags="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"
            abi_extra_configure=("--enable-neon")
            ;;
        x86_64)
            abi_arch="x86_64"
            abi_triple="x86_64-linux-android"
            abi_cpu="x86-64"
            abi_cflags=""
            abi_extra_configure=("--disable-x86asm")
            ;;
        x86)
            abi_arch="x86"
            abi_triple="i686-linux-android"
            abi_cpu="i686"
            abi_cflags="-mstackrealign"
            abi_extra_configure=("--disable-x86asm" "--disable-inline-asm")
            ;;
        *) printf 'Internal error: no settings for ABI %s\n' "$1" >&2; exit 2 ;;
    esac
}

for abi in "${selected_abis[@]}"; do
    abi_settings "$abi"
    build_dir="$build_root/build-$abi"
    install_root="$build_root/install-root-$abi"
    install_prefix="/ffmpeg-sdk/android/$abi"
    prefix_dir="$install_root$install_prefix"
    mkdir -p -- "$build_dir" "$install_root" "$runtime_output/jniLibs/$abi" \
        "$runtime_output/pkgconfig/$abi"

    cc_name="${abi_triple}${android_api}-clang"
    cxx_name="${abi_triple}${android_api}-clang++"
    cc="$toolchain/bin/$cc_name"
    cxx="$toolchain/bin/$cxx_name"
    [[ -x "$cc" && -x "$cxx" ]] || {
        printf 'NDK compiler not found for %s\n' "$abi" >&2
        exit 1
    }

    extra_cflags="-fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2 -DFFMPEG_SDK_EMBEDDED=1"
    [[ -n "$abi_cflags" ]] && extra_cflags+=" $abi_cflags"
    extra_ldflags="-Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now"
    configure_args=(
        "${common_configure[@]}"
        "--prefix=$install_prefix"
        "--arch=$abi_arch"
        "--cpu=$abi_cpu"
        "--cc=$cc_name"
        "--cxx=$cxx_name"
        "--ar=llvm-ar"
        "--nm=llvm-nm"
        "--ranlib=llvm-ranlib"
        "--strip=llvm-strip"
        "--extra-cflags=$extra_cflags"
        "--extra-ldflags=$extra_ldflags"
        "${abi_extra_configure[@]}"
    )

    for forbidden in "${forbidden_configure[@]}"; do
        for argument in "${configure_args[@]}"; do
            [[ "$argument" != "$forbidden" ]] || {
                printf 'Forbidden configure argument selected: %s\n' "$forbidden" >&2
                exit 1
            }
        done
    done

    printf 'Configuring FFmpeg %s for %s (API %s)\n' "$source_version" "$abi" "$android_api"
    (
        cd -- "$build_dir"
        export PATH="$toolchain/bin:$PATH"
        if ! "$source_root/configure" "${configure_args[@]}" > configure.log 2>&1; then
            printf 'FFmpeg configure failed for %s; log tail follows\n' "$abi" >&2
            tail -n 200 configure.log >&2
            exit 1
        fi
        if ! make -s -j"$jobs" -f Makefile -f "$cli_makefile" \
            DESTDIR="$install_root" \
            SDK_CLI_OUT="$build_dir/libffmpeg_sdk_cli.so" \
            SDK_CLI_MAP="$cli_map" \
            install-libs install-headers ffmpeg-sdk-cli > build.log 2>&1; then
            printf 'FFmpeg build failed for %s; log tail follows\n' "$abi" >&2
            tail -n 200 build.log >&2
            exit 1
        fi
    )

    printf '%s' "$abi" >> "$configure_log"
    printf '\t%s' "${configure_args[@]}" >> "$configure_log"
    printf '\n' >> "$configure_log"

    for library in "${locked_libraries[@]}"; do
        installed="$prefix_dir/lib/$library"
        [[ -f "$installed" ]] || {
            printf 'Expected shared library was not installed: %s\n' "$installed" >&2
            exit 1
        }
        install -m 0644 "$installed" "$runtime_output/jniLibs/$abi/$library"
    done

    "$cxx" -std=c++17 -shared -static-libstdc++ -fPIC \
        -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
        -fvisibility=hidden -I"$prefix_dir/include" "$bridge_source" \
        -L"$prefix_dir/lib" -Wl,-rpath-link,"$prefix_dir/lib" -Wl,--no-undefined \
        -Wl,--exclude-libs,ALL -Wl,--version-script="$bridge_map" \
        -Wl,-soname,libffmpeg_sdk_bridge.so \
        -Wl,--build-id=sha1 \
        -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
        -Wl,-z,relro -Wl,-z,now \
        -lavformat -lavcodec -lavfilter -lavdevice -lswresample -lswscale -lavutil \
        -pthread -landroid -llog -ldl -lz -lm -latomic \
        -o "$build_dir/libffmpeg_sdk_bridge.so"

    "$toolchain/bin/llvm-strip" --strip-unneeded "$build_dir/libffmpeg_sdk_cli.so"
    "$toolchain/bin/llvm-strip" --strip-unneeded "$build_dir/libffmpeg_sdk_bridge.so"
    install -m 0644 "$build_dir/libffmpeg_sdk_cli.so" \
        "$runtime_output/jniLibs/$abi/${sdk_libraries[0]}"
    install -m 0644 "$build_dir/libffmpeg_sdk_bridge.so" \
        "$runtime_output/jniLibs/$abi/${sdk_libraries[1]}"
    while IFS= read -r -d '' pkgconfig; do
        destination="$runtime_output/pkgconfig/$abi/$(basename "$pkgconfig")"
        sed -e 's|^prefix=.*|prefix=${pcfiledir}/../..|' \
            -e 's|^exec_prefix=.*|exec_prefix=${prefix}|' \
            -e "s|^libdir=.*|libdir=\${prefix}/jniLibs/$abi|" \
            -e 's|^includedir=.*|includedir=${prefix}/include|' \
            "$pkgconfig" > "$destination"
        chmod 0644 "$destination"
    done < <(find "$prefix_dir/lib/pkgconfig" -maxdepth 1 -type f -name '*.pc' -print0)

    if [[ ! -f "$runtime_output/include/libavcodec/avcodec.h" ]]; then
        cp -R -- "$prefix_dir/include/." "$runtime_output/include/"
    fi
done

python3 "$script_dir/generate-manifest.py" \
    --lock "$lock_file" \
    --output "$runtime_output" \
    --configure-log "$configure_log" \
    --ndk-revision "$actual_ndk_revision"

bash "$script_dir/verify-android.sh" \
    --runtime "$runtime_output" \
    --ndk "$ndk_root" \
    --abis "$(IFS=,; printf '%s' "${selected_abis[*]}")"

publish_dir="$(mktemp -d "$output_parent/.${output_name}.publish.XXXXXX")"
cp -R -- "$runtime_output/." "$publish_dir/"
backup_dir=""
if [[ -e "$output_dir" ]]; then
    backup_dir="$(mktemp -d "$output_parent/.${output_name}.previous.XXXXXX")"
    rmdir -- "$backup_dir"
    mv -- "$output_dir" "$backup_dir"
fi
if mv -- "$publish_dir" "$output_dir"; then
    [[ -z "$backup_dir" ]] || rm -rf -- "$backup_dir"
else
    [[ -z "$backup_dir" || -e "$output_dir" ]] || mv -- "$backup_dir" "$output_dir"
    printf 'Unable to publish verified runtime to %s\n' "$output_dir" >&2
    exit 1
fi

printf 'Official FFmpeg Android runtime is ready: %s\n' "$output_dir"
