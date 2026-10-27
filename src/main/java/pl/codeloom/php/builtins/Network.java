package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.call.BuiltinRootNode;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The client side of php-src's main/network.c: name resolution (php_network_getaddresses) and connecting to each
 * address in turn within one timeout (php_network_connect_socket_to_host), with the errno values and messages of
 * Linux and glibc, as PHP reports them there.
 *
 * <p>Names are resolved by the JDK (InetAddress), whose order follows java.net.preferIPv6Addresses: "system" (the
 * launchers set it) keeps getaddrinfo's order, as php-src connects in; the JDK's default puts IPv4 first. The JDK
 * also caches lookups (networkaddress.cache.ttl, 30 s, and 10 s for failures), where glibc asks each time.
 */
final class Network {
    private Network() {
    }

    static final int ENOENT = 2;
    static final int EIO = 5;
    static final int EAGAIN = 11;
    static final int EINPROGRESS = 115;
    static final int ENOTCONN = 107;
    static final int ETIMEDOUT = 110;
    static final int ECONNREFUSED = 111;
    static final int ECONNRESET = 104;

    /** glibc's strerror texts for the errno values sockets meet, by Linux's numbers. */
    private static final Map<Integer, String> STRERROR = Map.ofEntries(
            Map.entry(1, "Operation not permitted"), Map.entry(ENOENT, "No such file or directory"),
            Map.entry(4, "Interrupted system call"), Map.entry(EIO, "Input/output error"),
            Map.entry(9, "Bad file descriptor"), Map.entry(EAGAIN, "Resource temporarily unavailable"),
            Map.entry(13, "Permission denied"), Map.entry(22, "Invalid argument"), Map.entry(32, "Broken pipe"),
            Map.entry(36, "File name too long"), Map.entry(88, "Socket operation on non-socket"),
            Map.entry(97, "Address family not supported by protocol"), Map.entry(98, "Address already in use"),
            Map.entry(99, "Cannot assign requested address"), Map.entry(100, "Network is down"),
            Map.entry(101, "Network is unreachable"), Map.entry(103, "Software caused connection abort"),
            Map.entry(ECONNRESET, "Connection reset by peer"), Map.entry(105, "No buffer space available"),
            Map.entry(106, "Transport endpoint is already connected"),
            Map.entry(ENOTCONN, "Transport endpoint is not connected"), Map.entry(ETIMEDOUT, "Connection timed out"),
            Map.entry(ECONNREFUSED, "Connection refused"), Map.entry(112, "Host is down"),
            Map.entry(113, "No route to host"), Map.entry(EINPROGRESS, "Operation now in progress"));

    /**
     * The JDK's messages that aren't glibc's own text: on Linux the JDK reports strerror(), except for a reset
     * connection; the Windows host's messages are WinSock's.
     */
    private static final Map<String, Integer> JDK_MESSAGES = Map.of(
            "Connection reset", ECONNRESET,
            "An existing connection was forcibly closed by the remote host", ECONNRESET,
            "An established connection was aborted by the software in your host machine", 32,
            "A connection attempt failed because the connected party did not properly respond", ETIMEDOUT,
            "No connection could be made because the target machine actively refused it", ECONNREFUSED,
            "A socket operation was attempted to an unreachable network", 101,
            "A socket operation was attempted to an unreachable host", 113);

    /** getaddrinfo's messages (gai_strerror), which the JDK puts after "host: " on Linux. */
    private static final String[] GAI_ERRORS = {"Address family for hostname not supported",
        "Temporary failure in name resolution", "Bad value for ai_flags", "Non-recoverable failure in name resolution",
        "ai_family not supported", "Memory allocation failure", "No address associated with hostname",
        "Name or service not known", "Servname not supported for ai_socktype", "ai_socktype not supported",
        "System error"};

    /** A struct timeval: a timeout of sec seconds and usec microseconds; a negative sec means none. */
    record Timeval(long sec, long usec) {
        /** php_tvtoto: the poll(2) timeout in milliseconds, -1 for no limit. */
        long millis() {
            if (sec >= 0 && sec <= (Integer.MAX_VALUE - 1000) / 1000) {
                return sec * 1000 + usec / 1000;
            }
            return -1;
        }

        long micros() {
            return sec * 1_000_000 + usec;
        }

        static Timeval ofMicros(long micros) {
            return new Timeval(micros / 1_000_000, micros % 1_000_000);
        }
    }

    /** php_socket_strerror on Linux: glibc's text, or its "Unknown error N". */
    static String strerror(int errno) {
        String text = STRERROR.get(errno);
        return text != null ? text : "Unknown error " + errno;
    }

