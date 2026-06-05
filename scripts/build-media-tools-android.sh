#!/usr/bin/env bash
# Bundle Android arm64 ffmpeg into jniLibs (Gradle は libfoo.so.62 を APK に入れないため patchelf で改名).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ABI="arm64-v8a"
PLUGIN_JNI="$ROOT_DIR/mobile/plugins/media-audio-finder/android/src/main/jniLibs/$ABI"
APP_JNI="$ROOT_DIR/mobile/android/app/src/main/jniLibs/$ABI"
TERMUX_REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
FFMPEG_DEB_URL="${FFMPEG_DEB_URL:-$TERMUX_REPO/pool/main/f/ffmpeg/ffmpeg_8.1.1_aarch64.deb}"
DEB_CACHE="${DEB_CACHE:-$ROOT_DIR/.cache/termux-debs}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/media-tools-android-build.XXXXXX")"
TERMUX_LIB_DIRS=()
mkdir -p "$DEB_CACHE"

SONAMES=(
  libavdevice.so.62
  libavfilter.so.11
  libavformat.so.62
  libavcodec.so.62
  libswresample.so.6
  libswscale.so.9
  libavutil.so.60
)
JNI_NAMES=(
  libytdavdevice62.so
  libytdavfilter11.so
  libytdavformat62.so
  libytdavcodec62.so
  libytdswresample6.so
  libytdswscale9.so
  libytdavutil60.so
)

# 試聴パイプでは不要。resolve でもコピーしない（rubberband → fftw3 等の連鎖を断つ）
SKIP_LIBS=(
  libOpenCL.so
)

require_patchelf() {
  if command -v patchelf >/dev/null 2>&1; then
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    echo "Installing patchelf (brew)..."
    brew install patchelf
  fi
  command -v patchelf >/dev/null 2>&1 || {
    echo "patchelf is required. Install: brew install patchelf"
    exit 1
  }
}

build_opencl_stub() {
  local out="$1"
  local src
  download_termux_package "ocl-icd" || return 1
  src="$(find_termux_lib libOpenCL.so)" || return 1
  cp -Lf "$src" "$out"
  echo "Bundled Termux libOpenCL.so (ICD stub) for libavfilter OpenCL symbols"
}

