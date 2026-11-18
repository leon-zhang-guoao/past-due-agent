# Open Atlas Server

The testable Server runtime is graph-native and instance-local.

- An administrator's service key signs graph import commands.
- Each imported graph version is stored and cached by `graph UUID + version`.
- A graph token grants EQL access to exactly one graph.
- `POST /eql` keeps the existing EQL request form; it is not the retired Query
  Builder.

## Start a test instance

From this directory, set separate values for the runtime, service signing, and
graph actor signing:

```bash
export OPEN_ATLAS_SERVER_HOME=/absolute/path/to/server-runtime
export OPEN_ATLAS_SERVER_INSTANCE=server-local
export OPEN_ATLAS_SERVER_TOKEN_PEPPER=replace-with-a-secret
export OPEN_ATLAS_SERVER_SERVICE_KEY_ID=studio-service
export OPEN_ATLAS_SERVER_SERVICE_SECRET=replace-with-a-secret
export OPEN_ATLAS_SERVER_ACTOR_KEY_ID=server-actor
export OPEN_ATLAS_SERVER_ACTOR_SECRET=replace-with-a-secret

clojure -M:prod:drivers:runtime
```

`:prod` is the production basis (see deps.edn, and
[8.12](../../docs/design/93-standalone-server-production.md)). It used to be
load-bearing: it replaced `:paths`/`:deps` so the inherited `ent.dx.*` stack was
not merely unused but absent. That stack was retired in
[8.16](../../docs/design/97-server-legacy-stack-retirement.md), so the default
basis and `:prod` now describe the same thing; `:prod` stays as the name the
scripts and the image use, and as a guard should a second stack ever appear.

`:drivers` is the JDBC drivers. They are **not** in the jar: a packaged Server
carries them in `drivers/` beside it, and the launcher puts that directory on
the classpath. Discovery is unchanged (`META-INF/services/java.sql.Driver`), so
adding an engine is dropping a jar in, and removing one is deleting a file.
Leave `:drivers` off and a connection fails loudly — `No suitable driver found
for jdbc:…` — never as an empty result.

The production Server uses one HTTP listener, on loopback port **3009** by default:

- operator console: `http://127.0.0.1:3009/`
- signed deployment: `http://127.0.0.1:3009/control`
- EQL: `http://127.0.0.1:3009/eql`
- MCP: `http://127.0.0.1:3009/mcp/<graph-uuid>` (UUID required; generic `/mcp` and legacy SSE endpoints are disabled)
- health and metrics: `/health`, `/metrics`

Set `OPEN_ATLAS_SERVER_PORT` to override the shared port. The old
`OPEN_ATLAS_SERVER_CONSOLE_PORT` remains a fallback; `OPEN_ATLAS_SERVER_CONTROL_PORT`
and `OPEN_ATLAS_SERVER_DATA_PORT` no longer create listeners. Update deployment
clients and query clients that previously used 8443/8444 to use 3009.

Each route retains its own authentication: the console uses an operator session,
`/control` verifies service signatures, and EQL/MCP verify graph tokens. Sharing
an HTTP listener does not grant one credential access to another route.
The console signs commands as the signed-in operator so audits record who asked.

Create the first operator before starting the server (the password is read from
stdin, never from argv):

```bash
clojure -M:prod:bootstrap-admin --username admin
```

Until an operator exists the console refuses every request: there is no "nobody
is configured yet, so let anyone in" branch.

Later operators are added from the console's Operators tab by an operator who is
already signed in — this command is only ever for the first one.

The console's page is ClojureScript and is build output, so build it before
serving (or before building the image):

```bash
npm run console:release
```

`npm run console:dev` watches it instead. Without a build the console answers
500 with a message saying so — never a blank page with a silent 404.

## Import a graph

Use the same signed Studio deployment protocol as the Studio runtime:

```text
offer → stage → load → activate
```

Send those signed envelopes to the Server control plane. There is deliberately
no unauthenticated upload endpoint: it would bypass graph-version validation
and activation. Once `activate` succeeds, a graph token issued for that graph
can query it through `POST /eql`.

