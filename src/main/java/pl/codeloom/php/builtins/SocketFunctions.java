package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpResource;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Client sockets: fsockopen (ext/standard/fsock.c), stream_socket_client and the socket stream functions
 * (ext/standard/streamsfuncs.c), over the transports of main/streams/transports.c: tcp:// (ext/openssl's, so it
 * can turn to TLS), ssl://, tls://, tlsv1.0:// to tlsv1.3:// and unix://. udp:// and udg:// (datagram sockets)
 * aren't registered, nor is pfsockopen: a persistent socket outlives its request in php-src, which contexts here
 * don't share; STREAM_CLIENT_PERSISTENT makes an ordinary socket.
 */
final class SocketFunctions {
    private SocketFunctions() {
    }

    /** The registered transports, in php-src's order (php_stream_xport_get_hash). */
    private static final List<String> TRANSPORTS = List.of("tcp", "unix", "ssl", "tls", "tlsv1.0", "tlsv1.1",
            "tlsv1.2", "tlsv1.3");
    /** PHP_TIMEOUT_ULL_MAX / 1000000.0. */
    private static final double TIMEOUT_MAX = 18446744073709551615.0 / 1000000.0;
    private static final long CLIENT_PERSISTENT = 1;
    private static final long CLIENT_ASYNC_CONNECT = 2;
    private static final long CLIENT_CONNECT = 4;

    static void constants(Map<String, Object> constants) {
        Object[][] table = {
                {"STREAM_CLIENT_PERSISTENT", CLIENT_PERSISTENT}, {"STREAM_CLIENT_ASYNC_CONNECT", CLIENT_ASYNC_CONNECT},
                {"STREAM_CLIENT_CONNECT", CLIENT_CONNECT},
                {"STREAM_CRYPTO_METHOD_ANY_CLIENT", 127L}, {"STREAM_CRYPTO_METHOD_SSLv2_CLIENT", 3L},
                {"STREAM_CRYPTO_METHOD_SSLv3_CLIENT", 5L}, {"STREAM_CRYPTO_METHOD_SSLv23_CLIENT", 57L},
                {"STREAM_CRYPTO_METHOD_TLS_CLIENT", 121L}, {"STREAM_CRYPTO_METHOD_TLSv1_0_CLIENT", 9L},
                {"STREAM_CRYPTO_METHOD_TLSv1_1_CLIENT", 17L}, {"STREAM_CRYPTO_METHOD_TLSv1_2_CLIENT", 33L},
                {"STREAM_CRYPTO_METHOD_TLSv1_3_CLIENT", 65L}, {"STREAM_CRYPTO_METHOD_ANY_SERVER", 126L},
                {"STREAM_CRYPTO_METHOD_SSLv2_SERVER", 2L}, {"STREAM_CRYPTO_METHOD_SSLv3_SERVER", 4L},
                {"STREAM_CRYPTO_METHOD_SSLv23_SERVER", 120L}, {"STREAM_CRYPTO_METHOD_TLS_SERVER", 120L},
                {"STREAM_CRYPTO_METHOD_TLSv1_0_SERVER", 8L}, {"STREAM_CRYPTO_METHOD_TLSv1_1_SERVER", 16L},
                {"STREAM_CRYPTO_METHOD_TLSv1_2_SERVER", 32L}, {"STREAM_CRYPTO_METHOD_TLSv1_3_SERVER", 64L},
                {"STREAM_CRYPTO_PROTO_SSLv3", 4L}, {"STREAM_CRYPTO_PROTO_TLSv1_0", 8L},
                {"STREAM_CRYPTO_PROTO_TLSv1_1", 16L}, {"STREAM_CRYPTO_PROTO_TLSv1_2", 32L},
                {"STREAM_CRYPTO_PROTO_TLSv1_3", 64L},
                {"STREAM_SHUT_RD", 0L}, {"STREAM_SHUT_WR", 1L}, {"STREAM_SHUT_RDWR", 2L},
                {"STREAM_PF_INET", 2L}, {"STREAM_PF_INET6", 10L}, {"STREAM_PF_UNIX", 1L},
                {"STREAM_IPPROTO_IP", 0L}, {"STREAM_IPPROTO_TCP", 6L}, {"STREAM_IPPROTO_UDP", 17L},
                {"STREAM_IPPROTO_ICMP", 1L}, {"STREAM_IPPROTO_RAW", 255L},
                {"STREAM_SOCK_STREAM", 1L}, {"STREAM_SOCK_DGRAM", 2L}, {"STREAM_SOCK_RAW", 3L},
                {"STREAM_SOCK_SEQPACKET", 5L}, {"STREAM_SOCK_RDM", 4L},
                {"STREAM_PEEK", 2L}, {"STREAM_OOB", 1L}, {"STREAM_SERVER_BIND", 4L}, {"STREAM_SERVER_LISTEN", 8L},
        };
        for (Object[] entry : table) {
            constants.put((String) entry[0], entry[1]);
        }
    }

