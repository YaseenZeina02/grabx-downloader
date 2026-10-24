# HTTPS certificate chains

GrabX keeps Java PKIX certificate validation and the normal HTTPS hostname check.
Some file servers send only their leaf certificate and omit the issuing chain.
The app supplies public SSL2BUY EMEA / Sectigo R46 intermediates as path-building
candidates for direct downloads, browser file inspection, and size probes.

The JVM's configured trust store remains the only source of trust anchors. Bundled
self-signed certificates are excluded; intermediates are not installed in the OS
or Java trust store. Expiry, signatures, constraints and hostname checks remain
required. Invalid chains still fail. No trust-all fallback, HTTP downgrade or global
AIA fetching is enabled. If the helper cannot initialize, it falls back to standard
Java TLS validation, not insecure connections.

Public certificate sources are recorded beside the bundled resources. Offline tests
cover incomplete-chain recovery, unchanged trusted roots, tampered/untrusted/expired
certificate rejection and an actual local HTTPS handshake with a mismatched hostname.
On 2026-10-23 the same s201d1.downet.net server passed a verified TLS connection
through both the app helper and the staged native bridge (its root returns HTTP 401,
which is separate from TLS verification). No complete film was downloaded for this check.