## Configure production Sources

Open the main sidebar **Sources** shortcut, or **Groups & Graphs → a Group → Sources**,
to create or edit SQL, API or NiFi
connections and test them. New Sources default to the selected Group; search,
paging and bulk moves help maintain the list.
Saving tests the connection first; an explicit checkbox permits saving an
unverified connection. Connection validation uses the same connector SPI and connection form metadata
as Studio. Additional options use the connector's EDN keys; passwords and
API tokens should be variable references, for example `${PROD_DB_PASSWORD}`.
The **Credential variables** form accepts write-only values. Studio and Server
share `runtime/credentials`: values in the instance store take precedence over
the process environment, and responses expose names and presence only.

Choose a Group and **Import Graph**, select a Studio delivery ZIP, and choose a
production Source for each required slot. **Check readiness** is read-only.
Import saves release-scoped bindings and loads the version; in its Graph detail,
**Review latest release → Publish** runs the shared signed deployment pipeline. Missing/incompatible Sources and
missing credentials block publication. Current Semantic Contract artifacts and
legacy delivery artifacts use the same runtime artifact reader.

Connections live in `<home>/sources.edn`, credentials in `<home>/credentials.edn`,
and published bindings in `<home>/source-bindings.edn`. Changing a registered
connection does not rewrite an existing release's bindings; publish a new
release to use it. Updating a credential variable is an explicit instance-local
credential change, shared by Sources that reference that variable.
Studio and Server keep separate instance homes and token ledgers.

## Focused verification

```bash
clojure -M:prod:drivers:test-prod
```

That runs on the **production** basis, which is what the image runs. The suite
includes the guard for a failure that is silent by construction: a connector
missing from the classpath does not throw — every resolver of that source type
is excluded and `/eql` answers 200 with no rows (8.12.3).

This is the app's only suite. The inherited `ent.dx.*` stack — which started
with `clojure -M:run` and had its own `clojure -M:test` — was retired in
[8.16](../../docs/design/97-server-legacy-stack-retirement.md); the history is
in `git log -- apps/server/src/ent`.

## Build the production image

The build context is the **repository root**, not this directory — this app's
dependencies on `modules/**` are `:local/root` entries, so a context rooted here
cannot resolve a classpath at all:

```bash
docker build -f apps/server/Dockerfile -t open-atlas-server .
```

The image runs `java -cp "open-atlas-server.jar:drivers/*" open.atlas.server.main`
— `-jar` would ignore the classpath, and the drivers are beside the jar.

The image contains the standalone Server only — now because it is the only
thing in the app. Before 8.16 that was settled by `deps.edn`'s `:prod` alias
replacing `:paths`, rather than by anything the Dockerfile remembered to leave
out.

Create the first operator against the volume you will run with, then start:

```bash
docker run --rm -i -v atlas:/instance -e OPEN_ATLAS_SERVER_INSTANCE=prod-1 \
  --entrypoint java open-atlas-server \
  -cp open-atlas-server.jar open.atlas.server.identity.bootstrap --username admin
```

The instance root (`/instance`) holds the artifact shelf, the token ledger, the
operator database, the logs and the audit trail. Mount a volume there: an
instance is a directory, and losing it loses what this Server was serving and
every token it issued.

Inside the image the listeners bind `0.0.0.0` (`OPEN_ATLAS_SERVER_BIND`) —
loopback in a container means the ports are reachable from nothing. Outside it
the default stays `127.0.0.1`, and any other value makes the server say so on
startup: the shared listener is then reachable from the network, and between
it and a stranger there is a key, a token and a password.

Without Docker, `scripts/build-server.sh` produces the same jar in `dist/` and
`bin/start-server.sh` runs it.

## Server background lifecycle

`bin/start-server.sh`, `bin/stop-server.sh`, and `bin/restart-server.sh` manage
compiled Server instances. The corresponding `dev/` scripts run from source.
Start returns with Server running in the background, surviving terminal exit.
Use the same `OPEN_ATLAS_SERVER_HOME` for each command; logs are written to
`<server-home>/logs/server.log` and process records to `<server-home>/run/`.
Production defaults to `~/.open-atlas/servers/default`; development retains
`~/.open-atlas/server`. All Server endpoints still share port 3009.

