# v3 implementation notes

The combined graph is authoritative; development builder only extends/replaces
v3 artifacts and retains legacy bindings. Legacy reference attr shards are not
parsed as production truth. Tests no longer assert old past-due-only ratio behavior.

The latest trace omits identity fields and contains identical requested Vendor
parameters with different returned totals. The new protocol detects this, but
its original server/log/cache root cause is UNCONFIRMED; no production access was
attempted and no historical token reused.

Model rules are not hard enforcement. Runtime prototype is archived and excluded
from production packaging. Optional local helpers remain development assets only.
Formal platform config-check, deployed JDBC, performance and real Agent acceptance
must be completed after deployment. See docs/VALIDATION_V3.md for exact local status.