add_opencl_stub_to_bundle() {
  local patch_dir="$1"
  local stub="$patch_dir/libOpenCL.so"
  local nm_bin
  build_opencl_stub "$stub" || return 1
  nm_bin="$(command -v llvm-nm 2>/dev/null || true)"
  [[ -z "$nm_bin" && -x "$(brew --prefix llvm 2>/dev/null)/bin/llvm-nm" ]] && \
    nm_bin="$(brew --prefix llvm)/bin/llvm-nm"
  local bin
  for bin in "$patch_dir"/*; do
    [[ -f "$bin" ]] || continue
    [[ "$(basename "$bin")" == "libOpenCL.so" ]] && continue
    [[ -z "$nm_bin" ]] && continue
    if "$nm_bin" -D "$bin" 2>/dev/null | grep -q ' U cl'; then
      if ! patchelf --print-needed "$bin" 2>/dev/null | grep -qxF libOpenCL.so; then
        patchelf --add-needed libOpenCL.so "$bin"
        echo "  + libOpenCL.so (stub) for $(basename "$bin")"
      fi
    fi
  done
}

resolve_objcopy() {
  if command -v llvm-objcopy >/dev/null 2>&1; then
    command -v llvm-objcopy
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    local brew_llvm
    brew_llvm="$(brew --prefix llvm 2>/dev/null)/bin/llvm-objcopy"
    if [[ -x "$brew_llvm" ]]; then
      echo "$brew_llvm"
      return 0
    fi
  fi
  return 1
}

# Android リンカーは patchelf 改名後の VERNEED/SONAME 不一致で失敗する
finalize_android_bundle() {
  local patch_dir="$1"
  local f base soname
  echo "Finalizing for Android linker (SONAME + strip .gnu.version)..."
  for f in "$patch_dir"/*.so; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    soname="$(patchelf --print-soname "$f" 2>/dev/null || true)"
    if [[ "$soname" != "$base" ]]; then
      patchelf --set-soname "$base" "$f"
      echo "  soname $soname -> $base"
    fi
  done
  local objcopy
  objcopy="$(resolve_objcopy || true)"
  if [[ -z "$objcopy" ]]; then
    echo "Warning: llvm-objcopy not found; Android may fail VERNEED check. Install: brew install llvm"
    return 0
  fi
  for f in "$patch_dir"/*.so; do
    [[ -f "$f" ]] || continue
    # VERNEED (.gnu.version_r) は patchelf 改名と不整合になるため除去。
    # .gnu.version_d (VERDEF) は除去すると vd_version:0 エラーになるため残す。
    "$objcopy" \
      --remove-section .gnu.version \
      --remove-section .gnu.version_r \
      "$f" 2>/dev/null || true
  done
  local strip_tags="$SCRIPT_DIR/strip-elf-version-tags.py"
  if [[ ! -x "$strip_tags" ]]; then
    chmod +x "$strip_tags"
  fi
  for f in "$patch_dir"/*.so; do
    [[ -f "$f" ]] || continue
    python3 "$strip_tags" "$f" || {
      echo "Failed to strip ELF version dynamic tags: $f"
      exit 1
    }
  done
}

verify_android_bundle() {
  local patch_dir="$1"
  local ff="$patch_dir/libffmpeg.so"
  local af="$patch_dir/libytdavfilter11.so"
  local dep optional
  if [[ ! -f "$ff" ]]; then
    echo "verify failed: missing libffmpeg.so"
    return 1
  fi
  for dep in libytdavdevice62.so libytdavfilter11.so libytdavcodec62.so; do
    if ! patchelf --print-needed "$ff" 2>/dev/null | grep -qxF "$dep"; then
      echo "verify failed: libffmpeg.so missing NEEDED $dep"
      return 1
    fi
  done
  if [[ -f "$patch_dir/libOpenCL.so" && -f "$af" ]]; then
    if ! patchelf --print-needed "$af" 2>/dev/null | grep -qxF libOpenCL.so; then
      echo "verify failed: libytdavfilter11.so missing libOpenCL.so (ICD stub)"
      return 1
    fi
  fi
  local readelf
  readelf="$(command -v llvm-readelf 2>/dev/null || true)"
  if [[ -z "$readelf" && -x "$(brew --prefix llvm 2>/dev/null)/bin/llvm-readelf" ]]; then
    readelf="$(brew --prefix llvm)/bin/llvm-readelf"
  fi
  if [[ -n "$readelf" ]]; then
    # pipefail + grep -q は SIGPIPE で偽陰性になるため、出力を変数に取る
    local ff_sections dev_sections
    ff_sections="$("$readelf" --sections "$ff" 2>/dev/null || true)"
    dev_sections="$("$readelf" --sections "$patch_dir/libytdavdevice62.so" 2>/dev/null || true)"
    local ff_dyn
    ff_dyn="$("$readelf" -d "$ff" 2>/dev/null || true)"
    if [[ "$ff_sections" == *".gnu.version_r"* ]] || [[ "$ff_dyn" == *"(VERNEED)"* ]] || [[ "$ff_dyn" == *"(VERSYM)"* ]]; then
      echo "verify failed: libffmpeg.so still has version need/sym (stale dynamic tags or .gnu.version_r)"
      return 1
    fi
    if [[ "$dev_sections" != *".gnu.version_d"* ]]; then
      echo "verify failed: libytdavdevice62.so missing .gnu.version_d (VERDEF stripped — breaks Android)"
      return 1
    fi
  fi
  echo "Android bundle verification passed"
  return 0
}

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

extract_deb() {
  local deb="$1"
  local out="$2"
  mkdir -p "$out"
  (
    cd "$out"
    if command -v bsdtar >/dev/null 2>&1; then
      bsdtar -xf "$deb"
      bsdtar -xf data.tar.xz
    else
      ar x "$(basename "$deb")"
      tar -xf data.tar.xz
    fi
  )
}

register_lib_dir() {
  local dir="$1/data/data/com.termux/files/usr/lib"
  if [[ -d "$dir" ]]; then
    TERMUX_LIB_DIRS+=("$dir")
  fi
}

ensure_packages_index() {
  if [[ -f "$WORK_DIR/Packages" ]]; then
    return 0
  fi
  echo "Downloading Termux package index..." >&2
  curl -fsSL -o "$WORK_DIR/Packages.gz" "$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages.gz"
  gunzip -c "$WORK_DIR/Packages.gz" >"$WORK_DIR/Packages"
}

download_termux_package() {
  local pkg="$1"
  local deb_path="$DEB_CACHE/${pkg}.deb"
  local extract_path="$DEB_CACHE/${pkg}"

  if [[ -d "$extract_path/data" ]]; then
    register_lib_dir "$extract_path"
    return 0
  fi

  ensure_packages_index

  local filename
  filename="$(
    awk -v pkg="$pkg" '
      $1 == "Package:" && $2 == pkg { found=1; next }
      found && $1 == "Filename:" { print $2; exit }
      found && $1 == "" { exit }
    ' "$WORK_DIR/Packages"
  )"
  if [[ -z "$filename" ]]; then
    echo "Warning: Termux package not found in index: $pkg" >&2
    return 1
  fi

  mkdir -p "$DEB_CACHE"
  if [[ ! -f "$deb_path" ]]; then
    echo "  fetching $pkg ..." >&2
    curl -fsSL -o "$deb_path" "$TERMUX_REPO/$filename"
  fi
  rm -rf "$extract_path"
  extract_deb "$deb_path" "$extract_path"
  register_lib_dir "$extract_path"
}

is_system_lib() {
  case "$1" in
    libc.so|libm.so|libdl.so|liblog.so|libandroid.so|ld-*|libEGL.so|libGLESv2.so|libOpenSLES.so|libmediandk.so|libvulkan.so)
      return 0
      ;;
  esac
  return 1
}

is_skip_lib() {
  local dep="$1"
  local skip
  for skip in "${SKIP_LIBS[@]}"; do
    [[ "$dep" == "$skip" ]] && return 0
  done
  return 1
}

jni_name_for_dep() {
  local dep="$1"
  if [[ "$dep" == "libz.so.1" ]]; then
    echo "libytdzlib1.so"
    return 0
  fi
  if [[ "$dep" =~ ^lib(.+)\.so\.([0-9].*)$ ]]; then
    local ver="${BASH_REMATCH[2]//./}"
    echo "libytd${BASH_REMATCH[1]}${ver}.so"
  else
    echo "$dep"
  fi
}

find_termux_lib() {
  local dep="$1"
  python3 - "$dep" "${TERMUX_LIB_DIRS[@]}" <<'PY'
import glob
import os
import sys

dep = sys.argv[1]
dirs = sys.argv[2:]
base = dep.split(".so", 1)[0]

def check(libdir: str) -> str | None:
    if not os.path.isdir(libdir):
        return None
    exact = os.path.join(libdir, dep)
    if os.path.isfile(exact):
        return exact
    for path in sorted(glob.glob(os.path.join(libdir, base + "*.so*"))):
        if os.path.isfile(path):
            return path
    for root, _, files in os.walk(libdir):
        if dep in files:
            hit = os.path.join(root, dep)
            if os.path.isfile(hit):
                return hit
    return None

for libdir in dirs:
    hit = check(libdir)
    if hit:
        print(hit)
        raise SystemExit(0)
    parent = os.path.dirname(libdir)
    if os.path.isdir(parent):
        for root, _, files in os.walk(parent):
            if dep in files:
                hit = os.path.join(root, dep)
                if os.path.isfile(hit):
                    print(hit)
                    raise SystemExit(0)
raise SystemExit(1)
PY
}

guess_package_for_lib() {
  local dep="$1"
  case "$dep" in
    libfftw3.so*) echo fftw ;;
    libsamplerate.so*) echo libsamplerate ;;
    libsndfile.so*) echo libsndfile ;;
    libc++_shared.so*) echo libc++ ;;
    libz.so.1|libz.so*) echo zlib ;;
    libbs2b.so*) echo libbs2b ;;
    libssl.so*|libcrypto.so*) echo openssl ;;
    libharfbuzz.so*) echo harfbuzz ;;
    libfribidi.so*) echo fribidi ;;
    libass.so*) echo libass ;;
    libopus.so*) echo libopus ;;
    libmp3lame.so*) echo libmp3lame ;;
    libx264.so*) echo libx264 ;;
    libx265.so*) echo libx265 ;;
    libvpx.so*) echo libvpx ;;
    libdav1d.so*) echo libdav1d ;;
    libaom.so*) echo libaom ;;
    libxml2.so*) echo libxml2 ;;
    libbluray.so*) echo libbluray ;;
    libfreetype.so*) echo freetype ;;
    libfontconfig.so*) echo fontconfig ;;
    libbz2.so*) echo libbz2 ;;
    liblzma.so*) echo liblzma ;;
    libiconv.so*) echo libiconv ;;
    libzmq.so*) echo libzmq ;;
    libssh.so*) echo libssh ;;
    libsrt.so*) echo libsrt ;;
    libsoxr.so*) echo libsoxr ;;
    libvidstab.so*) echo libvidstab ;;
    libvmaf.so*) echo libvmaf ;;
    libwebp.so*) echo libwebp ;;
    libtheora*.so*) echo libtheora ;;
    libvorbis*.so*) echo libvorbis ;;
    libopencore-amr*.so*) echo libopencore-amr ;;
    libvo-amrwbenc.so*) echo libvo-amrwbenc ;;
    libxvidcore.so*) echo xvidcore ;;
    libzimg.so*) echo libzimg ;;
    libgme.so*) echo game-music-emu ;;
    libopenmpt.so*) echo libopenmpt ;;
    liblcms2.so*) echo littlecms ;;
    librav1e.so*) echo librav1e ;;
    libSvtAv1Enc.so*) echo svt-av1 ;;
    libglslang.so*) echo glslang ;;
    libglslang-default-resource-limits.so*) echo glslang ;;
    libplacebo.so*) echo libplacebo ;;
    librubberband.so*) echo rubberband ;;
    libfftw3.so*) echo fftw ;;
    libsamplerate.so*) echo libsamplerate ;;
    libglib-2.0.so*) echo glib ;;
    libgraphite2.so*) echo libgraphite ;;
    libsodium.so*) echo libsodium ;;
    libexpat.so*) echo libexpat ;;
    libpng16.so*) echo libpng ;;
    libbrotlidec.so*) echo brotli ;;
    libicuuc.so*|libicudata.so*) echo libicu ;;
    libpcre2-8.so*) echo pcre2 ;;
    libbrotlicommon.so*) echo brotli ;;
    libandroid-support.so*) echo libandroid-support ;;
    libsharpyuv.so*|libwebpmux.so*) echo libwebp ;;
    libogg.so*) echo libogg ;;
    libandroid-glob.so*) echo libandroid-glob ;;
    libandroid-posix-semaphore.so*) echo libandroid-posix-semaphore ;;
    libmpg123.so*) echo libmpg123 ;;
    libvorbisfile.so*) echo libvorbisfile ;;
    libvorbisenc.so*) echo libvorbis ;;
    libtheoradec.so*) echo libtheora ;;
    libudfread.so*) echo libudfread ;;
    libcrypto.so*) echo openssl ;;
    *)
      local base="${dep%.so*}"
      base="${base#lib}"
      echo "$base"
      ;;
  esac
}

ensure_termux_lib() {
  local dep="$1"
  local src pkg
  src="$(find_termux_lib "$dep")" || true
  if [[ -n "$src" ]]; then
    echo "$src"
    return 0
  fi
  pkg="$(guess_package_for_lib "$dep")"
  [[ -z "$pkg" ]] && return 1
  if ! download_termux_package "$pkg"; then
    echo "Warning: could not download package for $dep (guessed $pkg)" >&2
    return 1
  fi
  src="$(find_termux_lib "$dep")" || {
    echo "Warning: $dep not found after fetching $pkg" >&2
    return 1
  }
  echo "$src"
}

strip_skip_needed_from_dir() {
  local patch_dir="$1"
  local bin dep
  for bin in "$patch_dir"/*; do
    [[ -f "$bin" ]] || continue
    for dep in "${SKIP_LIBS[@]}"; do
      if patchelf --print-needed "$bin" 2>/dev/null | grep -qxF "$dep"; then
        patchelf --remove-needed "$dep" "$bin"
        echo "  - removed $dep from $(basename "$bin")"
      fi
    done
  done
}

# 取得できないコーデックは NEEDED から外す（試聴: mp3/aac/wav 向け）
strip_unresolved_needed() {
  local patch_dir="$1"
  local bin dep jni_name
  local optional=(
    libopencore-amrwb.so
    libopencore-amrnb.so
    libvo-amrwbenc.so.0
    libtheoradec.so
    libopenmpt.so
    libmpg123.so
    libvorbisfile.so
    libvorbisenc.so
    libwebp.so
    libbrotlicommon.so
    libicudata.so.78
    libcrypto.so.3
  )
  for bin in "$patch_dir"/*; do
    [[ -f "$bin" ]] || continue
    for dep in "${optional[@]}"; do
      patchelf --print-needed "$bin" 2>/dev/null | grep -qxF "$dep" || continue
      jni_name="$(jni_name_for_dep "$dep")"
      if [[ ! -f "$patch_dir/$jni_name" ]]; then
        patchelf --remove-needed "$dep" "$bin"
        echo "  - dropped unresolved $dep from $(basename "$bin")"
      fi
    done
  done
}

resolve_closure() {
  local patch_dir="$1"
  local changed=1 pass=0
  while [[ $changed -eq 1 && $pass -lt 32 ]]; do
    changed=0
    pass=$((pass + 1))
    for bin in "$patch_dir"/*; do
      [[ -f "$bin" ]] || continue
      [[ "$(basename "$bin")" == ".reachable" ]] && continue
      while IFS= read -r dep; do
        [[ -z "$dep" ]] && continue
        is_system_lib "$dep" && continue
        is_skip_lib "$dep" && continue
        local jni_name
        jni_name="$(jni_name_for_dep "$dep")"
        if [[ -f "$patch_dir/$jni_name" ]]; then
          continue
        fi
        local src
        src="$(ensure_termux_lib "$dep")" || continue
        cp -Lf "$src" "$patch_dir/$jni_name"
        patchelf --replace-needed "$dep" "$jni_name" "$bin"
        changed=1
        echo "  + $dep -> $jni_name (via $(basename "$bin"))"
      done < <(patchelf --print-needed "$bin" 2>/dev/null || true)
    done
  done
}

fixup_needed_aliases() {
  local patch_dir="$1"
  local changed=1
  while [[ $changed -eq 1 ]]; do
    changed=0
    for bin in "$patch_dir"/*; do
      [[ -f "$bin" ]] || continue
      while IFS= read -r dep; do
        [[ -z "$dep" ]] && continue
        is_system_lib "$dep" && continue
        is_skip_lib "$dep" && continue
        local jni_name
        jni_name="$(jni_name_for_dep "$dep")"
        [[ "$dep" == "$jni_name" ]] && continue
        [[ -f "$patch_dir/$jni_name" ]] || continue
        if patchelf --print-needed "$bin" 2>/dev/null | grep -qxF "$dep"; then
          patchelf --replace-needed "$dep" "$jni_name" "$bin"
          changed=1
        fi
      done < <(patchelf --print-needed "$bin" 2>/dev/null || true)
    done
  done
}

collect_reachable_libs() {
  local patch_dir="$1"
  local reachable_file="$2"
  echo "libffmpeg.so" >"$reachable_file"
  local changed=1 bin dep jni_name
  while [[ $changed -eq 1 ]]; do
    changed=0
    while IFS= read -r bin; do
      [[ -f "$patch_dir/$bin" ]] || continue
      while IFS= read -r dep; do
        [[ -z "$dep" ]] && continue
        is_system_lib "$dep" && continue
        is_skip_lib "$dep" && continue
        jni_name="$(jni_name_for_dep "$dep")"
        [[ -f "$patch_dir/$jni_name" ]] || continue
        if ! grep -qxF "$jni_name" "$reachable_file"; then
          echo "$jni_name" >>"$reachable_file"
          changed=1
        fi
      done < <(patchelf --print-needed "$patch_dir/$bin" 2>/dev/null || true)
    done <"$reachable_file"
  done
}

prune_unreachable_libs() {
  local patch_dir="$1"
  local reachable_file="$patch_dir/.reachable"
  collect_reachable_libs "$patch_dir" "$reachable_file"

  local removed=0 f base
  for f in "$patch_dir"/*; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    [[ "$base" == ".reachable" ]] && continue
    if ! grep -qxF "$base" "$reachable_file"; then
      rm -f "$f"
      removed=$((removed + 1))
    fi
  done
  rm -f "$reachable_file"
  if [[ $removed -gt 0 ]]; then
    echo "  pruned $removed unreachable libraries"
  fi
}

verify_patch_dir() {
  local patch_dir="$1"
  local reachable_file="$patch_dir/.reachable"
  collect_reachable_libs "$patch_dir" "$reachable_file"

  local missing=() bin dep jni_name
  while IFS= read -r bin; do
    [[ -f "$patch_dir/$bin" ]] || continue
    while IFS= read -r dep; do
      [[ -z "$dep" ]] && continue
      is_system_lib "$dep" && continue
      is_skip_lib "$dep" && continue
      jni_name="$(jni_name_for_dep "$dep")"
      if [[ ! -f "$patch_dir/$jni_name" ]]; then
        missing+=("$dep (required by $bin)")
      fi
    done < <(patchelf --print-needed "$patch_dir/$bin" 2>/dev/null || true)
  done <"$reachable_file"
  rm -f "$reachable_file"

  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing libraries (reachable from libffmpeg.so):"
    printf '  %s\n' "${missing[@]}"
    return 1
  fi
  return 0
}

converge_bundle() {
  local patch_dir="$1"
  local attempts=0
  while [[ $attempts -lt 6 ]]; do
    if verify_patch_dir "$patch_dir" 2>/dev/null; then
      return 0
    fi
    attempts=$((attempts + 1))
    echo "  converge pass $attempts ..."
    resolve_closure "$patch_dir"
    strip_skip_needed_from_dir "$patch_dir"
    strip_unresolved_needed "$patch_dir"
    fixup_needed_aliases "$patch_dir"
    prune_unreachable_libs "$patch_dir"
  done
  strip_unresolved_needed "$patch_dir"
  prune_unreachable_libs "$patch_dir"
  verify_patch_dir "$patch_dir"
}

install_ffmpeg_jni() {
  local dest="$1"
  local termux_bin="$2"
  local patch_dir="$WORK_DIR/patched-$(basename "$dest")-$$"

  mkdir -p "$dest"
  rm -rf "$patch_dir"
  mkdir -p "$patch_dir"
  find "$dest" -maxdepth 1 -name '*.so' ! -name 'libsyncthing.so' -delete 2>/dev/null || true

  cp -f "$termux_bin" "$patch_dir/libffmpeg.so"
  chmod +x "$patch_dir/libffmpeg.so"

  local i soname jni_name src
  for i in "${!SONAMES[@]}"; do
    soname="${SONAMES[$i]}"
    jni_name="${JNI_NAMES[$i]}"
    src="$(find_termux_lib "$soname")" || {
      echo "Missing dependency: $soname"
      exit 1
    }
    cp -Lf "$src" "$patch_dir/$jni_name"
    patchelf --replace-needed "$soname" "$jni_name" "$patch_dir/libffmpeg.so"
  done

  local zlib_src
  zlib_src="$(find_termux_lib libz.so.1)" || true
  if [[ -n "$zlib_src" ]]; then
    cp -Lf "$zlib_src" "$patch_dir/libytdzlib1.so"
    patchelf --replace-needed libz.so.1 libytdzlib1.so "$patch_dir/libffmpeg.so"
  fi

  echo "Resolving dependencies (lazy fetch, skip rubberband/OpenCL)..."
  strip_skip_needed_from_dir "$patch_dir"
  resolve_closure "$patch_dir"
  strip_skip_needed_from_dir "$patch_dir"
  fixup_needed_aliases "$patch_dir"
  prune_unreachable_libs "$patch_dir"
  converge_bundle "$patch_dir"
  add_opencl_stub_to_bundle "$patch_dir"

  local cxx
  cxx="$(find_termux_lib libc++_shared.so)" || true
  if [[ -n "$cxx" ]]; then
    cp -Lf "$cxx" "$patch_dir/libc++_shared.so"
  fi

  finalize_android_bundle "$patch_dir"

  echo "Verifying bundle..."
  verify_patch_dir "$patch_dir"
  verify_android_bundle "$patch_dir"

  cp -f "$patch_dir"/* "$dest/"
  chmod +x "$dest/libffmpeg.so"

  local count
  count="$(find "$dest" -maxdepth 1 -name '*.so' ! -name 'libsyncthing.so' | wc -l | tr -d ' ')"
  echo "Installed ffmpeg jni bundle under $dest ($count libraries)"
}

require_patchelf

echo "Downloading Termux ffmpeg (aarch64)..."
mkdir -p "$WORK_DIR/debs"
curl -fsSL -o "$WORK_DIR/ffmpeg.deb" "$FFMPEG_DEB_URL"
extract_deb "$WORK_DIR/ffmpeg.deb" "$WORK_DIR/ffmpeg"
register_lib_dir "$WORK_DIR/ffmpeg"

TERMUX_BIN="$WORK_DIR/ffmpeg/data/data/com.termux/files/usr/bin/ffmpeg"
if [[ ! -f "$TERMUX_BIN" ]]; then
  echo "ffmpeg binary not found in Termux package"
  exit 1
fi

download_termux_package "libc++"

install_ffmpeg_jni "$PLUGIN_JNI" "$TERMUX_BIN"

if [[ -d "$ROOT_DIR/mobile/android" ]]; then
  install_ffmpeg_jni "$APP_JNI" "$TERMUX_BIN"
fi

ASSET_DIRS=(
  "$ROOT_DIR/mobile/plugins/media-audio-finder/android/src/main/assets/bundled-ffmpeg"
  "$ROOT_DIR/mobile/android/app/src/main/assets/bundled-ffmpeg"
)
for dir in "${ASSET_DIRS[@]}"; do
  [[ -d "$dir" ]] && rm -rf "$dir"
done

echo "Done. Rebuild APK: ./build_and_install_android_app.sh"