Use `start-server.sh --foreground` under Docker/systemd. Background launch
confirms process survival, not HTTP readiness. Stop only signals the matching
managed instance; restart waits for it to stop before starting again. This does
not install a boot service or automatically restart after a crash.

## Graph lifecycle

Studio creates the artifact; Server imports it as a production Graph. In the
console, both uploaded releases and bundled packages use **Import Graph** to
validate Sources and load a version without changing the serving pointer.
**Publish** rechecks readiness and runs the shared signed deployment pipeline
before activating the reviewed version. Use **Review latest release**, or select
a version from the paged **Versions** history. Stale serving state
is refused; refresh before publishing again.

**Archive Graph** is reversible and preserves versions, existing service and
access-token records. Restore before importing, publishing or issuing new keys.
**Delete Graph** requires an archived Graph and its exact name as confirmation.
It checks retained/staged dependencies first, stops serving, revokes keys, and
removes owned artifacts. A cleanup failure retains the archived registration
for retry. Source instances and historical audit/deployment evidence remain.

Application access uses the same token ledger, one-time secret/clipboard
component and revoke list as Studio. Keys follow this Graph's serving version
and are valid only on the issuing instance. The issue API also accepts
`expires-at`; no secret is returned by list or revoke.

For existing API callers, `/api/console/graphs/import` and package install keep
the combined import-and-activate default. Send `:activate? false` for the
console's separate import/publish flow. Archive/restore/delete/publish routes
require the same authenticated operator session as other console writes.


## Group-based production management

Use **Groups & Graphs → Group → Graph → Overview / Versions / Access / Manage**.
The persistent **Sources** shortcut opens the same grouped view from Graph details
or other console pages and preserves the current Group filter.
Groups belong to this Server instance and are independent of Studio workspaces.
The sidebar is intended for about ten Groups; All Graphs and Ungrouped provide
instance-wide discovery and a home for existing imports.

- Create, rename and remove Groups; move selected Graphs in one action.
- Search, filter by state, sort and page the compact Graph register. Opening a
  Graph and returning preserves the register's query, Group and page.
- Import a new version into the chosen Graph, review the serving/target version,
  publish, issue/revoke tokens, pause/resume service, archive/restore or delete.
- Sources share this Group registry. Configure, search and move them in the
  Group's Sources tab. Credential variables remain instance-wide.
- Tokens inherit their Graph's Group dynamically. Moving a Graph immediately
  changes its tokens' grouping without reissuing keys. There is no separate
  token-group assignment or new permission boundary.
- Removing a Group moves its Graphs and Sources to Ungrouped. It does not delete
  resources, change published bindings or affect access. Permanent Graph deletion
  removes only that Graph's membership; Sources remain.

The Graph list, version history and token list use bounded read APIs (20 rows by
default, at most 50). History is requested only when opened, and catalog responses
never include artifact content or nested token/version histories. Aggregate counts
still scan local shelf coordinates and token records; this is not an indexed
catalog. Sources reuse the existing registry read and paginate the rendered rows.
Group metadata is stored in the instance root's `graph-groups.edn`.

### Query diagnostics

Server records `query-executed` events in `$OPEN_ATLAS_SERVER_HOME/logs/app.jsonl`.
Each event includes the parsed EQL request as EDN (`eql`), verified `token-id`,
Graph/version, outcome, duration and request ID. HTTP requests also carry the
socket peer (`remote-addr`), path and client-supplied User-Agent; MCP adds the tool
name. Forwarded headers are not treated as verified client addresses. When a
proxy or Docker sits between client and Server, the peer can be that intermediary.
The Bearer credential and query results are not logged. EQL includes supplied
parameters, so these logs contain query input data. Existing structured audit
records remain separate and omit query values.

Log lifecycle: see [shared retention policy](../../docs/guides/log-retention.md).