    /** The errno a JDK socket exception stands for. */
    static int errno(IOException e) {
        String message = e.getMessage();
        if (message != null) {
            for (Map.Entry<Integer, String> entry : STRERROR.entrySet()) {
                if (message.startsWith(entry.getValue())) {
                    return entry.getKey();
                }
            }
            for (Map.Entry<String, Integer> entry : JDK_MESSAGES.entrySet()) {
                if (message.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        if (e instanceof ConnectException) {
            return ECONNREFUSED;
        }
        if (e instanceof NoRouteToHostException) {
            return 113;
        }
        return EIO; // a message without a Linux counterpart
    }

    /** php_error_docref: the message after the active function's name. */
    static void warn(PhpContext context, int type, String message) {
        String function = BuiltinRootNode.activeName();
        ErrorFunctions.raise(context, type, latin1(function == null ? message : function + "(): " + message),
                SourceLocation.current());
    }

    /** A message's bytes: names keep their bytes as ISO-8859-1 chars here. */
    static com.oracle.truffle.api.strings.TruffleString latin1(String text) {
        return Str.of(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * php_network_getaddresses: the host's addresses (AF_UNSPEC: IPv4 and IPv6), or null after the warning, with
     * errorString[0] set to its text.
     */
    @TruffleBoundary
    static InetAddress[] getAddresses(PhpContext context, String host, String[] errorString) {
        String failure;
        try {
            if (host.isEmpty()) {
                throw new UnknownHostException(": Name or service not known"); // the JDK would give the loopback
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length > 0) {
                return addresses;
            }
            failure = "Name or service not known";
        } catch (UnknownHostException | SecurityException | IllegalArgumentException e) {
            failure = gaiError(e.getMessage());
        }
        errorString[0] = "php_network_getaddresses: getaddrinfo for " + host + " failed: " + failure;
        warn(context, ErrorFunctions.E_WARNING, errorString[0]);
        return null;
    }

    /** The getaddrinfo error in a JDK message ("host: text" on Linux); EAI_NONAME's text otherwise. */
    private static String gaiError(String message) {
        if (message != null) {
            for (String text : GAI_ERRORS) {
                if (message.endsWith(": " + text)) {
                    return text;
                }
            }
        }
        return "Name or service not known";
    }

    /** The result of connecting: the channel (null on failure), the errno, and the error's text. */
    static final class Connection {
        SocketChannel channel;
        int errorCode;
        String errorString;
    }

    /**
     * php_network_connect_socket_to_host: tries each address in order, each with what is left of the timeout
     * (null: none), until one connects. asynchronous leaves the connect in progress (EINPROGRESS). bindTo and
     * bindPort are the "bindto" socket option's address; nodelay is "tcp_nodelay".
     */
    @TruffleBoundary
    static Connection connectToHost(PhpContext context, String host, int port, boolean asynchronous, Timeval timeout,
                                    String bindTo, int bindPort, boolean nodelay) {
        Connection result = new Connection();
        String[] error = new String[1];
        InetAddress[] addresses = getAddresses(context, host, error);
        if (addresses == null) {
            result.errorString = error[0];
            return result;
        }
        long limit = timeout == null ? 0 : System.nanoTime() + timeout.micros() * 1000;
        Timeval working = timeout;
        for (InetAddress address : addresses) {
            boolean v6 = address instanceof Inet6Address;
            String bind = bindTo;
            if (v6) {
                if (bind != null && bind.indexOf(':') < 0) {
                    continue; // expects an IPv4 address: skip to the next
                }
            } else if (bind != null && (bind.indexOf(':') >= 0 || bind.equals("0"))) {
                bind = null; // an IPv4 socket can't bind to an IPv6 address
            }
            SocketChannel channel;
            try {
                channel = SocketChannel.open(v6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
                channel.configureBlocking(false);
            } catch (IOException | UnsupportedOperationException e) {
                continue;
            }
            if (bind != null) {
                bindLocal(context, channel, v6, bind, bindPort);
            }
            result.errorString = null;
            try {
                if (nodelay) {
                    channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                }
            } catch (IOException e) {
                // setsockopt's result isn't checked
            }
            if (connectSocket(context, channel, new InetSocketAddress(address, port), asynchronous, working, result)) {
                result.channel = channel;
                return result;
            }
            close(channel);
            if (timeout != null) {
                long left = limit - System.nanoTime();
                if (left <= 0) {
                    break; // time limit expired: don't attempt any further connections
                }
                working = Timeval.ofMicros(left / 1000);
            }
        }
        return result;
    }

    /** The bind of php_network_connect_socket_to_host: failures only warn. */
    private static void bindLocal(PhpContext context, SocketChannel channel, boolean v6, String bindTo, int port) {
        InetAddress local = numericAddress(bindTo, v6);
        if (local == null) {
            warn(context, ErrorFunctions.E_WARNING, "Invalid IP Address: " + bindTo);
            return;
        }
        try {
            channel.bind(new InetSocketAddress(local, port));
        } catch (IOException e) {
            warn(context, ErrorFunctions.E_WARNING, "Failed to bind to '" + bindTo + ":" + port + "', system said: "
                    + strerror(errno(e)));
        }
    }

    /** inet_pton: the address a numeric IPv4 (or IPv6) text stands for, or null. */
    static InetAddress numericAddress(String text, boolean v6) {
        if (v6 ? text.indexOf(':') < 0 : !text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(text);
            return v6 || address instanceof Inet4Address ? address : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * php_network_connect_socket: a non-blocking connect, then a wait for it within the timeout (null: none). True
     * once connected, or in progress when asynchronous; false with the error set in result.
     */
    static boolean connectSocket(PhpContext context, SocketChannel channel, java.net.SocketAddress address,
                                 boolean asynchronous, Timeval timeout, Connection result) {
        try {
            if (channel.connect(address)) {
                result.errorCode = 0;
                return true;
            }
        } catch (IOException e) {
            return fail(result, errno(e));
        }
        result.errorCode = EINPROGRESS;
        if (asynchronous) {
            return true;
        }
        try (java.nio.channels.Selector selector = java.nio.channels.Selector.open()) {
            channel.register(selector, java.nio.channels.SelectionKey.OP_CONNECT);
            int ready = SocketStream.select(selector, timeout == null ? -1 : timeout.millis());
            if (ready == 0) {
                return fail(result, ETIMEDOUT);
            }
            channel.finishConnect();
            channel.keyFor(selector).cancel();
            selector.selectNow();
            result.errorCode = 0;
            return true;
        } catch (IOException e) {
            return fail(result, errno(e));
        }
    }

    private static boolean fail(Connection result, int errno) {
        result.errorCode = errno;
        result.errorString = strerror(errno);
        return false;
    }

    static void close(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            // closesocket's result isn't checked
        }
    }

    /**
     * php_network_populate_name_from_sockaddr's text: "a.b.c.d:port", "[v6]:port" (inet_ntop's form), or a Unix
     * socket's path.
     */
    static String nameOf(java.net.SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            InetAddress ip = inet.getAddress();
            if (ip instanceof Inet6Address v6) {
                return "[" + ntop6(v6.getAddress()) + "]:" + inet.getPort();
            }
            return ip.getHostAddress() + ":" + inet.getPort();
        }
        if (address instanceof java.net.UnixDomainSocketAddress unix) {
            return unix.getPath().toString();
        }
        return null;
    }

    /** glibc's inet_ntop for AF_INET6: the longest run of two or more zero groups as "::", an embedded IPv4 dotted. */
    static String ntop6(byte[] bytes) {
        int[] words = new int[8];
        for (int i = 0; i < 8; i++) {
            words[i] = (bytes[2 * i] & 0xFF) << 8 | bytes[2 * i + 1] & 0xFF;
        }
        int bestBase = -1;
        int bestLength = 0;
        int base = -1;
        int length = 0;
        for (int i = 0; i < 8; i++) {
            if (words[i] == 0) {
                if (base < 0) {
                    base = i;
                    length = 1;
                } else {
                    length++;
                }
            } else if (base >= 0) {
                if (bestBase < 0 || length > bestLength) {
                    bestBase = base;
                    bestLength = length;
                }
                base = -1;
            }
        }
        if (base >= 0 && (bestBase < 0 || length > bestLength)) {
            bestBase = base;
            bestLength = length;
        }
        if (bestBase >= 0 && bestLength < 2) {
            bestBase = -1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (bestBase >= 0 && i >= bestBase && i < bestBase + bestLength) {
                if (i == bestBase) {
                    out.append(':');
                }
                continue;
            }
            if (i != 0) {
                out.append(':');
            }
            if (i == 6 && bestBase == 0 && (bestLength == 6 || bestLength == 5 && words[5] == 0xFFFF)) {
                out.append(bytes[12] & 0xFF).append('.').append(bytes[13] & 0xFF).append('.')
                        .append(bytes[14] & 0xFF).append('.').append(bytes[15] & 0xFF);
                break;
            }
            out.append(Integer.toHexString(words[i]));
        }
        if (bestBase >= 0 && bestBase + bestLength == 8) {
            out.append(':');
        }
        return out.toString();
    }
}