    static void register(Registry r) {
        r.fn("fsockopen", "0011", (context, a) -> fsockopen(context, a));
        r.fn("stream_socket_client", "011", (context, a) -> streamSocketClient(context, a));
        r.fn("stream_set_timeout", a -> {
            FileFunctions.Stream stream = FileFunctions.stream(a, "stream_set_timeout");
            long seconds = Args.lng(a, 1, 0);
            long microseconds = 0;
            if (Args.count(a) >= 3) {
                long given = Args.lng(a, 2, 0);
                microseconds = given % 1_000_000;
                seconds += given / 1_000_000;
            }
            return stream.setReadTimeout(seconds, microseconds);
        });
        r.fn("stream_set_blocking", a -> {
            FileFunctions.Stream stream = FileFunctions.stream(a, "stream_set_blocking");
            return stream.setBlocking(Args.bool(a, 1, false)) != -1;
        });
        r.fn("stream_socket_get_name", a -> {
            FileFunctions.Stream stream = FileFunctions.stream(a, "stream_socket_get_name");
            String name = stream instanceof SocketStream socket ? socket.name(Args.bool(a, 1, false)) : null;
            return name == null || name.isEmpty() || name.charAt(0) == 0 ? (Object) Boolean.FALSE
                    : Str.of(name.getBytes(StandardCharsets.UTF_8));
        });
        r.fn("stream_socket_enable_crypto", (context, a) -> enableCrypto(context, a));
        r.fn("stream_get_transports", a -> {
            PhpArray transports = new PhpArray();
            for (String transport : TRANSPORTS) {
                transports.append(Str.of(transport));
            }
            return transports;
        });
    }

    /** fsockopen(hostname, port = -1, &errno = null, &errstr = null, timeout = null): php_fsockopen_stream. */
    @TruffleBoundary
    private static Object fsockopen(PhpContext context, Object[] a) {
        byte[] host = Str.bytes(Args.str(a, 0));
        long port = Args.lng(a, 1, -1);
        Object timeoutArgument = PhpRef.deref(Args.get(a, 4, PhpNull.NULL));
        double timeout = timeoutArgument == PhpNull.NULL ? context.ini().defaultSocketTimeout()
                : Convert.toDouble(timeoutArgument);
        String hostname = Network.latin1(host) + (port > 0 ? ":" + port : "");
        if (timeout != -1.0 && !(timeout >= 0.0 && timeout <= TIMEOUT_MAX)) {
            throw new PhpError("ValueError", "fsockopen(): Argument #5 ($timeout) must be -1 or between 0 and "
                    + (long) TIMEOUT_MAX);
        }
        long conv = (long) (timeout * 1000000.0);
        Network.Timeval tv = new Network.Timeval(conv / 1000000, conv % 1000000);
        String[] error = new String[1];
        int[] code = new int[1];
        SocketStream stream = xportCreate(context, hostname.getBytes(StandardCharsets.ISO_8859_1), tv, null, true,
                false, error, code);
        if (stream == null) {
            Network.warn(context, ErrorFunctions.E_WARNING, "Unable to connect to "
                    + Network.latin1(SslSocketStream.cString(host)) + ":" + port + " ("
                    + (error[0] == null ? "Unknown error" : error[0]) + ")");
            if (Args.has(a, 2)) {
                Args.ref(a, 2).set((long) code[0]);
            }
            if (error[0] != null && Args.has(a, 3)) {
                Args.ref(a, 3).set(Network.latin1(error[0]));
            }
            return Boolean.FALSE;
        }
        if (Args.has(a, 2)) {
            Args.ref(a, 2).set(0L);
        }
        if (Args.has(a, 3)) {
            Args.ref(a, 3).set(Str.EMPTY);
        }
        return FileFunctions.resource(context, stream);
    }

