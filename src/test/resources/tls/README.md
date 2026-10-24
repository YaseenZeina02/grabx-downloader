# Disposable TLS test fixtures

These certificates and `server.p12` belong only to the offline loopback HTTPS
regression tests in VerifiedConnectionsTest. They are not production credentials.
The PKCS#12 password is intentionally public: `fixture-password`.
Never trust these test roots in the application, JVM, operating system or browser.
