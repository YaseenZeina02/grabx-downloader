package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedConnectionsTest {
    private X509Certificate certificate(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/tls/" + name + ".der")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }
    private KeyStore roots() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
        store.setCertificateEntry("root", certificate("root")); return store;
    }
    private X509TrustManager supplemented() throws Exception {
        return VerifiedConnections.trustManager(roots(), List.of(certificate("issuer")));
    }
    @Test void incompleteChainNeedsIntermediateButDoesNotNeedANewTrustedRoot() throws Exception {
        X509Certificate[] leaf = {certificate("leaf")};
        var missing = VerifiedConnections.trustManager(roots(), List.of());
        assertThrows(CertificateException.class, () -> missing.checkServerTrusted(leaf, "RSA"));
        supplemented().checkServerTrusted(leaf, "RSA");
        assertEquals(1, supplemented().getAcceptedIssuers().length);
        assertEquals(certificate("root"), supplemented().getAcceptedIssuers()[0]);
    }
    @Test void untrustedExpiredAndTamperedCertificatesRemainRejected() throws Exception {
        var verifier = supplemented();
        assertThrows(CertificateException.class, () -> verifier.checkServerTrusted(new X509Certificate[]{certificate("untrusted")}, "RSA"));
        assertThrows(CertificateException.class, () -> verifier.checkServerTrusted(new X509Certificate[]{certificate("expired")}, "RSA"));
        byte[] bytes = certificate("leaf").getEncoded(); bytes[bytes.length - 1] ^= 1;
        var tampered = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
        assertThrows(CertificateException.class, () -> verifier.checkServerTrusted(new X509Certificate[]{tampered}, "RSA"));
        var extraRoot = VerifiedConnections.trustManager(roots(), List.of(certificate("untrusted")));
        assertThrows(CertificateException.class, () -> extraRoot.checkServerTrusted(new X509Certificate[]{certificate("untrusted")}, "RSA"));
    }
    @Test void productionSupplementNeverAddsIssuersToConfiguredTrustAnchors() throws Exception {
        var original = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        original.init((KeyStore) null);
        var baseline = (X509TrustManager) original.getTrustManagers()[0];
        assertEquals(java.util.Set.of(baseline.getAcceptedIssuers()), java.util.Set.of(VerifiedConnections.trustManager().getAcceptedIssuers()));
    }
    @Test void httpsStillRejectsWrongHostnameEvenWithValidTrustedChain() throws Exception {
        SSLContext serverContext = SSLContext.getInstance("TLS");
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/tls/server.p12")) { serverKeys.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(serverKeys, "fixture-password".toCharArray()); serverContext.init(keys.getKeyManagers(), null, null);
        try (var server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var served = CompletableFuture.runAsync(() -> {
                try (var socket = (SSLSocket) server.accept()) { socket.setSoTimeout(3000); socket.startHandshake(); }
                catch (IOException expected) { }
            });
            SSLContext client = SSLContext.getInstance("TLS"); client.init(null, new TrustManager[]{supplemented()}, null);
            // Fixture certificate is valid for localhost, not the IP address.
            var connection = (HttpsURLConnection) new URI("https://127.0.0.1:" + server.getLocalPort()).toURL().openConnection();
            connection.setSSLSocketFactory(client.getSocketFactory()); connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
            try { assertThrows(SSLHandshakeException.class, connection::getResponseCode); } finally { connection.disconnect(); }
            served.get(5, TimeUnit.SECONDS);
        }
    }
}