    /** stream_socket_client(address, &errno, &errstr, timeout = null, flags = CONNECT, context = null). */
    @TruffleBoundary
    private static Object streamSocketClient(PhpContext context, Object[] a) {
        byte[] host = Str.bytes(Args.str(a, 0));
        Object timeoutArgument = PhpRef.deref(Args.get(a, 3, PhpNull.NULL));
        double timeout;
        if (timeoutArgument == PhpNull.NULL) {
            timeout = context.ini().defaultSocketTimeout();
        } else {
            timeout = Convert.toDouble(timeoutArgument);
            if (!Double.isFinite(timeout)) {
                throw new PhpError("ValueError", "stream_socket_client(): Argument #4 ($timeout) must be a finite "
                        + "value");
            }
        }
        long flags = Args.lng(a, 4, CLIENT_CONNECT);
        StreamContext streamContext = StreamContext.fromArgument(context, a, 5, "stream_socket_client", "context",
                (flags & 16) != 0);
        Network.Timeval tv = null;
        if (!(timeout < 0.0 || timeout >= TIMEOUT_MAX)) {
            long conv = (long) (timeout * 1000000.0);
            tv = new Network.Timeval(conv / 1000000, conv % 1000000);
        }
        if (Args.has(a, 1)) {
            Args.ref(a, 1).set(0L);
        }
        if (Args.has(a, 2)) {
            Args.ref(a, 2).set(Str.EMPTY);
        }
        String[] error = new String[1];
        int[] code = new int[1];
        SocketStream stream = xportCreate(context, host, tv, streamContext,
                (flags & CLIENT_CONNECT) != 0, (flags & CLIENT_ASYNC_CONNECT) != 0, error, code);
        if (stream == null) {
            Network.warn(context, ErrorFunctions.E_WARNING, "Unable to connect to "
                    + Network.latin1(Str.bytes(StringFunctions.addslashes(Args.str(a, 0)))) + " ("
                    + (error[0] == null ? "Unknown error" : error[0]) + ")");
            if (Args.has(a, 1)) {
                Args.ref(a, 1).set((long) code[0]);
            }
            if (error[0] != null && Args.has(a, 2)) {
                Args.ref(a, 2).set(Network.latin1(error[0]));
            }
            return Boolean.FALSE;
        }
        return FileFunctions.resource(context, stream);
    }

    /**
     * _php_stream_xport_create for a client: the transport ("tcp" when the name has none) makes the stream, which
     * then connects (STREAM_XPORT_CONNECT, or _ASYNC). Null on a failure, with error[0] (null for "Unknown
     * error") and code[0] set as php-src's error_string and error_code.
     */
    static SocketStream xportCreate(PhpContext context, byte[] name, Network.Timeval timeout,
                                    StreamContext streamContext, boolean connect, boolean asynchronous, String[] error,
                                    int[] code) {
        Network.Timeval effective = timeout != null ? timeout
                : new Network.Timeval(context.ini().defaultSocketTimeout(), 0);
        int n = 0;
        while (n < name.length && (Character.isLetterOrDigit(name[n] & 0x7F) && name[n] >= 0 || name[n] == '+'
                || name[n] == '-' || name[n] == '.')) {
            n++;
        }
        String protocol = "tcp";
        byte[] resource = name;
        if (n > 1 && n + 2 < name.length && name[n] == ':' && name[n + 1] == '/' && name[n + 2] == '/') {
            protocol = Network.latin1(Arrays.copyOf(name, n));
            resource = Arrays.copyOfRange(name, n + 3, name.length);
        }
        String uri = Network.latin1(name);
        SocketStream stream;
        switch (protocol) {
            case "unix" -> stream = new SocketStream(context, "unix_socket", uri);
            case "tcp", "ssl", "tls", "tlsv1.0", "tlsv1.1", "tlsv1.2", "tlsv1.3" -> {
                SslSocketStream ssl = new SslSocketStream(context, uri, effective, SslSocketStream.urlName(resource));
                ssl.enableOnConnect = !protocol.equals("tcp");
                ssl.method = switch (protocol) {
                    case "tlsv1.0" -> SslSocketStream.TLSv1_0 | SslSocketStream.IS_CLIENT;
                    case "tlsv1.1" -> SslSocketStream.TLSv1_1 | SslSocketStream.IS_CLIENT;
                    case "tlsv1.2" -> SslSocketStream.TLSv1_2 | SslSocketStream.IS_CLIENT;
                    case "tlsv1.3" -> SslSocketStream.TLSv1_3 | SslSocketStream.IS_CLIENT;
                    case "tcp" -> 0;
                    default -> SslSocketStream.cryptoMethod(streamContext, SslSocketStream.TLS_ANY_CLIENT);
                };
                stream = ssl;
            }
            default -> {
                String shown = protocol.length() > 31 ? protocol.substring(0, 31) : protocol;
                error[0] = "Unable to find the socket transport \"" + shown + "\" - did you forget to enable it when "
                        + "you configured PHP?";
                return null;
            }
        }
        stream.context = streamContext;
        if (connect || asynchronous) {
            if (connect(context, stream, resource, asynchronous, effective, error, code) == -1) {
                FileFunctions.closeStream(stream);
                return null;
            }
        }
        return stream;
    }

