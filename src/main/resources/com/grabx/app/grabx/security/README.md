Supplemental public CA intermediates for incomplete server chains.
Retrieved 2026-10-23 from the issuer URLs declared in the certificates:
- http://crt.sectigo.com/SSL2BUYEMEARSADomainValidationSecureServerCA.crt
- http://crt.sectigo.com/SectigoPublicServerAuthenticationRootR46.p7c

VerifiedConnections uses these as CertStore path candidates, NOT trusted roots.
Self-signed entries are excluded. Trust anchors come only from the configured JVM
trust store; PKIX signatures, validity, constraints and HTTPS hostname checks remain
required. No global trust-store changes, permissive trust managers or automatic AIA
network fetching. The user explicitly approved this change on 2026-10-23 after TLS
verification failed due to the server's missing intermediate certificate chain.
