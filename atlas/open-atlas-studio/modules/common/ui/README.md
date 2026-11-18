# Shared graph access UI

Browser-only components used by Studio and Server: one-time token display,
copy with an HTTP-compatible fallback, expiry-aware token list and revocation.
Callbacks keep each host's session API and authorization separate. This module
is an application `:ui` dependency, not a production JVM dependency.