    /**
     * STREAM_XPORT_OP_CONNECT: php_tcp_sockop_connect (a Unix socket, or "host:port" / "[v6]:port" with the
     * socket context's bindto and tcp_nodelay), then for a TLS transport the crypto setup and handshake. 0, 1 for
     * an asynchronous connect in progress, or -1.
     */
    private static int connect(PhpContext context, SocketStream stream, byte[] name, boolean asynchronous,
                               Network.Timeval timeout, String[] error, int[] code) {
        int result;
        if (stream.streamType().equals("unix_socket")) {
            result = connectUnix(context, stream, name, asynchronous, timeout, error, code);
        } else {
            int[] port = new int[1];
            String host = parseIpAddress(name, port, error);
            if (host == null) {
                return -1;
            }
            String bindTo = null;
            int[] bindPort = new int[1];
            Object bindOption = stream.context == null ? null : stream.context.option("socket", "bindto");
            if (bindOption != null) {
                if (!(bindOption instanceof com.oracle.truffle.api.strings.TruffleString bindText)) {
                    error[0] = "local_addr context option is not a string.";
                    return -1;
                }
                bindTo = parseIpAddress(Str.bytes(bindText), bindPort, error);
            }
            Object nodelay = stream.context == null ? null : stream.context.option("socket", "tcp_nodelay");
            Network.Connection connection = Network.connectToHost(context, host, port[0] & 0xFFFF, asynchronous,
                    timeout, bindTo, bindPort[0] & 0xFFFF, nodelay != null && Convert.toBool(nodelay));
            error[0] = connection.errorString;
            code[0] = connection.errorCode;
            result = attach(stream, connection.channel, error, code);
            if (result >= 0 && asynchronous && code[0] == Network.EINPROGRESS) {
                result = 1;
            }
        }
        if (stream instanceof SslSocketStream ssl && ssl.enableOnConnect
                && (result == 0 || asynchronous && result == 1 && code[0] == Network.EINPROGRESS)) {
            if (ssl.setupCrypto(ssl.method, null) < 0 || ssl.enableCrypto(true) < 0) {
                Network.warn(context, ErrorFunctions.E_WARNING, "Failed to enable crypto");
                result = -1;
            }
        }
        return result;
    }

    private static int attach(SocketStream stream, SocketChannel channel, String[] error, int[] code) {
        if (channel == null) {
            return -1;
        }
        try {
            stream.attach(channel);
            return 0;
        } catch (IOException e) {
            Network.close(channel);
            code[0] = Network.errno(e);
            error[0] = Network.strerror(code[0]);
            return -1;
        }
    }

