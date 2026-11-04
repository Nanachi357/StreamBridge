# Frozen base and Debian package index: updating either is a reviewed toolchain change.
FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3
COPY install-toolchain.sh /install-toolchain.sh
RUN bash /install-toolchain.sh
WORKDIR /work
ENTRYPOINT ["bash", "/recipe/build.sh"]
