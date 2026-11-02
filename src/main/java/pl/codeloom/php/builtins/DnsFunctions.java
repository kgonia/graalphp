package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.ffi.NativeLibrary;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ext/standard/dns.c's resolver functions: gethostbyname(), gethostbynamel(), gethostbyaddr(), through the C
 * library's resolver on Linux (gethostbyname_r, getnameinfo), as php-src. Elsewhere (the Windows dev host) the JDK's
 * resolver stands in, its IPv4 addresses only, as gethostbyname() resolves.
 */
final class DnsFunctions {
    /** MAXFQDNLEN. */
    private static final int MAX_FQDN = 255;

    private DnsFunctions() {
    }

    static void register(Registry r) {
        r.fn("gethostbyname", (context, a) -> gethostbyname(context, a));
        r.fn("gethostbynamel", (context, a) -> gethostbynamel(context, a));
        r.fn("gethostbyaddr", (context, a) -> gethostbyaddr(context, a));
    }

    /** PHP_FUNCTION(gethostbyname): the first IPv4 address, or the name itself when there is none. */
    @TruffleBoundary
    private static Object gethostbyname(PhpContext context, Object[] a) {
        Args.arity(a, "gethostbyname", 1, 1);
        byte[] name = path(context, a, "gethostbyname", "hostname");
        if (name.length > MAX_FQDN) {
            // protects from CVE-2015-0235
            warn(context, "gethostbyname(): Host name cannot be longer than " + MAX_FQDN + " characters");
            return Str.of(name);
        }
        List<String> addresses = resolve(name);
        return addresses == null || addresses.isEmpty() ? Str.of(name) : Str.of(addresses.get(0));
    }

    /** PHP_FUNCTION(gethostbynamel): the IPv4 addresses, false when the name doesn't resolve. */
    @TruffleBoundary
    private static Object gethostbynamel(PhpContext context, Object[] a) {
        Args.arity(a, "gethostbynamel", 1, 1);
        byte[] name = path(context, a, "gethostbynamel", "hostname");
        if (name.length > MAX_FQDN) {
            warn(context, "gethostbynamel(): Host name cannot be longer than " + MAX_FQDN + " characters");
            return false;
        }
        List<String> addresses = resolve(name);
        if (addresses == null) {
            return false;
        }
        PhpArray result = new PhpArray();
        for (String address : addresses) {
            result.append(Str.of(address));
        }
        return result;
    }

    /**
     * PHP_FUNCTION(gethostbyaddr): php_gethostbyaddr's getnameinfo(NI_NAMEREQD) of an IPv6 or else IPv4 address
     * (inet_pton's syntax); the address itself when it has no name.
     */
    @TruffleBoundary
    private static Object gethostbyaddr(PhpContext context, Object[] a) {
        Args.arity(a, "gethostbyaddr", 1, 1);
        byte[] text = path(context, a, "gethostbyaddr", "ip");
        String ip = new String(text, StandardCharsets.ISO_8859_1);
        byte[] address = InetPton.pton6(ip);
        if (address == null) {
            address = InetPton.pton4(ip);
        }
        if (address == null) {
            warn(context, "gethostbyaddr(): Address is not a valid IPv4 or IPv6 address");
            return false;
        }
        String host = LINUX ? Libc.nameOf(address) : jdkNameOf(address);
        return host == null ? Str.of(text) : Str.of(host);
    }

    /**
     * php_network_gethostbyname: the name's IPv4 addresses in the resolver's order, null when it doesn't resolve.
     * On Linux the C library's gethostbyname_r (as php-src's ZTS build), elsewhere the JDK's resolver.
     */
    private static List<String> resolve(byte[] name) {
        return LINUX ? Libc.addressesOf(name) : jdkAddressesOf(name);
    }