    /**
     * The Unix socket connect of php_tcp_sockop_connect: the path (truncated to sun_path's 107 bytes with a
     * notice, as parse_unix_address). Linux's abstract names (a leading NUL) have no JDK counterpart: EINVAL.
     */
    private static int connectUnix(PhpContext context, SocketStream stream, byte[] name, boolean asynchronous,
                                   Network.Timeval timeout, String[] error, int[] code) {
        boolean abstractName = name.length > 0 && name[0] == 0;
        int maxLength = abstractName ? 108 : 107;
        if (name.length > maxLength) {
            name = Arrays.copyOf(name, maxLength);
            Network.warn(context, ErrorFunctions.E_NOTICE, "socket path exceeded the maximum allowed length of "
                    + maxLength + " bytes and was truncated");
        }
        SocketChannel channel;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.configureBlocking(false);
        } catch (IOException | UnsupportedOperationException e) {
            error[0] = "Failed to create unix socket";
            return -1;
        }
        Network.Connection connection = new Network.Connection();
        boolean connected;
        if (abstractName || SslSocketStream.cString(name).length != name.length) {
            connected = false;
            connection.errorCode = 22;
            connection.errorString = Network.strerror(22);
        } else {
            UnixDomainSocketAddress address;
            try {
                address = UnixDomainSocketAddress.of(new String(name, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                address = null;
            }
            connected = address != null
                    && Network.connectSocket(context, channel, address, asynchronous, timeout, connection);
            if (address == null) {
                connection.errorCode = Network.ENOENT;
                connection.errorString = Network.strerror(Network.ENOENT);
            }
        }
        code[0] = connection.errorCode;
        error[0] = connection.errorString;
        if (!connected) {
            Network.close(channel);
            return -1;
        }
        int result = attach(stream, channel, error, code);
        return result >= 0 && asynchronous && code[0] == Network.EINPROGRESS ? 1 : result;
    }

    /**
     * parse_ip_address_ex: the host of "host:port" (the first colon before the last byte) or "[v6]:port", with
     * port[0] as atoi reads it; null with error[0] set.
     */
    static String parseIpAddress(byte[] str, int[] port, String[] error) {
        for (byte b : str) {
            if (b == 0) {
                error[0] = "The hostname must not contain null bytes";
                return null;
            }
        }
        if (str.length > 1 && str[0] == '[') {
            int close = -1;
            for (int i = 1; i < str.length - 1; i++) {
                if (str[i] == ']') {
                    close = i;
                    break;
                }
            }
            if (close < 0 || str[close + 1] != ':') {
                error[0] = "Failed to parse IPv6 address \"" + Network.latin1(str) + "\"";
                return null;
            }
            port[0] = atoi(str, close + 2);
            return Network.latin1(Arrays.copyOfRange(str, 1, close));
        }
        int colon = -1;
        for (int i = 0; i < str.length - 1; i++) {
            if (str[i] == ':') {
                colon = i;
                break;
            }
        }
        if (colon < 0) {
            error[0] = "Failed to parse address \"" + Network.latin1(str) + "\"";
            return null;
        }
        port[0] = atoi(str, colon + 1);
        return Network.latin1(Arrays.copyOf(str, colon));
    }

    /** C's atoi from index i (strtol's value cast to int). */
    private static int atoi(byte[] str, int i) {
        while (i < str.length && (str[i] == ' ' || str[i] >= '\t' && str[i] <= '\r')) {
            i++;
        }
        boolean negative = false;
        if (i < str.length && (str[i] == '+' || str[i] == '-')) {
            negative = str[i] == '-';
            i++;
        }
        long value = 0;
        while (i < str.length && str[i] >= '0' && str[i] <= '9') {
            value = value > Long.MAX_VALUE / 10 ? Long.MAX_VALUE : value * 10 + (str[i] - '0');
            i++;
        }
        return (int) (negative ? -value : value);
    }

    /** stream_socket_enable_crypto(stream, enable, crypto_method = null, session_stream = null). */
    @TruffleBoundary
    private static Object enableCrypto(PhpContext context, Object[] a) {
        FileFunctions.Stream stream = FileFunctions.stream(a, "stream_socket_enable_crypto");
        boolean enable = Args.bool(a, 1, false);
        Object methodArgument = PhpRef.deref(Args.get(a, 2, PhpNull.NULL));
        Object sessionArgument = PhpRef.deref(Args.get(a, 3, PhpNull.NULL));
        FileFunctions.Stream session = null;
        if (sessionArgument != PhpNull.NULL) {
            if (!(sessionArgument instanceof PhpResource resource)
                    || !(resource.getHandle() instanceof FileFunctions.Stream sessionStream)) {
                throw new PhpError("TypeError", "stream_socket_enable_crypto(): Argument #4 ($session_stream) must be "
                        + "of type resource or null, " + StreamContext.typeName(sessionArgument) + " given");
            }
            session = sessionStream;
        }
        if (enable) {
            long method;
            if (methodArgument == PhpNull.NULL) {
                Object option = stream.context == null ? null : stream.context.option("ssl", "crypto_method");
                if (option == null) {
                    throw new PhpError("ValueError", "stream_socket_enable_crypto(): Argument #3 ($crypto_method) must "
                            + "be specified when enabling encryption");
                }
                method = Convert.toLong(option);
            } else {
                method = Convert.toLong(methodArgument);
            }
            if (!(stream instanceof SslSocketStream ssl)) {
                Network.warn(context, ErrorFunctions.E_WARNING, "This stream does not support SSL/crypto");
                return Boolean.FALSE;
            }
            if (ssl.setupCrypto((int) method, session) < 0) {
                return Boolean.FALSE;
            }
        }
        int result;
        if (stream instanceof SslSocketStream ssl) {
            result = ssl.enableCrypto(enable);
        } else {
            Network.warn(context, ErrorFunctions.E_WARNING, "This stream does not support SSL/crypto");
            result = FileFunctions.OPTION_NOT_IMPLEMENTED;
        }
        return switch (result) {
            case -1 -> Boolean.FALSE;
            case 0 -> (Object) 0L;
            default -> Boolean.TRUE;
        };
    }
}
