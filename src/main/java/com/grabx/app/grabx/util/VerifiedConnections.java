package com.grabx.app.grabx.util;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;

/** Supplies missing intermediates without adding trust roots or bypassing HTTPS hostname checks. */
public final class VerifiedConnections {
    private VerifiedConnections() { }
    public static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection https) https.setSSLSocketFactory(Holder.SOCKETS);
        return connection;
    }
    private static final class Holder {
        static final SSLSocketFactory SOCKETS = sockets();
    }
    private static SSLSocketFactory sockets() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager()}, null);
            return context.getSocketFactory();
        } catch (Exception error) {
            AppLog.get(VerifiedConnections.class).warning("Could not load supplemental TLS intermediates; using standard certificate validation");
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
    public static X509TrustManager trustManager() throws Exception {
        List<java.security.cert.Certificate> intermediates = new ArrayList<>();
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        for (String name : List.of("ssl2buy-emea-rsa.crt", "sectigo-r46-chain.p7c")) {
            try (var input = VerifiedConnections.class.getResourceAsStream("/com/grabx/app/grabx/security/" + name)) {
                if (input == null) throw new IOException("Missing supplemental certificates");
                for (var certificate : certificates.generateCertificates(input)) {
                    X509Certificate x509 = (X509Certificate) certificate;
                    if (!x509.getIssuerX500Principal().equals(x509.getSubjectX500Principal())) intermediates.add(x509);
                }
            }
        }
        return trustManager(null, intermediates);
    }
    static X509TrustManager trustManager(KeyStore trustStore, Collection<java.security.cert.Certificate> intermediates) throws Exception {
        TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init(trustStore);
        Set<TrustAnchor> anchors = new HashSet<>();
        for (TrustManager manager : defaults.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509)
                for (X509Certificate issuer : x509.getAcceptedIssuers()) anchors.add(new TrustAnchor(issuer, null));
        }
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, new X509CertSelector());
        parameters.setRevocationEnabled(Boolean.getBoolean("com.sun.net.ssl.checkRevocation"));
        // Path-building candidates only. None becomes a trust anchor.
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(intermediates)));
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        factory.init(new CertPathTrustManagerParameters(parameters));
        for (TrustManager manager : factory.getTrustManagers())
            if (manager instanceof X509TrustManager x509) return x509;
        throw new CertificateException("No PKIX trust manager");
    }
}