    private static List<String> jdkAddressesOf(byte[] name) {
        if (name.length == 0) {
            // the C library resolves no empty name; the JDK would give the loopback address
            return null;
        }
        try {
            List<String> out = new ArrayList<>();
            for (InetAddress address : InetAddress.getAllByName(new String(name, StandardCharsets.UTF_8))) {
                if (address instanceof Inet4Address) {
                    out.add(address.getHostAddress());
                }
            }
            return out.isEmpty() ? null : out;
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    private static String jdkNameOf(byte[] address) {
        try {
            // an address made from its bytes has no name yet: getHostName() looks it up, without the forward check
            // getCanonicalHostName() adds; it gives the address's text when there is no name
            InetAddress inet = InetAddress.getByAddress(address);
            String host = inet.getHostName();
            return host.isEmpty() || host.equals(inet.getHostAddress()) ? null : host;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static final boolean LINUX = System.getProperty("os.name").startsWith("Linux");

    /** The C library's resolver (glibc, x86-64 and aarch64 layouts): declared with the image (NativeCallsFeature). */
    private static final class Libc {
        private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
        private static final AddressLayout PTR = ValueLayout.ADDRESS;
        /** struct hostent: h_name, h_aliases, h_addrtype, h_length, h_addr_list. */
        private static final StructLayout HOSTENT = MemoryLayout.structLayout(PTR.withName("h_name"),
                PTR.withName("h_aliases"), INT.withName("h_addrtype"), INT.withName("h_length"),
                PTR.withName("h_addr_list"));
        private static final long H_ADDR_LIST = HOSTENT.byteOffset(MemoryLayout.PathElement.groupElement(
                "h_addr_list"));
        private static final int ERANGE = 34;
        private static final short AF_INET = 2;
        private static final short AF_INET6 = 10;
        private static final int NI_NAMEREQD = 8;
        private static final int NI_MAXHOST = 1025;

        static final MethodHandle GETHOSTBYNAME_R = NativeLibrary.LIBC.downcall("gethostbyname_r",
                FunctionDescriptor.of(INT, PTR, PTR, PTR, NativeLibrary.SIZE_T, PTR, PTR));
        static final MethodHandle GETNAMEINFO = NativeLibrary.LIBC.downcall("getnameinfo",
                FunctionDescriptor.of(INT, PTR, INT, PTR, INT, PTR, INT, INT));

        private Libc() {
        }

        /** php_gethostbyname_r: the buffer doubled while the C library says ERANGE. */
        static List<String> addressesOf(byte[] name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cName = arena.allocate(name.length + 1L);
                MemorySegment.copy(name, 0, cName, ValueLayout.JAVA_BYTE, 0, name.length);
                MemorySegment hostent = arena.allocate(HOSTENT);
                MemorySegment result = arena.allocate(PTR);
                MemorySegment error = arena.allocate(INT);
                long size = 8192;
                while (true) {
                    MemorySegment buffer = arena.allocate(size);
                    int status = (int) GETHOSTBYNAME_R.invokeExact(cName, hostent, buffer, size, result, error);
                    if (status == ERANGE) {
                        size *= 2;
                        continue;
                    }
                    break;
                }
                if (result.get(PTR, 0).equals(MemorySegment.NULL)) {
                    return null;
                }
                List<String> out = new ArrayList<>();
                MemorySegment list = hostent.get(PTR, H_ADDR_LIST).reinterpret(Long.MAX_VALUE);
                for (long i = 0; ; i++) {
                    MemorySegment address = list.getAtIndex(PTR, i);
                    if (address.equals(MemorySegment.NULL)) {
                        break;
                    }
                    MemorySegment in = address.reinterpret(4);
                    out.add(Byte.toUnsignedInt(in.get(ValueLayout.JAVA_BYTE, 0)) + "."
                            + Byte.toUnsignedInt(in.get(ValueLayout.JAVA_BYTE, 1)) + "."
                            + Byte.toUnsignedInt(in.get(ValueLayout.JAVA_BYTE, 2)) + "."
                            + Byte.toUnsignedInt(in.get(ValueLayout.JAVA_BYTE, 3)));
                }
                return out;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        /** getnameinfo(NI_NAMEREQD) of a sockaddr_in6 (16 bytes) or sockaddr_in (4); null when it has no name. */
        static String nameOf(byte[] address) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sockaddr;
                if (address.length == 16) {
                    // sin6_family, sin6_port, sin6_flowinfo, sin6_addr, sin6_scope_id
                    sockaddr = arena.allocate(28, 4);
                    sockaddr.set(ValueLayout.JAVA_SHORT, 0, AF_INET6);
                    MemorySegment.copy(address, 0, sockaddr, ValueLayout.JAVA_BYTE, 8, 16);
                } else {
                    // sin_family, sin_port, sin_addr, sin_zero
                    sockaddr = arena.allocate(16, 4);
                    sockaddr.set(ValueLayout.JAVA_SHORT, 0, AF_INET);
                    MemorySegment.copy(address, 0, sockaddr, ValueLayout.JAVA_BYTE, 4, 4);
                }
                MemorySegment host = arena.allocate(NI_MAXHOST);
                int status = (int) GETNAMEINFO.invokeExact(sockaddr, (int) sockaddr.byteSize(), host, NI_MAXHOST,
                        MemorySegment.NULL, 0, NI_NAMEREQD);
                return status != 0 ? null : host.getString(0, StandardCharsets.ISO_8859_1);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Z_PARAM_PATH: a string without NUL bytes. */
    private static byte[] path(PhpContext context, Object[] a, String function, String name) {
        TruffleString value = Args.string(context, a, 0, function, name, false);
        byte[] bytes = Str.bytes(value);
        for (byte b : bytes) {
            if (b == 0) {
                throw new PhpError("ValueError", function + "(): Argument #1 ($" + name
                        + ") must not contain any null bytes");
            }
        }
        return bytes;
    }

    private static void warn(PhpContext context, String message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(message), SourceLocation.current());
    }

    /** glibc's inet_pton (resolv/inet_pton.c): an address's text to its bytes, or null for anything else. */
    static final class InetPton {
        private InetPton() {
        }

        /** inet_pton4: four decimal octets, no leading zero, none past 255. */
        static byte[] pton4(String src) {
            byte[] out = new byte[4];
            int octets = 0;
            int value = 0;
            boolean sawDigit = false;
            int i = 0;
            for (; i < src.length(); i++) {
                char ch = src.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    if (sawDigit && value == 0) {
                        return null;
                    }
                    value = value * 10 + (ch - '0');
                    if (value > 255) {
                        return null;
                    }
                    if (!sawDigit) {
                        if (++octets > 4) {
                            return null;
                        }
                        sawDigit = true;
                    }
                    out[octets - 1] = (byte) value;
                } else if (ch == '.' && sawDigit) {
                    if (octets == 4) {
                        return null;
                    }
                    value = 0;
                    sawDigit = false;
                } else {
                    return null;
                }
            }
            return octets < 4 ? null : out;
        }

        /** inet_pton6: up to eight groups of up to four hex digits, one "::", a dotted IPv4 tail. */
        static byte[] pton6(String src) {
            byte[] out = new byte[16];
            int tp = 0;
            int colon = -1;
            int i = 0;
            if (src.startsWith(":")) {
                if (!src.startsWith("::")) {
                    return null;
                }
                i = 1;
            }
            int current = i;
            boolean sawXdigit = false;
            int value = 0;
            int digits = 0;
            while (i < src.length()) {
                char ch = src.charAt(i++);
                int digit = Character.digit(ch, 16);
                if (digit >= 0 && ch < 128) {
                    if (++digits > 4) {
                        return null;
                    }
                    value = (value << 4) | digit;
                    sawXdigit = true;
                    continue;
                }
                if (ch == ':') {
                    current = i;
                    if (!sawXdigit) {
                        if (colon >= 0) {
                            return null;
                        }
                        colon = tp;
                        continue;
                    } else if (i == src.length()) {
                        return null;
                    }
                    if (tp + 2 > 16) {
                        return null;
                    }
                    out[tp++] = (byte) (value >> 8);
                    out[tp++] = (byte) value;
                    sawXdigit = false;
                    digits = 0;
                    value = 0;
                    continue;
                }
                if (ch == '.' && tp + 4 <= 16) {
                    byte[] v4 = pton4(src.substring(current));
                    if (v4 == null) {
                        return null;
                    }
                    System.arraycopy(v4, 0, out, tp, 4);
                    tp += 4;
                    sawXdigit = false;
                    break;
                }
                return null;
            }
            if (sawXdigit) {
                if (tp + 2 > 16) {
                    return null;
                }
                out[tp++] = (byte) (value >> 8);
                out[tp++] = (byte) value;
            }
            if (colon >= 0) {
                if (tp == 16) {
                    return null;
                }
                int n = tp - colon;
                for (int k = 1; k <= n; k++) {
                    out[16 - k] = out[colon + n - k];
                    out[colon + n - k] = 0;
                }
                tp = 16;
            }
            return tp == 16 ? out : null;
        }
    }
}
