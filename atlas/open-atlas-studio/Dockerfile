# Build from the repository root with open-atlas-release scripts/release/build-images.sh studio --source .
# (repo-split §7.1): it writes build-info/studio/BUILD.json and copies the pinned load-env.sh to build-info/.
FROM node:22-bookworm-slim AS node
FROM clojure:temurin-21-tools-deps AS build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
WORKDIR /build
COPY apps/studio/package.json apps/studio/package-lock.json ./apps/studio/
RUN cd apps/studio && npm ci --no-audit --no-fund
COPY deps.edn ./
COPY modules ./modules
COPY apps/studio ./apps/studio
COPY fixtures/dvd ./fixtures/dvd
COPY fixtures/demo ./fixtures/demo
COPY docs/help ./docs/help
COPY docs/contracts/ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md ./docs/contracts/
COPY scripts/build-studio.sh ./scripts/build-studio.sh
RUN bash scripts/build-studio.sh

FROM eclipse-temurin:21-jre-jammy AS runtime
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="Open Atlas Builder" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.licenses="Elastic-2.0"
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl openssl iproute2 \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 10001 atlas \
 && useradd --uid 10001 --gid 10001 --home-dir /data/studio --no-create-home atlas \
 && mkdir -p /data/studio && chown 10001:10001 /data/studio
WORKDIR /opt/open-atlas
COPY --from=build /build/dist/studio ./dist/studio
COPY bin/start-studio.sh ./bin/start-studio.sh
# load-env.sh comes from the pinned open-atlas-release version (tools.lock), never a copy kept in this repo.
COPY build-info/load-env.sh ./scripts/load-env.sh
# doc 110 §110.3: BUILD.json from scripts/release/build-images.sh; optional, so a plain build still works.
COPY LICENSE build-info/studio/BUILD.jso[n] ./
ENV OPEN_ATLAS_PACKAGE_ROOT=/opt/open-atlas
ENV HOME=/data/studio \
    OPEN_ATLAS_STUDIO_HOME=/data/studio \
    OPEN_ATLAS_STUDIO_BUILDER_HOST=0.0.0.0 \
    OPEN_ATLAS_STUDIO_DATA_HOST=0.0.0.0
USER 10001:10001
EXPOSE 9631 9632
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD curl -fsSk https://127.0.0.1:9631/ > /dev/null || curl -fsS http://127.0.0.1:9631/ > /dev/null || exit 1
ENTRYPOINT ["bash", "/opt/open-atlas/bin/start-studio.sh", "--foreground"]
