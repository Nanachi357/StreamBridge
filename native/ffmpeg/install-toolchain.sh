#!/usr/bin/env bash
set -euo pipefail
snapshot=http://snapshot.debian.org/archive/debian/20261103T000000Z
printf 'deb [check-valid-until=no] %s bookworm main\n' "$snapshot" > /etc/apt/sources.list
rm /etc/apt/sources.list.d/debian.sources
apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=30 update
packages=(ca-certificates curl xz-utils make gcc libc6-dev gcc-mingw-w64-x86-64-win32 binutils-mingw-w64-x86-64 nasm pkg-config gnupg)
mkdir -p /var/cache/apt/archives/partial
# Use the frozen, signature-checked index. A mirror may provide the exact bytes
# faster, but must match the SHA-256 from that index; never substitute versions.
apt-get -o Acquire::ForceHash=SHA256 --print-uris --yes --no-install-recommends install "${packages[@]}" > /tmp/packages.txt
while read -r quoted name size hash; do
    [[ "$quoted" == \'http* ]] || continue
    url=${quoted//\'/}
    [[ "$hash" == SHA256:* ]] || { echo 'Missing strong package checksum' >&2; exit 1; }
    mirror=${url/"$snapshot"/http:\/\/deb.debian.org\/debian}
    /usr/lib/apt/apt-helper -o Acquire::Retries=1 -o Acquire::http::Timeout=30 download-file \
        "$mirror" "/var/cache/apt/archives/$name" "$hash" || \
    /usr/lib/apt/apt-helper -o Acquire::Retries=1 -o Acquire::http::Timeout=30 download-file \
        "$url" "/var/cache/apt/archives/$name" "$hash"
done < /tmp/packages.txt
cp /tmp/packages.txt /toolchain-downloads.txt
apt-get --no-download --yes --no-install-recommends install "${packages[@]}"
rm -rf /var/lib/apt/lists/*
