#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1793664000
source /recipe/sources.lock
archive="ffmpeg-${FFMPEG_VERSION}.tar.xz"
mkdir -p /out/sources /out/evidence
if [[ ! -f "/out/sources/$archive" ]]; then
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
        "$FFMPEG_URL" -o "/out/sources/$archive.partial"
    echo "$FFMPEG_SHA256  /out/sources/$archive.partial" | sha256sum --check --strict
    mv "/out/sources/$archive.partial" "/out/sources/$archive"
fi
echo "$FFMPEG_SHA256  /out/sources/$archive" | sha256sum --check --strict
# Build on the container filesystem, not the Windows bind mount. Never overwrite an output.
test ! -e /out/runtime
tar -xJf "/out/sources/$archive" --no-same-owner
cd "ffmpeg-${FFMPEG_VERSION}"
args=(
    --prefix=/work/install --target-os=mingw32 --arch=x86_64 --enable-cross-compile
    --cross-prefix=x86_64-w64-mingw32- --cc=x86_64-w64-mingw32-gcc-win32
    --enable-shared --disable-static --disable-autodetect --disable-debug --disable-doc
    --disable-gpl --disable-nonfree --disable-version3 --disable-avdevice --disable-ffplay
    --disable-network --disable-hwaccels --disable-encoders
    --enable-encoder=wrapped_avframe,pcm_s16le
    --disable-demuxers --enable-demuxer=mpegts,mov,aac
    --disable-muxers --enable-muxer=mp4,null
    --disable-protocols --enable-protocol=file,pipe
    --extra-ldflags=-static-libgcc
)
printf '%q ' ./configure "${args[@]}" > /out/evidence/configure-command.txt
printf '\n' >> /out/evidence/configure-command.txt
./configure "${args[@]}" > /out/evidence/configure.log 2>&1 || {
    cp ffbuild/config.log /out/evidence/configure-failure.log
    exit 1
}
cp config.h config_components.h ffbuild/config.mak /out/evidence/
make -j "${BUILD_JOBS:-4}" > /out/evidence/compile.log 2>&1
make install > /out/evidence/install.log 2>&1
# Compile and link against the installed development set without executing Windows code.
cat > /work/development-smoke.c <<'EOF'
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
int main(void) {
    return !(avcodec_version() && avfilter_version() && avformat_version() &&
             avutil_version() && swresample_version() && swscale_version());
}
EOF
x86_64-w64-mingw32-gcc-win32 -I/work/install/include /work/development-smoke.c \
    -L/work/install/lib -lavcodec -lavfilter -lavformat -lavutil -lswresample -lswscale \
    -o /work/development-smoke.exe > /out/evidence/development-smoke.log 2>&1
echo 'PASS: all six public headers and import libraries compile and link' >> /out/evidence/development-smoke.log
mkdir -p /out/runtime /out/licenses/ffmpeg /out/licenses/toolchain /out/recipe /out/development
cp /work/install/bin/*.exe /work/install/bin/*.dll /out/runtime/
# Keep headers and import libraries with corresponding source for rebuilding/relinking.
cp -R /work/install/include /work/install/lib /out/development/
cp COPYING* LICENSE.md /out/licenses/ffmpeg/
cp libavcodec/jfdctfst.c libavcodec/jfdctint_template.c libavcodec/jrevdct.c /out/licenses/ffmpeg/
printf 'This software is based in part on the work of the Independent JPEG Group.\nNo local FFmpeg source modifications.\n' > /out/licenses/ffmpeg/NOTICE.txt
cp /recipe/build.sh /recipe/Build.ps1 /recipe/Dockerfile /recipe/install-toolchain.sh /recipe/sources.lock /recipe/README.md /recipe/Verify-Materials.ps1 /recipe/Read-PeImports.ps1 /recipe/development.sha256 /recipe/Test-Materials.ps1 /recipe/Test-OutputDirectory.ps1 /recipe/BUNDLE-NOTICE.txt /out/recipe/
cp /recipe/BUNDLE-NOTICE.txt /out/NOTICE.txt
cp /toolchain-downloads.txt /out/evidence/
dpkg-query -W > /out/evidence/debian-packages.txt
x86_64-w64-mingw32-gcc-win32 -v > /out/evidence/compiler.txt 2>&1
for package in mingw-w64-common mingw-w64-x86-64-dev gcc-mingw-w64-base gcc-mingw-w64-x86-64-win32 gcc-mingw-w64-x86-64-win32-runtime; do
    cp "/usr/share/doc/$package/copyright" "/out/licenses/toolchain/$package.copyright"
done
mkdir -p /out/licenses/toolchain/common-licenses
cp -L /usr/share/common-licenses/* /out/licenses/toolchain/common-licenses/
for file in /out/runtime/*; do
    x86_64-w64-mingw32-objdump -p "$file" > "/out/evidence/$(basename "$file").pe.txt"
done
(cd /out && find runtime licenses recipe sources development evidence NOTICE.txt -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
echo 'Build complete. Windows compatibility and redistribution review are separate gates.'
