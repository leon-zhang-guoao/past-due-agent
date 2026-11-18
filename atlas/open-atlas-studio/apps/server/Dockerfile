# The standalone Open Atlas Server (docs/design/93-standalone-server-production.md).
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not this directory:
#
#     docker build -f apps/server/Dockerfile -t open-atlas-server .
#
# It has to be. This app's dependencies on `modules/**` are `:local/root`
# entries in deps.edn, so a context rooted at apps/server cannot resolve a
# classpath at all — not even `clojure -P`.
#
# This image used to have to argue that it contained no second stack: the
# inherited `ent.dx.*` namespaces lived in the same app and were kept out by
# `deps.edn`'s `:prod` alias replacing `:paths`. That stack was retired in
# 8.16, so there is nothing left to keep out.

# ── Build stage ──────────────────────────────────────────────────────────────
FROM node:22-bookworm-slim AS node
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /build

# Node is the ONE build dependency beyond the JDK: the console's page is
# ClojureScript compiled by shadow-cljs. Without it the image would serve a
# console that answers 500 saying the build has not run. (Python is no longer
# needed — it belonged to the legacy demo-database bundling.)
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

# Warm the dependency caches before the sources, so a source edit does not
# re-resolve Maven. The module deps.edn files are part of the classpath's
# definition, so they come along.
COPY deps.edn ./deps.edn
COPY modules ./modules
COPY apps/server/deps.edn apps/server/build.clj apps/server/
# `clojure -P` for each basis this build actually uses: the production one, the
# build tooling, and the UI alias shadow-cljs resolves from.
RUN cd apps/server \
 && clojure -P -A:prod && clojure -P -T:build && clojure -P -A:ui

COPY apps/server/package.json apps/server/package-lock.json apps/server/
RUN cd apps/server && npm ci

COPY apps/server/shadow-cljs.edn apps/server/
COPY apps/server/src-ui apps/server/src-ui
COPY apps/server/src-runtime apps/server/src-runtime
COPY apps/server/resources-console apps/server/resources-console
COPY LICENSE ./LICENSE
COPY fixtures/dvd ./fixtures/dvd

# The console's script must exist BEFORE the uberjar is built: `uber` copies
# resources-console into the jar.
RUN cd apps/server && npx shadow-cljs release console
RUN cd apps/server && clojure -T:build uber && clojure -T:build drivers

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.version=$VERSION org.opencontainers.image.revision=$REVISION
LABEL org.opencontainers.image.licenses="Elastic-2.0"
WORKDIR /app
# curl is here for one reason: the healthcheck below. A healthcheck that only
# proves the JVM starts would stay green while every listener was refusing.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/apps/server/target/open-atlas-server.jar ./open-atlas-server.jar
# The JDBC drivers ship BESIDE the jar (8.13). The image carries all of them,
# so it connects out of the box; mounting a volume over /app/drivers, or
# rebuilding with a different set, is now an operator's decision rather than
# ours. Discovery is unchanged: META-INF/services/java.sql.Driver.
COPY --from=build /build/apps/server/target/drivers ./drivers
# ELv2 requires that whoever receives a copy of the software receives the
# terms. The file landed in the repository on 2026-09-01 (before that this
# image's label was a claim nothing backed, and the previous Dockerfile copied
# a LICENSE that did not exist, so it could not build at all).
COPY --from=build /build/LICENSE ./LICENSE

# The instance root: the artifact shelf, the token ledger, the operator
# database, the logs and the audit trail all live under it. Mount a volume
# here — an instance is a directory, and losing it is losing what this Server
# was serving and every token it issued.
ENV OPEN_ATLAS_SERVER_HOME=/instance
# Inside a container, loopback means the ports are reachable from nothing. The
# default in `open.atlas.server.main` stays 127.0.0.1 for a laptop; the image
# widens it deliberately, and the server says so on startup.
ENV OPEN_ATLAS_SERVER_BIND=0.0.0.0

# control plane · data plane (+ /mcp) · operator console
EXPOSE 3009

# `/health` is on the control plane, needs no credential, and says nothing
# about what this instance holds.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${OPEN_ATLAS_SERVER_PORT:-${OPEN_ATLAS_SERVER_CONSOLE_PORT:-3009}}/health || exit 1

# A deterministic uid/gid so a Kubernetes podSecurityContext can match it by
# number, and so a mounted volume inherits an ownership the process can write.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 app \
 && mkdir -p /instance \
 && chown -R app:app /app /instance
USER app

# The server refuses to start without every one of its secrets, separately
# (see `open.atlas.server.main/config`): OPEN_ATLAS_SERVER_INSTANCE,
# _TOKEN_PEPPER, _SERVICE_KEY_ID, _SERVICE_SECRET, _ACTOR_KEY_ID, _ACTOR_SECRET.
# Create the first operator once, against the same volume:
#
#   docker run --rm -i -v atlas:/instance -e OPEN_ATLAS_SERVER_INSTANCE=prod-1 \
#     --entrypoint java open-atlas-server \
#     -cp "open-atlas-server.jar:drivers/*" \
#     open.atlas.server.identity.bootstrap --username admin
#
# `-cp`, not `-jar`: `-jar` ignores the classpath, and the drivers are beside
# the jar. The shell form is deliberate — `drivers/*` is a JVM classpath
# wildcard the JVM expands itself, not a shell glob.
ENTRYPOINT ["java", "-cp", "open-atlas-server.jar:drivers/*", "open.atlas.server.main"]
