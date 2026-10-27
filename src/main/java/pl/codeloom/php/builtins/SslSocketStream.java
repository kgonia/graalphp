package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TLS-capable socket stream, as ext/openssl's xp_ssl.c (php_openssl_netstream_data_t): the stream of ssl://,
 * tls:// and tlsv1.x://, and of tcp://, which ext/openssl takes over so stream_socket_enable_crypto() works on it.
 * Until crypto is enabled it is a plain socket (php_stream_socket_ops underneath).
 *
 * <p>OpenSSL's SSL object is an SSLEngine over the stream's non-blocking channel, and the calls php-src makes are
 * emulated with OpenSSL's results: SSL_connect, SSL_read and SSL_write return a count or fail with SSL_get_error's
 * WANT_READ, WANT_WRITE, ZERO_RETURN, SYSCALL or SSL (with OpenSSL's error queue), so php-src's loops (handshake
 * and I/O with timeouts, php_openssl_handle_ssl_error's warnings) are ported as they are. As php-src sets
 * SSL_OP_IGNORE_UNEXPECTED_EOF, the peer closing without close_notify is ZERO_RETURN.
 *
 * <p>The client side is ported: verify_peer (with cafile, capath, allow_self_signed and verify_depth; the CAs
 * default to OpenSSL's default paths, or the JDK's trust store where the host has none, as on the Windows host),
 * verify_peer_name and peer_name (php-src's own name matching), peer_fingerprint, SNI_enabled, crypto_method,
 * min_proto_version, max_proto_version, alpn_protocols and security_level's warning. The JDK can't do what these
 * options ask, so they are not taken: ciphers (OpenSSL's cipher strings; PHP's default list is offered, in its
 * order), local_cert, local_pk and passphrase (client certificates), capture_peer_cert and
 * capture_peer_cert_chain (they return OpenSSLCertificate objects, which need ext/openssl), no_ticket, the
 * security level itself (the JDK's jdk.tls.disabledAlgorithms decides), and a session stream's session.
 */
final class SslSocketStream extends SocketStream {
    static final int IS_CLIENT = 1;
    static final int TLSv1_0 = 1 << 3;
    static final int TLSv1_1 = 1 << 4;
    static final int TLSv1_2 = 1 << 5;
    static final int TLSv1_3 = 1 << 6;
    /** STREAM_CRYPTO_METHOD_TLS_ANY_CLIENT. */
    static final int TLS_ANY_CLIENT = TLSv1_0 | TLSv1_1 | TLSv1_2 | TLSv1_3 | IS_CLIENT;
    /** PHP_OPENSSL_MIN_PROTO_VERSION: the reference build's OpenSSL has no SSLv3 (OPENSSL_NO_SSL3). */
    private static final int MIN_PROTO = TLSv1_0;
    private static final int MAX_PROTO = TLSv1_3;
    /** OPENSSL_DEFAULT_STREAM_VERIFY_DEPTH. */
    private static final long DEFAULT_VERIFY_DEPTH = 9;

    // SSL_get_error's results
    private static final int SSL_ERROR_SSL = 1;
    private static final int SSL_ERROR_WANT_READ = 2;
    private static final int SSL_ERROR_WANT_WRITE = 3;
    private static final int SSL_ERROR_SYSCALL = 5;
    private static final int SSL_ERROR_ZERO_RETURN = 6;

    /** connect_timeout: the handshake's (stream_socket_client's timeout). */
    private final Network.Timeval connectTimeout;
    boolean enableOnConnect;
    int method;
    /** url_name: the host, for SNI and the peer name check. */
    private final String urlName;
    /** ssl_handle (with ctx): null until stream_socket_enable_crypto or the connect sets crypto up. */
    private SSLEngine engine;
    private boolean isClient;
    private boolean sslActive;
    private boolean stateSet;
    private ByteBuffer netIn;
    private ByteBuffer netOut;
    /** Decrypted bytes not read yet: SSL_pending. */
    private ByteBuffer appIn;
    /** SSL_write's bytes sent as a record that is still going out: the retry reports them. */
    private int pendingWrite;
    /** SSL_get_error of the last call, OpenSSL's error queue for SSL_ERROR_SSL, and errno as php-src reads it. */
    private int sslError;
    private final List<String> errorQueue = new ArrayList<>();
    private int errno;
    /** The verification's failure, if it failed the handshake. */
    private boolean verifyFailed;

    SslSocketStream(PhpContext context, String uri, Network.Timeval connectTimeout, String urlName) {
        super(context, "tcp_socket/ssl", uri);
        this.connectTimeout = connectTimeout;
        this.urlName = urlName;
    }

    // ---- php_openssl_ssl_socket_factory's helpers ----

    /** php_openssl_get_crypto_method: the context's ssl crypto_method (as a client's), or the default. */
    static int cryptoMethod(StreamContext streamContext, int method) {
        Object value = streamContext == null ? null : streamContext.option("ssl", "crypto_method");
        if (value != null) {
            return (int) Convert.toLong(value) | IS_CLIENT;
        }
        return method;
    }

    /** php_openssl_get_url_name: the host parse_url finds in "host:port", without trailing dots; null if none. */
    static String urlName(byte[] resourceName) {
        Object host = UrlFunctions.parseUrl(Str.of(resourceName), 1);
        if (!(host instanceof com.oracle.truffle.api.strings.TruffleString string)) {
            return null;
        }
        byte[] bytes = Str.bytes(string);
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == '.') {
            length--;
        }
        return length == 0 ? null : Network.latin1(Arrays.copyOf(bytes, length));
    }

    private Object option(String name) {
        return context() == null ? null : context().option("ssl", name);
    }

    private StreamContext context() {
        return context;
    }

    // ---- php_openssl_setup_crypto ----

    /** STREAM_XPORT_CRYPTO_OP_SETUP: 0, or -1 on a failure (warned). */
    @TruffleBoundary
    int setupCrypto(int cryptoMethod, FileFunctions.Stream session) {
        if (engine != null) {
            if (blocked) {
                Network.warn(php, ErrorFunctions.E_WARNING, "SSL/TLS already set-up for this stream");
                return -1;
            }
            return 0;
        }
        errorQueue.clear();
        isClient = (cryptoMethod & IS_CLIENT) != 0;
        int flags = cryptoMethod & ~IS_CLIENT;
        Object min = option("min_proto_version");
        Object max = option("max_proto_version");
        flags = protoVersionFlags(flags, min == null ? 0 : (int) Convert.toLong(min),
                max == null ? 0 : (int) Convert.toLong(max));
        Object verifyPeer = option("verify_peer");
        TrustManager trust;
        if (verifyPeer != null && !Convert.toBool(verifyPeer)) {
            trust = new Verifier(null, false, 0);
        } else {
            Set<TrustAnchor> anchors = enablePeerVerification();
            if (anchors == null) {
                return -1;
            }
            Object allowSelfSigned = option("allow_self_signed");
            Object depth = option("verify_depth");
            trust = new Verifier(anchors, allowSelfSigned != null && Convert.toBool(allowSelfSigned),
                    depth == null ? DEFAULT_VERIFY_DEPTH : Convert.toLong(depth));
        }
        Object level = option("security_level");
        if (level != null) {
            long value = Convert.toLong(level);
            if (value < 0 || value > 5) {
                Network.warn(php, ErrorFunctions.E_WARNING, "Security level must be between 0 and 5");
            }
        }
        String[] alpn = null;
        Object alpnOption = option("alpn_protocols");
        if (alpnOption != null) {
            String protocols = Network.latin1(cString(Str.bytes(Convert.toStr(alpnOption))));
            alpn = protocols.split(",", -1);
            for (String protocol : alpn) {
                if (protocols.length() >= 65535 || protocol.length() > 255) {
                    Network.warn(php, ErrorFunctions.E_WARNING,
                            "Failed parsing comma-separated TLS ALPN protocol string");
                    return -1;
                }
            }
            for (String protocol : alpn) {
                if (protocol.isEmpty()) {
                    Network.warn(php, ErrorFunctions.E_WARNING,
                            "Failed setting TLS ALPN protocols, protocol names must not be empty");
                    return -1;
                }
            }
        }
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {trust}, null);
            engine = sslContext.createSSLEngine();
        } catch (GeneralSecurityException e) {
            Network.warn(php, ErrorFunctions.E_WARNING, "SSL context creation failure");
            return -1;
        }
        engine.setUseClientMode(isClient);
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setProtocols(protocols(flags, engine.getSupportedProtocols()));
        parameters.setCipherSuites(Ciphers.offered(engine.getSupportedCipherSuites()));
        if (alpn != null) {
            parameters.setApplicationProtocols(alpn);
        }
        engine.setSSLParameters(parameters);
        int packet = engine.getSession().getPacketBufferSize();
        netIn = ByteBuffer.allocate(packet);
        netOut = ByteBuffer.allocate(packet);
        appIn = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        appIn.flip();
        if (session != null) {
            if (!(session instanceof SslSocketStream ssl)) {
                Network.warn(php, ErrorFunctions.E_WARNING,
                        "Supplied session stream must be an SSL enabled stream");
            } else if (ssl.engine == null) {
                Network.warn(php, ErrorFunctions.E_WARNING, "Supplied SSL session stream is not initialized");
            }
            // SSL_copy_session_id: an SSLEngine can't take another's session; this connection makes its own
        }
        return 0;
    }

    /** php_openssl_get_proto_version_flags: the method's versions, widened or narrowed to min..max. */
    private static int protoVersionFlags(int flags, int min, int max) {
        if (min == 0) {
            min = minProtoFlag(flags);
        }
        if (max == 0) {
            max = maxProtoFlag(flags);
        }
        for (int version = MIN_PROTO; version <= MAX_PROTO; version <<= 1) {
            if (version >= min && version <= max) {
                flags |= version;
            } else {
                flags &= ~version;
            }
        }
        return flags;
    }

    private static int minProtoFlag(int flags) {
        for (int version = MIN_PROTO; version <= MAX_PROTO; version <<= 1) {
            if ((flags & version) != 0) {
                return version;
            }
        }
        return MAX_PROTO;
    }

    private static int maxProtoFlag(int flags) {
        for (int version = MAX_PROTO; version >= MIN_PROTO; version >>= 1) {
            if ((flags & version) != 0) {
                return version;
            }
        }
        return TLSv1_3;
    }

    /**
     * SSL_CTX_set_min_proto_version / max: the JDK's protocols from the lowest to the highest version flags;
     * php_openssl_map_proto_version maps a flag that is no version to TLSv1.2.
     */
    private static String[] protocols(int flags, String[] supported) {
        int min = protoIndex(minProtoFlag(flags));
        int max = protoIndex(maxProtoFlag(flags));
        String[] names = {"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"};
        Set<String> available = new HashSet<>(Arrays.asList(supported));
        List<String> enabled = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            if (available.contains(names[i])) {
                enabled.add(names[i]);
            }
        }
        return enabled.toArray(new String[0]);
    }

    private static int protoIndex(int flag) {
        return switch (flag) {
            case TLSv1_0 -> 0;
            case TLSv1_1 -> 1;
            case TLSv1_3 -> 3;
            default -> 2;
        };
    }

    // ---- CAs: php_openssl_enable_peer_verification ----

    /** Parsed CA files and directories, by path, size and time: OpenSSL reads them for each context. */
    private static final ConcurrentHashMap<String, Set<TrustAnchor>> CA_CACHE = new ConcurrentHashMap<>();
    /** The OpenSSL directories (OPENSSLDIR) of Debian and Ubuntu, Red Hat, and Alpine, SUSE and Arch. */
    private static final String[] OPENSSL_DIRS = {"/usr/lib/ssl", "/etc/pki/tls", "/etc/ssl"};

    /**
     * The CAs to verify with: cafile and capath, else OpenSSL's defaults (SSL_CERT_FILE and SSL_CERT_DIR, else
     * OPENSSLDIR's cert.pem and certs/); where the host has no OpenSSL directory, the JDK's trust store. Null
     * after the warnings when cafile can't be loaded.
     */
    private Set<TrustAnchor> enablePeerVerification() {
        Object cafileOption = option("cafile");
        Object capathOption = option("capath");
        String cafile = cafileOption == null ? null : Network.latin1(cString(Str.bytes(Convert.toStr(cafileOption))));
        String capath = capathOption == null ? null : Network.latin1(cString(Str.bytes(Convert.toStr(capathOption))));
        Set<TrustAnchor> anchors = new HashSet<>();
        if (cafile != null || capath != null) {
            if (cafile != null) {
                TruffleFile file = file(cafile);
                if (file == null || !file.isRegularFile()) {
                    ErrorFunctions.raise(php, ErrorFunctions.E_WARNING, Network.latin1(
                            "failed loading cafile stream: `" + cafile + "'"), SourceLocation.current());
                    return null;
                }
                Set<TrustAnchor> loaded = pemFile(file);
                if (loaded.isEmpty()) {
                    ErrorFunctions.raise(php, ErrorFunctions.E_WARNING, Network.latin1(
                            "no valid certs found cafile stream: `" + cafile + "'"), SourceLocation.current());
                    return null;
                }
                anchors.addAll(loaded);
            }
            if (capath != null) {
                anchors.addAll(hashDirectory(file(capath)));
            }
            return anchors;
        }
        String certFile = System.getenv("SSL_CERT_FILE");
        String certDir = System.getenv("SSL_CERT_DIR");
        String opensslDir = null;
        for (String candidate : OPENSSL_DIRS) {
            TruffleFile dir = file(candidate);
            if (dir != null && dir.isDirectory()) {
                opensslDir = candidate;
                break;
            }
        }
        if (opensslDir == null && certFile == null && certDir == null) {
            return jdkAnchors();
        }
        TruffleFile defaultFile = file(certFile != null ? certFile : opensslDir + "/cert.pem");
        if (defaultFile != null && defaultFile.isRegularFile()) {
            anchors.addAll(pemFile(defaultFile));
        }
        String dirs = certDir != null ? certDir : opensslDir + "/certs";
        for (String dir : dirs.split(":")) {
            anchors.addAll(hashDirectory(file(dir)));
        }
        return anchors;
    }

    private TruffleFile file(String path) {
        try {
            return php.getEnv().getPublicTruffleFile(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The JDK's default trust store's CAs (the Windows host's stand-in for its certificate store). */
    private static Set<TrustAnchor> jdkAnchors() {
        return CA_CACHE.computeIfAbsent("jdk", key -> {
            Set<TrustAnchor> anchors = new HashSet<>();
            try {
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509TrustManager x509) {
                        for (X509Certificate certificate : x509.getAcceptedIssuers()) {
                            anchors.add(new TrustAnchor(certificate, null));
                        }
                    }
                }
            } catch (GeneralSecurityException e) {
                // no trust store: nothing verifies
            }
            return anchors;
        });
    }

    /** A PEM file's certificates (BEGIN CERTIFICATE, TRUSTED CERTIFICATE or X509 CERTIFICATE blocks). */
    private static Set<TrustAnchor> pemFile(TruffleFile file) {
        String key;
        try {
            key = "file:" + file.getCanonicalFile().getPath() + ":" + file.size() + ":" + file.getLastModifiedTime();
        } catch (IOException | SecurityException e) {
            return Set.of();
        }
        return CA_CACHE.computeIfAbsent(key, k -> {
            Set<TrustAnchor> anchors = new HashSet<>();
            try {
                for (X509Certificate certificate : parsePem(file.readAllBytes())) {
                    anchors.add(new TrustAnchor(certificate, null));
                }
            } catch (IOException | SecurityException e) {
                // unreadable: no certificates
            }
            return anchors;
        });
    }

    /** A hashed directory's certificates: the files named as OpenSSL looks them up (subject hash ".N"). */
    private static Set<TrustAnchor> hashDirectory(TruffleFile directory) {
        if (directory == null || !directory.isDirectory()) {
            return Set.of();
        }
        String key;
        try {
            key = "dir:" + directory.getCanonicalFile().getPath() + ":" + directory.getLastModifiedTime();
        } catch (IOException | SecurityException e) {
            return Set.of();
        }
        return CA_CACHE.computeIfAbsent(key, k -> {
            Set<TrustAnchor> anchors = new HashSet<>();
            try (java.nio.file.DirectoryStream<TruffleFile> entries = directory.newDirectoryStream()) {
                for (TruffleFile entry : entries) {
                    if (entry.getName().matches("[0-9a-f]{8}\\.[0-9]+")) {
                        try {
                            for (X509Certificate certificate : parsePem(entry.readAllBytes())) {
                                anchors.add(new TrustAnchor(certificate, null));
                            }
                        } catch (IOException | SecurityException e) {
                            // skipped, as OpenSSL skips a file it can't read
                        }
                    }
                }
            } catch (IOException | SecurityException e) {
                // an unreadable directory has no certificates
            }
            return anchors;
        });
    }

    private static List<X509Certificate> parsePem(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        List<X509Certificate> certificates = new ArrayList<>();
        java.util.regex.Matcher block = java.util.regex.Pattern.compile(
                "-----BEGIN (CERTIFICATE|TRUSTED CERTIFICATE|X509 CERTIFICATE)-----([^-]*)-----END \\1-----")
                .matcher(text);
        while (block.find()) {
            try {
                byte[] der = Base64.getMimeDecoder().decode(block.group(2));
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            } catch (IllegalArgumentException | CertificateException e) {
                // not a certificate: skipped
            }
        }
        return certificates;
    }

    /**
     * The peer's chain as OpenSSL verifies it with SSL_VERIFY_PEER and php-src's verify_callback: a path to a
     * trusted CA, checked for the server purpose (extended key usage) and the dates; a self-signed leaf passes with
     * allow_self_signed (X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT); the CA's depth must not pass verify_depth.
     * Without anchors (verify_peer off) everything passes (SSL_VERIFY_NONE).
     */
    private final class Verifier extends X509ExtendedTrustManager {
        private final Set<TrustAnchor> anchors;
        private final boolean allowSelfSigned;
        private final long verifyDepth;

        Verifier(Set<TrustAnchor> anchors, boolean allowSelfSigned, long verifyDepth) {
            this.anchors = anchors;
            this.allowSelfSigned = allowSelfSigned;
            this.verifyDepth = verifyDepth;
        }

        private void verify(X509Certificate[] chain) throws CertificateException {
            if (anchors == null) {
                return;
            }
            long depth;
            try {
                depth = pathDepth(chain);
            } catch (GeneralSecurityException e) {
                if (allowSelfSigned && selfSigned(chain[0])) {
                    depth = 0;
                } else {
                    verifyFailed = true;
                    throw new CertificateException("certificate verify failed", e);
                }
            }
            if (Long.compareUnsigned(depth, verifyDepth) > 0) {
                verifyFailed = true;
                throw new CertificateException("certificate chain too long");
            }
        }

        /** The trusted CA's depth in the chain OpenSSL builds (the leaf is 0). */
        private long pathDepth(X509Certificate[] chain) throws GeneralSecurityException {
            for (TrustAnchor anchor : anchors) {
                if (chain[0].equals(anchor.getTrustedCert())) {
                    chain[0].checkValidity();
                    return 0;
                }
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(chain[0]);
            try {
                target.setExtendedKeyUsage(Set.of("1.3.6.1.5.5.7.3.1")); // X509_PURPOSE_SSL_SERVER
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, target);
            parameters.setRevocationEnabled(false);
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(Arrays.asList(chain))));
            PKIXCertPathBuilderResult result =
                    (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(parameters);
            return result.getCertPath().getCertificates().size();
        }

        private static boolean selfSigned(X509Certificate certificate) {
            try {
                if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
                    return false;
                }
                certificate.verify(certificate.getPublicKey());
                certificate.checkValidity();
                return true;
            } catch (GeneralSecurityException e) {
                return false;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verify(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ---- php_openssl_enable_crypto ----

    /** STREAM_XPORT_CRYPTO_OP_ENABLE: 1 when done, 0 while a non-blocking handshake goes on, -1 on a failure. */
    @TruffleBoundary
    int enableCrypto(boolean activate) {
        if (activate && !sslActive) {
            boolean wasBlocked = blocked;
            if (isClient) {
                enableClientSni();
            }
            if (!stateSet) {
                try {
                    engine.beginHandshake();
                } catch (SSLException e) {
                    // the JDK refuses before sending (no protocol it may use): SSL_connect's first call fails
                    errorQueue.clear();
                    handleSslError(sslFailure(e), wasBlocked);
                    return -1;
                }
                stateSet = true;
            }
            blocked = false; // php_openssl_set_blocking(sslsock, 0)
            Network.Timeval limit = isClient ? connectTimeout : timeout;
            boolean hasTimeout = limit.sec() > 0 || limit.sec() == 0 && limit.usec() != 0;
            long start = System.nanoTime();
            int n;
            boolean retry;
            do {
                n = handshake();
                long elapsed = (System.nanoTime() - start) / 1000;
                if (hasTimeout && elapsed > limit.micros()) {
                    blocked = wasBlocked;
                    Network.warn(php, ErrorFunctions.E_WARNING, "SSL: Handshake timed out");
                    return -1;
                }
                if (n <= 0) {
                    int err = sslError;
                    retry = handleSslError(n, wasBlocked);
                    if (retry) {
                        poll(err == SSL_ERROR_WANT_READ ? SelectionKey.OP_READ : SelectionKey.OP_WRITE,
                                hasTimeout ? Network.Timeval.ofMicros(limit.micros() - elapsed) : null);
                    }
                } else {
                    retry = false;
                }
            } while (retry);
            blocked = wasBlocked;
            if (n == 1) {
                if (!applyPeerVerificationPolicy()) {
                    shutdown();
                    n = -1;
                } else {
                    sslActive = true;
                }
            } else if (errno == Network.EAGAIN) {
                n = 0;
            } else {
                n = -1;
            }
            return n;
        } else if (!activate && sslActive) {
            shutdown();
            sslActive = false;
        }
        return -1;
    }

    /** php_openssl_enable_client_sni: peer_name, else the URL's host, unless SNI_enabled is off. */
    private void enableClientSni() {
        Object enabled = option("SNI_enabled");
        if (enabled != null && !Convert.toBool(enabled)) {
            return;
        }
        String name = urlName;
        Object peerName = option("peer_name");
        if (peerName != null) {
            name = Network.latin1(cString(Str.bytes(Convert.toStr(peerName))));
        }
        if (name != null) {
            SSLParameters parameters = engine.getSSLParameters();
            try {
                parameters.setServerNames(List.of(new SNIHostName(name.getBytes(StandardCharsets.ISO_8859_1))));
                engine.setSSLParameters(parameters);
            } catch (IllegalArgumentException e) {
                // a name the JDK won't send (OpenSSL sends any): no SNI
            }
        }
    }

    // ---- SSL_connect, SSL_read, SSL_write over the engine ----

    /** SSL_connect: 1 when the handshake is done, else 0 or -1 with sslError set. */
    private int handshake() {
        errno = 0;
        errorQueue.clear();
        try {
            for (;;) {
                if (!flushNet()) {
                    return want(SSL_ERROR_WANT_WRITE);
                }
                switch (engine.getHandshakeStatus()) {
                    case NEED_TASK -> runTasks();
                    case NEED_WRAP -> wrap(ByteBuffer.allocate(0));
                    case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                        int result = unwrap();
                        if (result <= 0) {
                            return result;
                        }
                    }
                    default -> {
                        return 1;
                    }
                }
            }
        } catch (SSLException e) {
            return sslFailure(e);
        } catch (IOException e) {
            return syscall(e);
        }
    }

    /**
     * One unwrap of what has arrived: 1 after progress, else (needing more bytes) a read from the socket; 0 for
     * the peer's close (ZERO_RETURN), -1 with WANT_READ when nothing has arrived.
     */
    private int unwrap() throws IOException {
        for (;;) {
            netIn.flip();
            SSLEngineResult result;
            try {
                result = engine.unwrap(netIn, compactForWrite(appIn));
            } finally {
                netIn.compact();
                appIn.flip();
            }
            switch (result.getStatus()) {
                case OK -> {
                    return 1;
                }
                case CLOSED -> {
                    sslError = SSL_ERROR_ZERO_RETURN;
                    return 0;
                }
                case BUFFER_OVERFLOW -> appIn = grow(appIn, engine.getSession().getApplicationBufferSize(), true);
                case BUFFER_UNDERFLOW -> {
                    if (netIn.remaining() == 0) {
                        netIn = grow(netIn, engine.getSession().getPacketBufferSize(), false);
                    }
                    int n = fillNet();
                    if (n == 0) {
                        return want(SSL_ERROR_WANT_READ);
                    }
                    if (n < 0) {
                        sslError = SSL_ERROR_ZERO_RETURN; // SSL_OP_IGNORE_UNEXPECTED_EOF
                        return 0;
                    }
                }
                default -> throw new IllegalStateException(result.toString());
            }
        }
    }

    /** SSL_read: the count read, else 0 or -1 with sslError set. */
    private int sslRead(byte[] into, int offset, int length) {
        errno = 0;
        errorQueue.clear();
        try {
            while (!appIn.hasRemaining()) {
                if (!flushNet()) {
                    return want(SSL_ERROR_WANT_WRITE);
                }
                switch (engine.getHandshakeStatus()) {
                    case NEED_TASK -> runTasks();
                    case NEED_WRAP -> wrap(ByteBuffer.allocate(0));
                    default -> {
                        int result = unwrap();
                        if (result <= 0) {
                            return result;
                        }
                    }
                }
            }
        } catch (SSLException e) {
            return sslFailure(e);
        } catch (IOException e) {
            return syscall(e);
        }
        int n = Math.min(length, appIn.remaining());
        appIn.get(into, offset, n);
        return n;
    }

    /** SSL_write (with partial writes): the count sent, else -1 with sslError set; a retry continues a record. */
    private int sslWrite(byte[] bytes, int offset, int length) {
        errno = 0;
        errorQueue.clear();
        try {
            if (pendingWrite == 0) {
                SSLEngineResult result = wrap(ByteBuffer.wrap(bytes, offset, length));
                if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    errorQueue.add("error:0A0000CF:SSL routines::protocol is shutdown");
                    sslError = SSL_ERROR_SSL;
                    return -1;
                }
                pendingWrite = result.bytesConsumed();
            }
            if (!flushNet()) {
                return want(SSL_ERROR_WANT_WRITE);
            }
            int n = pendingWrite;
            pendingWrite = 0;
            return n;
        } catch (SSLException e) {
            return sslFailure(e);
        } catch (IOException e) {
            return syscall(e);
        }
    }

    private SSLEngineResult wrap(ByteBuffer source) throws SSLException {
        for (;;) {
            SSLEngineResult result = engine.wrap(source, netOut);
            if (result.getStatus() != SSLEngineResult.Status.BUFFER_OVERFLOW) {
                return result;
            }
            netOut = grow(netOut, engine.getSession().getPacketBufferSize(), false);
        }
    }

    private void runTasks() {
        for (Runnable task; (task = engine.getDelegatedTask()) != null;) {
            task.run();
        }
    }

    /** Sends what has been wrapped: false if the socket took only part of it. */
    private boolean flushNet() throws IOException {
        netOut.flip();
        try {
            while (netOut.hasRemaining()) {
                if (channel.write(netOut) == 0) {
                    return false;
                }
            }
            return true;
        } finally {
            netOut.compact();
        }
    }

    /** Reads what the socket has into netIn: the count, 0 if nothing is there, -1 at the end of the stream. */
    private int fillNet() throws IOException {
        byte[] ahead = takePeeked();
        if (ahead.length > 0) {
            if (netIn.remaining() < ahead.length) {
                netIn = grow(netIn, ahead.length, false);
            }
            netIn.put(ahead);
            return ahead.length;
        }
        return receive(netIn);
    }

    /** A buffer with room for size more bytes; reading: the buffer is in read mode (flipped). */
    private static ByteBuffer grow(ByteBuffer buffer, int size, boolean reading) {
        ByteBuffer larger = ByteBuffer.allocate(buffer.capacity() + size);
        if (!reading) {
            buffer.flip();
        }
        larger.put(buffer);
        if (reading) {
            larger.flip();
        }
        return larger;
    }

    /** A read-mode buffer turned to write mode, its unread bytes first. */
    private static ByteBuffer compactForWrite(ByteBuffer buffer) {
        buffer.compact();
        return buffer;
    }

    private int want(int error) {
        sslError = error;
        return -1;
    }

    private int syscall(IOException e) {
        sslError = SSL_ERROR_SYSCALL;
        errno = Network.errno(e);
        return -1;
    }

    /** A TLS failure: OpenSSL's error queue for it, after sending the alert the engine has (as OpenSSL does). */
    private int sslFailure(SSLException e) {
        sslError = SSL_ERROR_SSL;
        errorQueue.add(opensslError(e));
        try {
            if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                wrap(ByteBuffer.allocate(0));
            }
            flushNet();
        } catch (IOException ignored) {
            // the alert is best effort
        }
        return -1;
    }

    /** OpenSSL alert errors: reason SSL_AD_REASON_OFFSET (1000) + the alert's number, and its text. */
    private static final Map<String, String> ALERTS = Map.ofEntries(
            Map.entry("unexpected_message", "0A0003F2:SSL routines::ssl/tls alert unexpected message"),
            Map.entry("bad_record_mac", "0A0003FC:SSL routines::ssl/tls alert bad record mac"),
            Map.entry("decryption_failed", "0A0003FD:SSL routines::tlsv1 alert decryption failed"),
            Map.entry("decompression_failure", "0A000406:SSL routines::ssl/tls alert decompression failure"),
            Map.entry("handshake_failure", "0A000410:SSL routines::ssl/tls alert handshake failure"),
            Map.entry("no_certificate", "0A000411:SSL routines::ssl/tls alert no certificate"),
            Map.entry("bad_certificate", "0A000412:SSL routines::ssl/tls alert bad certificate"),
            Map.entry("unsupported_certificate", "0A000413:SSL routines::ssl/tls alert unsupported certificate"),
            Map.entry("certificate_revoked", "0A000414:SSL routines::ssl/tls alert certificate revoked"),
            Map.entry("certificate_expired", "0A000415:SSL routines::ssl/tls alert certificate expired"),
            Map.entry("certificate_unknown", "0A000416:SSL routines::ssl/tls alert certificate unknown"),
            Map.entry("illegal_parameter", "0A000417:SSL routines::ssl/tls alert illegal parameter"),
            Map.entry("unknown_ca", "0A000418:SSL routines::tlsv1 alert unknown ca"),
            Map.entry("access_denied", "0A000419:SSL routines::tlsv1 alert access denied"),
            Map.entry("decode_error", "0A00041A:SSL routines::tlsv1 alert decode error"),
            Map.entry("decrypt_error", "0A00041B:SSL routines::tlsv1 alert decrypt error"),
            Map.entry("protocol_version", "0A00042E:SSL routines::tlsv1 alert protocol version"),
            Map.entry("insufficient_security", "0A00042F:SSL routines::tlsv1 alert insufficient security"),
            Map.entry("internal_error", "0A000438:SSL routines::tlsv1 alert internal error"),
            Map.entry("inappropriate_fallback", "0A00043E:SSL routines::tlsv1 alert inappropriate fallback"),
            Map.entry("user_canceled", "0A000442:SSL routines::tlsv1 alert user cancelled"),
            Map.entry("no_renegotiation", "0A00044C:SSL routines::tlsv1 alert no renegotiation"),
            Map.entry("missing_extension", "0A000455:SSL routines::tlsv13 alert missing extension"),
            Map.entry("unsupported_extension", "0A000456:SSL routines::tlsv1 unsupported extension"),
            Map.entry("certificate_unobtainable", "0A000457:SSL routines::tlsv1 certificate unobtainable"),
            Map.entry("unrecognized_name", "0A000458:SSL routines::tlsv1 unrecognized name"),
            Map.entry("bad_certificate_status_response",
                    "0A000459:SSL routines::tlsv1 bad certificate status response"),
            Map.entry("bad_certificate_hash_value", "0A00045A:SSL routines::tlsv1 bad certificate hash value"),
            Map.entry("unknown_psk_identity", "0A00045B:SSL routines::tlsv1 alert unknown psk identity"),
            Map.entry("certificate_required", "0A00045C:SSL routines::tlsv13 alert certificate required"),
            Map.entry("no_application_protocol", "0A000460:SSL routines::tlsv1 alert no application protocol"));

    /**
     * The OpenSSL error (ERR_error_string_n's line) a JSSE failure stands for. A failure OpenSSL has no
     * counterpart for keeps the JDK's message.
     */
    private String opensslError(SSLException e) {
        if (verifyFailed) {
            return "error:0A000086:SSL routines::certificate verify failed";
        }
        String message = String.valueOf(e.getMessage());
        String alert = "Received fatal alert: ";
        if (message.startsWith(alert) && ALERTS.containsKey(message.substring(alert.length()))) {
            return "error:" + ALERTS.get(message.substring(alert.length()));
        }
        if (message.startsWith("No appropriate protocol")) {
            return "error:0A0000BF:SSL routines::no protocols available";
        }
        if (message.startsWith("Unsupported or unrecognized SSL message")) {
            return "error:0A00010B:SSL routines::wrong version number";
        }
        return message;
    }

    /**
     * php_openssl_handle_ssl_error: whether to retry; warns for the failures. errno becomes EAGAIN for WANT_*,
     * as there; a 0-byte SYSCALL (none here: the end of the stream is ZERO_RETURN) would end the stream.
     */
    private boolean handleSslError(int nrBytes, boolean isInit) {
        switch (sslError) {
            case SSL_ERROR_ZERO_RETURN -> {
                return false;
            }
            case SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> {
                errno = Network.EAGAIN;
                return isInit || blocked;
            }
            case SSL_ERROR_SYSCALL -> {
                if (nrBytes == 0) {
                    eof = true;
                } else {
                    Network.warn(php, ErrorFunctions.E_WARNING, "SSL: " + Network.strerror(errno));
                }
                return false;
            }
            default -> {
                Network.warn(php, ErrorFunctions.E_WARNING, "SSL operation failed with code " + sslError
                        + ". " + (errorQueue.isEmpty() ? "" : "OpenSSL Error messages:\n" + String.join("\n",
                        errorQueue)));
                errno = 0;
                return false;
            }
        }
    }

    /** SSL_shutdown: close_notify, sent if the socket takes it now. */
    private void shutdown() {
        try {
            engine.closeOutbound();
            wrap(ByteBuffer.allocate(0));
            flushNet();
        } catch (IOException e) {
            // SSL_shutdown's result isn't checked
        }
    }

    // ---- php_openssl_sockop_io ----

    @Override
    int rawRead(byte[] into, int offset, int length) {
        return sockopIo(true, into, offset, length);
    }

    @Override
    int rawWrite(byte[] bytes, int offset, int length) {
        return sockopIo(false, bytes, offset, length);
    }

    /**
     * php_openssl_sockop_io: with TLS active, SSL_read or SSL_write retried (waiting for the socket) until done,
     * the timeout passes (timed_out) or an error ends it; otherwise the plain socket's operations.
     */
    @TruffleBoundary
    private int sockopIo(boolean read, byte[] buf, int offset, int count) {
        if (!sslActive) {
            return read ? socketRead(buf, offset, count) : socketWrite(buf, offset, count);
        }
        boolean supplemental = hasBufferedData;
        boolean retry = !supplemental;
        boolean beganBlocked = blocked;
        Network.Timeval limit = beganBlocked && !supplemental ? timeout : null;
        if (limit != null || supplemental) {
            blocked = false;
        }
        boolean hasTimeout = !blocked && limit != null && (limit.sec() > 0 || limit.sec() == 0 && limit.usec() != 0);
        long start = System.nanoTime();
        int nrBytes;
        do {
            long elapsed = 0;
            if (hasTimeout) {
                elapsed = (System.nanoTime() - start) / 1000;
                if (elapsed > limit.micros()) {
                    if (beganBlocked) {
                        blocked = true;
                    }
                    timeoutEvent = true;
                    return -1;
                }
            }
            nrBytes = read ? sslRead(buf, offset, count) : sslWrite(buf, offset, count);
            Network.Timeval left = hasTimeout ? Network.Timeval.ofMicros(limit.micros() - elapsed) : null;
            if (nrBytes <= 0) {
                int err = sslError;
                retry = handleSslError(nrBytes, false);
                if (errno == Network.EAGAIN && err == SSL_ERROR_WANT_READ && read) {
                    retry = true;
                }
                if (errno == Network.EAGAIN && err == SSL_ERROR_WANT_WRITE && !read) {
                    retry = true;
                }
                if (read) {
                    eof = !retry && err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE && !appIn.hasRemaining();
                }
                if (!beganBlocked || supplemental) {
                    break;
                }
                if (retry) {
                    boolean wantWrite = read ? err == SSL_ERROR_WANT_WRITE : err != SSL_ERROR_WANT_READ;
                    poll(wantWrite ? SelectionKey.OP_WRITE : SelectionKey.OP_READ, left);
                }
            } else {
                break;
            }
        } while (retry);
        if (beganBlocked) {
            blocked = true;
        }
        return Math.max(nrBytes, 0);
    }

    /**
     * PHP_STREAM_OPTION_CHECK_LIVENESS with value 0: a plain socket peeks; with TLS, if the socket is readable,
     * SSL_peek decides (data or a retryable state is alive, the peer's close or an error isn't).
     */
    @Override
    @TruffleBoundary
    boolean alive() {
        if (channel == null) {
            return false;
        }
        if (!sslActive) {
            return peek() != 0;
        }
        if (appIn.hasRemaining() || poll(SelectionKey.OP_READ, new Network.Timeval(0, 0)) > 0) {
            boolean wasBlocked = blocked;
            blocked = false;
            int n = sslPeek();
            blocked = wasBlocked;
            if (n > 0) {
                return true;
            }
            return switch (sslError) {
                case SSL_ERROR_SYSCALL -> errno == Network.EAGAIN;
                case SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> true;
                default -> false;
            };
        }
        return true;
    }

    /** SSL_peek: what SSL_read would give, left for it. */
    private int sslPeek() {
        if (appIn.hasRemaining()) {
            return appIn.remaining();
        }
        byte[] none = new byte[0];
        int n = sslRead(none, 0, 0);
        return n == 0 && appIn.hasRemaining() ? appIn.remaining() : n;
    }

    // ---- php_openssl_apply_peer_verification_policy ----

    private boolean applyPeerVerificationPolicy() {
        Object verifyPeerOption = option("verify_peer");
        Object verifyNameOption = option("verify_peer_name");
        boolean mustVerifyPeer = verifyPeerOption != null ? Convert.toBool(verifyPeerOption) : isClient;
        boolean mustVerifyPeerName = verifyNameOption != null ? Convert.toBool(verifyNameOption) : isClient;
        Object fingerprint = option("peer_fingerprint");
        X509Certificate peer = null;
        try {
            Certificate[] chain = engine.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate x509) {
                peer = x509;
            }
        } catch (SSLPeerUnverifiedException e) {
            // no certificate
        }
        if ((mustVerifyPeer || mustVerifyPeerName || fingerprint != null) && peer == null) {
            Network.warn(php, ErrorFunctions.E_WARNING, "Could not get peer certificate");
            return false;
        }
        // with verify_peer the handshake has already failed unless the chain verified (or was allowed self-signed)
        if (fingerprint != null) {
            if (fingerprint instanceof com.oracle.truffle.api.strings.TruffleString
                    || fingerprint instanceof PhpArray) {
                if (!fingerprintMatch(peer, fingerprint)) {
                    Network.warn(php, ErrorFunctions.E_WARNING, "peer_fingerprint match failure");
                    return false;
                }
            } else {
                Network.warn(php, ErrorFunctions.E_WARNING,
                        "Expected peer fingerprint must be a string or an array");
                return false;
            }
        }
        if (mustVerifyPeerName) {
            String peerName = null;
            Object peerNameOption = option("peer_name");
            if (peerNameOption != null) {
                peerName = Network.latin1(cString(Str.bytes(Convert.toStr(peerNameOption))));
            }
            if (peerName == null && isClient) {
                peerName = urlName;
            }
            if (peerName == null) {
                return false;
            }
            boolean[] hasServiceId = new boolean[1];
            if (matchesSanList(peer, peerName, hasServiceId)) {
                return true;
            } else if (hasServiceId[0]) {
                Network.warn(php, ErrorFunctions.E_WARNING,
                        "Peer certificate subjectAltName did not match expected name `" + peerName + "'");
                return false;
            }
            return matchesCommonName(peer, peerName);
        }
        return true;
    }

    /** php_openssl_x509_fingerprint_match: md5 (32 digits) or sha1 (40), or [algorithm => fingerprint, ...]. */
    private boolean fingerprintMatch(X509Certificate peer, Object value) {
        if (value instanceof com.oracle.truffle.api.strings.TruffleString string) {
            String expected = Str.java(string);
            String method = switch (Str.bytes(string).length) {
                case 32 -> "md5";
                case 40 -> "sha1";
                default -> null;
            };
            return method != null && expected.equalsIgnoreCase(String.valueOf(fingerprint(peer, method)));
        }
        PhpArray array = (PhpArray) value;
        if (array.size() == 0) {
            Network.warn(php, ErrorFunctions.E_WARNING,
                    "Invalid peer_fingerprint array; [algo => fingerprint] form required");
            return false;
        }
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            Object key = array.keyAt(p);
            Object expected = pl.codeloom.php.runtime.PhpRef.deref(array.rawAt(p));
            if (key instanceof Long || !(expected instanceof com.oracle.truffle.api.strings.TruffleString string)) {
                Network.warn(php, ErrorFunctions.E_WARNING,
                        "Invalid peer_fingerprint array; [algo => fingerprint] form required");
                return false;
            }
            String actual = fingerprint(peer, Str.java((com.oracle.truffle.api.strings.TruffleString) key));
            if (actual == null || !Str.java(string).equalsIgnoreCase(actual)) {
                return false;
            }
        }
        return true;
    }

    /** OpenSSL's digest names (EVP_get_digestbyname) for the JDK's algorithms. */
    private static final Map<String, String> DIGESTS = Map.ofEntries(Map.entry("md5", "MD5"),
            Map.entry("sha1", "SHA-1"), Map.entry("sha-1", "SHA-1"), Map.entry("sha224", "SHA-224"),
            Map.entry("sha-224", "SHA-224"), Map.entry("sha256", "SHA-256"), Map.entry("sha-256", "SHA-256"),
            Map.entry("sha384", "SHA-384"), Map.entry("sha-384", "SHA-384"), Map.entry("sha512", "SHA-512"),
            Map.entry("sha-512", "SHA-512"));

    /** php_openssl_x509_fingerprint: the certificate's digest in lowercase hex, or null (warned) for an unknown one. */
    private String fingerprint(X509Certificate peer, String method) {
        String algorithm = DIGESTS.get(method.toLowerCase(java.util.Locale.ROOT));
        if (algorithm == null) {
            Network.warn(php, ErrorFunctions.E_WARNING, "Unknown digest algorithm");
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(peer.getEncoded()));
        } catch (GeneralSecurityException e) {
            Network.warn(php, ErrorFunctions.E_WARNING, "Unknown digest algorithm");
            return null;
        }
    }

    /** php_openssl_matches_wildcard_name: equal, or a wildcard in the left-most label only. */
    static boolean matchesWildcardName(String subject, String certName) {
        if (subject.equalsIgnoreCase(certName)) {
            return true;
        }
        int wildcard = certName.indexOf('*');
        if (wildcard < 0 || certName.substring(0, wildcard).indexOf('.') >= 0) {
            return false;
        }
        if (wildcard > 0 && !subject.regionMatches(true, 0, certName, 0, wildcard)) {
            return false;
        }
        int suffixLength = certName.length() - wildcard - 1;
        int subjectLength = subject.length();
        if (suffixLength + wildcard <= subjectLength) {
            return certName.substring(wildcard + 1).equalsIgnoreCase(subject.substring(subjectLength - suffixLength))
                    && subject.substring(wildcard, subjectLength - suffixLength).indexOf('.') < 0;
        }
        return false;
    }

    /** php_openssl_matches_san_list: DNS names, IP addresses; DNS, URI and SRV names make a service identity. */
    private static boolean matchesSanList(X509Certificate peer, String subject, boolean[] hasServiceId) {
        Collection<List<?>> names;
        try {
            names = peer.getSubjectAlternativeNames();
        } catch (java.security.cert.CertificateParsingException e) {
            names = null;
        }
        if (names == null) {
            return false;
        }
        String subjectIpv6 = expandIpv6(subject);
        for (List<?> name : names) {
            int type = (Integer) name.get(0);
            if (type == 2) {
                hasServiceId[0] = true;
                String dns = (String) name.get(1);
                if (dns.indexOf('\0') >= 0) {
                    continue; // null-byte poisoning
                }
                if (dns.endsWith(".")) {
                    dns = dns.substring(0, dns.length() - 1);
                }
                if (matchesWildcardName(subject, dns)) {
                    return true;
                }
            } else if (type == 7) {
                byte[] address = ipBytes((String) name.get(1));
                if (address != null && address.length == 4) {
                    String ip = (address[0] & 0xFF) + "." + (address[1] & 0xFF) + "." + (address[2] & 0xFF) + "."
                            + (address[3] & 0xFF);
                    if (subject.equalsIgnoreCase(ip)) {
                        return true;
                    }
                } else if (address != null && address.length == 16 && subjectIpv6 != null) {
                    if (subjectIpv6.equalsIgnoreCase(expand(address))) {
                        return true;
                    }
                }
            } else if (type == 6) {
                hasServiceId[0] = true;
            } else if (type == 0 && name.get(1) instanceof byte[] encoded && containsSrvOid(encoded)) {
                hasServiceId[0] = true;
            }
        }
        return false;
    }

    /** id-on-dnsSRV (1.3.6.1.5.5.7.8.7) as the otherName's type. */
    private static boolean containsSrvOid(byte[] encoded) {
        byte[] oid = {0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x08, 0x07};
        for (int i = 0; i + oid.length <= encoded.length && i < 8; i++) {
            if (Arrays.equals(encoded, i, i + oid.length, oid, 0, oid.length)) {
                return true;
            }
        }
        return false;
    }

    /** An IP SAN's bytes, as the JDK writes the address. */
    private static byte[] ipBytes(String text) {
        try {
            InetAddress address = InetAddress.getByName(text.indexOf(':') >= 0 ? text : text);
            return text.indexOf(':') >= 0 && address instanceof Inet4Address v4 ? mapped(v4) : address.getAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private static byte[] mapped(Inet4Address v4) {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        System.arraycopy(v4.getAddress(), 0, bytes, 12, 4);
        return bytes;
    }

    /** inet_pton(AF_INET6) of the name, EXPAND_IPV6_ADDRESS'd; null if it isn't an IPv6 address. */
    private static String expandIpv6(String subject) {
        if (subject.indexOf(':') < 0 || !subject.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        byte[] bytes = ipBytes(subject);
        return bytes == null || bytes.length != 16 ? null : expand(bytes);
    }

    /** EXPAND_IPV6_ADDRESS: eight groups in uppercase hexadecimal without leading zeros. */
    private static String expand(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(Integer.toHexString((bytes[i] & 0xFF) << 8 | bytes[i + 1] & 0xFF).toUpperCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }

    /** php_openssl_matches_common_name: the subject's first CN (its raw bytes, at most 1023), with its warnings. */
    private boolean matchesCommonName(X509Certificate peer, String subject) {
        byte[] cn = firstCommonName(peer.getSubjectX500Principal().getEncoded());
        if (cn == null) {
            Network.warn(php, ErrorFunctions.E_WARNING, "Unable to locate peer certificate CN");
            return false;
        }
        int length = Math.min(cn.length, 1023);
        int cLength = 0;
        while (cLength < length && cn[cLength] != 0) {
            cLength++;
        }
        String name = Network.latin1(Arrays.copyOf(cn, length));
        if (cLength != length) {
            Network.warn(php, ErrorFunctions.E_WARNING, "Peer certificate CN=`" + name + "' is malformed");
            return false;
        }
        if (matchesWildcardName(subject, name)) {
            return true;
        }
        Network.warn(php, ErrorFunctions.E_WARNING, "Peer certificate CN=`" + name
                + "' did not match expected CN=`" + subject + "'");
        return false;
    }

    /** The first commonName (2.5.4.3) value's bytes in a DER Name, in encoding order; null if there is none. */
    static byte[] firstCommonName(byte[] name) {
        int[] position = {0};
        int nameEnd = enter(name, position, 0x30);
        while (position[0] < nameEnd) {
            int setEnd = enter(name, position, 0x31);
            while (position[0] < setEnd) {
                int attributeEnd = enter(name, position, 0x30);
                int oidEnd = enter(name, position, 0x06);
                boolean commonName = oidEnd - position[0] == 3 && name[position[0]] == 0x55 && name[position[0] + 1] == 0x04
                        && name[position[0] + 2] == 0x03;
                position[0] = oidEnd;
                position[0]++; // the value's tag
                int valueEnd = length(name, position);
                if (commonName) {
                    return Arrays.copyOfRange(name, position[0], valueEnd);
                }
                position[0] = attributeEnd;
            }
            position[0] = setEnd;
        }
        return null;
    }

    /** Past a DER tag (which must be expected) and length: the content's end. */
    private static int enter(byte[] der, int[] position, int expected) {
        if ((der[position[0]] & 0xFF) != expected) {
            throw new IllegalArgumentException("unexpected DER tag");
        }
        position[0]++;
        return length(der, position);
    }

    private static int length(byte[] der, int[] position) {
        int first = der[position[0]++] & 0xFF;
        int length = first;
        if (first >= 0x80) {
            length = 0;
            for (int i = 0; i < (first & 0x7F); i++) {
                length = length << 8 | der[position[0]++] & 0xFF;
            }
        }
        return position[0] + length;
    }

    // ---- PHP_STREAM_OPTION_META_DATA_API ----

    /** The "crypto" entry while TLS is active (protocol, OpenSSL's cipher name, bits and version), then the rest. */
    @Override
    boolean populateMeta(PhpArray meta) {
        if (sslActive) {
            PhpArray crypto = new PhpArray();
            String protocol = engine.getSession().getProtocol();
            crypto.put(FileFunctions.key("protocol"), Str.of(switch (protocol) {
                case "TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1" -> protocol;
                default -> "UNKNOWN";
            }));
            String[] cipher = Ciphers.describe(engine.getSession().getCipherSuite());
            crypto.put(FileFunctions.key("cipher_name"), Str.of(cipher[0]));
            crypto.put(FileFunctions.key("cipher_bits"), Long.parseLong(cipher[2]));
            crypto.put(FileFunctions.key("cipher_version"), Str.of(cipher[1]));
            String alpn = engine.getApplicationProtocol();
            if (alpn != null && !alpn.isEmpty()) {
                crypto.put(FileFunctions.key("alpn_protocol"), Network.latin1(alpn));
            }
            meta.put(FileFunctions.key("crypto"), crypto);
        }
        return super.populateMeta(meta);
    }

    @Override
    void rawClose() throws IOException {
        if (sslActive) {
            shutdown();
            sslActive = false;
        }
        super.rawClose();
    }

    /** A C string: the bytes up to the first NUL. */
    static byte[] cString(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return Arrays.copyOf(bytes, i);
            }
        }
        return bytes;
    }

    /**
     * The cipher suites: OPENSSL_DEFAULT_STREAM_CIPHERS as the reference's OpenSSL expands it (`openssl ciphers
     * -stdname -v`, OpenSSL 3.5), after OpenSSL's TLS 1.3 suites, keeping those the JDK implements, in that order;
     * with each suite's OpenSSL name, SSL_CIPHER_get_version and SSL_CIPHER_get_bits for stream_get_meta_data().
     */
    static final class Ciphers {
        private Ciphers() {
        }

        private static final String[][] SUITES = {
            {"TLS_AES_256_GCM_SHA384", "TLS_AES_256_GCM_SHA384", "TLSv1.3", "256"},
            {"TLS_CHACHA20_POLY1305_SHA256", "TLS_CHACHA20_POLY1305_SHA256", "TLSv1.3", "256"},
            {"TLS_AES_128_GCM_SHA256", "TLS_AES_128_GCM_SHA256", "TLSv1.3", "128"},
            {"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "ECDHE-RSA-AES128-GCM-SHA256", "TLSv1.2", "128"},
            {"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "ECDHE-ECDSA-AES128-GCM-SHA256", "TLSv1.2", "128"},
            {"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "ECDHE-RSA-AES256-GCM-SHA384", "TLSv1.2", "256"},
            {"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "ECDHE-ECDSA-AES256-GCM-SHA384", "TLSv1.2", "256"},
            {"TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", "DHE-RSA-AES128-GCM-SHA256", "TLSv1.2", "128"},
            {"TLS_DHE_DSS_WITH_AES_128_GCM_SHA256", "DHE-DSS-AES128-GCM-SHA256", "TLSv1.2", "128"},
            {"TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", "DHE-DSS-AES256-GCM-SHA384", "TLSv1.2", "256"},
            {"TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "DHE-RSA-AES256-GCM-SHA384", "TLSv1.2", "256"},
            {"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "ECDHE-RSA-AES128-SHA256", "TLSv1.2", "128"},
            {"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "ECDHE-ECDSA-AES128-SHA256", "TLSv1.2", "128"},
            {"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "ECDHE-RSA-AES128-SHA", "TLSv1", "128"},
            {"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "ECDHE-ECDSA-AES128-SHA", "TLSv1", "128"},
            {"TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "ECDHE-RSA-AES256-SHA384", "TLSv1.2", "256"},
            {"TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", "ECDHE-ECDSA-AES256-SHA384", "TLSv1.2", "256"},
            {"TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", "ECDHE-RSA-AES256-SHA", "TLSv1", "256"},
            {"TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", "ECDHE-ECDSA-AES256-SHA", "TLSv1", "256"},
            {"TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", "DHE-RSA-AES128-SHA256", "TLSv1.2", "128"},
            {"TLS_DHE_RSA_WITH_AES_128_CBC_SHA", "DHE-RSA-AES128-SHA", "SSLv3", "128"},
            {"TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", "DHE-DSS-AES128-SHA256", "TLSv1.2", "128"},
            {"TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", "DHE-RSA-AES256-SHA256", "TLSv1.2", "256"},
            {"TLS_DHE_DSS_WITH_AES_256_CBC_SHA", "DHE-DSS-AES256-SHA", "SSLv3", "256"},
            {"TLS_DHE_RSA_WITH_AES_256_CBC_SHA", "DHE-RSA-AES256-SHA", "SSLv3", "256"},
            {"TLS_RSA_WITH_AES_128_GCM_SHA256", "AES128-GCM-SHA256", "TLSv1.2", "128"},
            {"TLS_RSA_WITH_AES_256_GCM_SHA384", "AES256-GCM-SHA384", "TLSv1.2", "256"},
            {"TLS_DHE_DSS_WITH_AES_128_CBC_SHA", "DHE-DSS-AES128-SHA", "SSLv3", "128"},
            {"TLS_RSA_WITH_AES_128_CBC_SHA256", "AES128-SHA256", "TLSv1.2", "128"},
            {"TLS_RSA_WITH_AES_128_CBC_SHA", "AES128-SHA", "SSLv3", "128"},
            {"TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", "DHE-DSS-AES256-SHA256", "TLSv1.2", "256"},
            {"TLS_RSA_WITH_AES_256_CBC_SHA256", "AES256-SHA256", "TLSv1.2", "256"},
            {"TLS_RSA_WITH_AES_256_CBC_SHA", "AES256-SHA", "SSLv3", "256"},
            {"TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "ECDHE-ECDSA-CHACHA20-POLY1305", "TLSv1.2", "256"},
            {"TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "ECDHE-RSA-CHACHA20-POLY1305", "TLSv1.2", "256"},
            {"TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "DHE-RSA-CHACHA20-POLY1305", "TLSv1.2", "256"},
        };
        private static final Map<String, String[]> BY_NAME = new HashMap<>();

        static {
            for (String[] suite : SUITES) {
                BY_NAME.put(suite[0], new String[] {suite[1], suite[2], suite[3]});
            }
        }

        /** The suites to offer, of those supported, with the JDK's renegotiation indication last. */
        static String[] offered(String[] supported) {
            Set<String> available = new HashSet<>(Arrays.asList(supported));
            List<String> offered = new ArrayList<>();
            for (String[] suite : SUITES) {
                if (available.contains(suite[0])) {
                    offered.add(suite[0]);
                }
            }
            if (available.contains("TLS_EMPTY_RENEGOTIATION_INFO_SCSV")) {
                offered.add("TLS_EMPTY_RENEGOTIATION_INFO_SCSV");
            }
            return offered.toArray(new String[0]);
        }

        /** {OpenSSL name, version, bits} of a negotiated suite. */
        static String[] describe(String suite) {
            String[] described = BY_NAME.get(suite);
            return described != null ? described : new String[] {suite, "unknown", "0"};
        }
    }
}
