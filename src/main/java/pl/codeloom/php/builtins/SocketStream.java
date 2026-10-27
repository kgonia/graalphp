package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpArray;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * A socket stream, as php-src's main/streams/xp_socket.c (php_netstream_data_t and the php_sockop_* operations):
 * a stream socket, blocking or not, with a read timeout whose expiry sets timed_out. Reads take what one recv(2)
 * gives (php-src reads sockets non-greedily); a 0-byte recv is the end of the stream.
 *
 * <p>The socket is a SocketChannel kept in non-blocking mode, with a Selector standing in for poll(2): a blocking
 * stream waits in the Selector as php-src waits in poll() before its MSG_DONTWAIT recv and send, so read and write
 * timeouts both work as there. recv(MSG_PEEK) (feof()'s liveness check) has no JDK counterpart: the byte is read
 * ahead into this stream and given to the next read, which is what the kernel would have kept.
 */
class SocketStream extends FileFunctions.Stream {
    /** The stream's ops label (stream_type): "tcp_socket/ssl" (as ext/openssl registers tcp), "unix_socket". */
    private final String label;
    /** The PHP context, for warnings and the ini. */
    final PhpContext php;
    /** sock->socket: null until connected. */
    SocketChannel channel;
    private Selector selector;
    private SelectionKey key;
    /** sock->is_blocked. */
    boolean blocked = true;
    /** sock->timeout: the read (and write) timeout; tv_sec == -1 means none. */
    Network.Timeval timeout;
    /** sock->timeout_event: the last wait timed out (stream_get_meta_data's timed_out). */
    boolean timeoutEvent;
    /** Bytes read ahead by the liveness check (recv with MSG_PEEK), given to the next read first. */
    private byte[] peeked = new byte[0];
    private int peekedPosition;

    SocketStream(PhpContext context, String label, String uri) {
        super("r+", uri);
        this.php = context;
        this.label = label;
        this.timeout = new Network.Timeval(context.ini().defaultSocketTimeout(), 0);
    }

    /** The connected socket, with the selector its waits use. */
    void attach(SocketChannel connected) throws IOException {
        channel = connected;
        channel.configureBlocking(false);
        selector = Selector.open();
        key = channel.register(selector, 0);
    }

    @Override
    String wrapperType() {
        return null;
    }

    @Override
    String streamType() {
        return label;
    }

    @Override
    boolean greedy() {
        return false;
    }

    // ---- php_sockop_read / php_sockop_write ----

    @Override
    int rawRead(byte[] into, int offset, int length) {
        return socketRead(into, offset, length);
    }

    @Override
    int rawWrite(byte[] bytes, int offset, int length) {
        return socketWrite(bytes, offset, length);
    }

    /** php_sockop_read: waits for data when blocking (not when some was buffered), then one recv. */
    @TruffleBoundary
    final int socketRead(byte[] into, int offset, int length) {
        if (channel == null) {
            return -1;
        }
        if (blocked) {
            boolean hasBufferedData = this.hasBufferedData;
            boolean dontWait = hasBufferedData || timeout.sec() == 0 && timeout.usec() == 0;
            if (!dontWait) {
                waitForData(hasBufferedData);
                if (timeoutEvent) {
                    return hasBufferedData ? 0 : -1;
                }
            }
        }
        int n;
        try {
            n = receive(ByteBuffer.wrap(into, offset, length));
        } catch (IOException e) {
            eof = true;
            return -1;
        }
        if (n < 0) {
            eof = true;
            return 0;
        }
        return n;
    }

    /** php_sock_stream_wait_for_data. */
    private void waitForData(boolean hasBufferedData) {
        timeoutEvent = false;
        Network.Timeval wait = hasBufferedData ? new Network.Timeval(0, 0) : timeout.sec() == -1 ? null : timeout;
        if (poll(SelectionKey.OP_READ, wait) == 0) {
            timeoutEvent = true;
        }
    }

    /**
     * recv(2) without waiting: the count read (0 if nothing is there: EAGAIN), or -1 at the end of the stream; the
     * read-ahead bytes come first.
     */
    final int receive(ByteBuffer into) throws IOException {
        if (peekedPosition < peeked.length) {
            int n = Math.min(into.remaining(), peeked.length - peekedPosition);
            into.put(peeked, peekedPosition, n);
            peekedPosition += n;
            return n;
        }
        if (!finishConnect()) {
            throw new IOException(Network.strerror(Network.ENOTCONN));
        }
        return channel.read(into);
    }

    /** php_sockop_write: a send without waiting; a blocking stream waits (up to its timeout) for room. */
    @TruffleBoundary
    final int socketWrite(byte[] bytes, int offset, int length) {
        if (channel == null) {
            return 0;
        }
        Network.Timeval wait = timeout.sec() == -1 ? null : timeout;
        for (;;) {
            int err;
            try {
                int n = finishConnect() ? channel.write(ByteBuffer.wrap(bytes, offset, length)) : 0;
                if (n > 0) {
                    return n;
                }
                err = Network.EAGAIN;
            } catch (IOException e) {
                err = Network.errno(e);
            }
            if (err == Network.EAGAIN) {
                if (!blocked) {
                    return 0; // not an error for a non-blocking stream
                }
                timeoutEvent = false;
                if (poll(SelectionKey.OP_WRITE, wait) > 0) {
                    continue;
                }
                timeoutEvent = true;
            }
            Network.warn(php, ErrorFunctions.E_NOTICE, "Send of " + length + " bytes failed with errno=" + err + " "
                    + Network.strerror(err));
            return -1;
        }
    }

    /** An asynchronous connect (STREAM_CLIENT_ASYNC_CONNECT) completes here: false while it is in progress. */
    private boolean finishConnect() throws IOException {
        return !channel.isConnectionPending() || channel.finishConnect();
    }

    // ---- poll(2) ----

    /**
     * php_pollfd_for: waits until the socket is ready for ops (SelectionKey's) or the timeout (null: none)
     * passes; the ready ops, or 0 on a timeout. An error or hang-up shows as ready, as POLLERR and POLLHUP do.
     */
    @TruffleBoundary
    final int poll(int ops, Network.Timeval wait) {
        if ((ops & SelectionKey.OP_READ) != 0 && peekedPosition < peeked.length) {
            return SelectionKey.OP_READ;
        }
        try {
            int interest = channel.isConnectionPending() ? SelectionKey.OP_CONNECT : ops;
            key.interestOps(interest);
            selector.selectedKeys().clear();
            int ready = select(selector, wait == null ? -1 : wait.millis());
            if (ready == 0) {
                return 0;
            }
            if (interest == SelectionKey.OP_CONNECT) {
                // the asynchronous connect is done (or failed, which the next recv or send reports)
                return ops;
            }
            return key.readyOps() & ops;
        } catch (IOException | java.nio.channels.CancelledKeyException e) {
            return ops; // poll() reports POLLNVAL: the operation that follows fails
        }
    }

    /**
     * Selector.select with a poll(2) timeout in milliseconds (negative: no limit): the number of ready keys, 0 on
     * a timeout. Interruptible by Truffle's safepoints (a cancelled context), as a blocking wait must be.
     */
    static int select(Selector selector, long millis) throws IOException {
        if (millis == 0) {
            return selector.selectNow();
        }
        long deadline = System.nanoTime() + millis * 1_000_000;
        int[] ready = new int[1];
        try {
            TruffleSafepoint.setBlockedThreadInterruptible(null, s -> {
                try {
                    for (;;) {
                        long wait = 0;
                        if (millis > 0) {
                            long left = deadline - System.nanoTime();
                            if (left <= 0) {
                                ready[0] = s.selectNow();
                                return;
                            }
                            wait = Math.max(1, (left + 999_999) / 1_000_000);
                        }
                        int n = s.select(wait);
                        if (Thread.interrupted()) {
                            throw new InterruptedException();
                        }
                        if (n > 0) {
                            ready[0] = n;
                            return;
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, selector);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return ready[0];
    }

    // ---- php_sockop_set_option ----

    /**
     * PHP_STREAM_OPTION_CHECK_LIVENESS with value 0: php-src skips the poll (MSG_DONTWAIT is there on Linux) and
     * peeks a byte; the peer's orderly shutdown (0) or an error other than EAGAIN means dead.
     */
    @Override
    @TruffleBoundary
    boolean alive() {
        if (channel == null) {
            return false;
        }
        return peek() != 0;
    }

    /** recv(MSG_PEEK | MSG_DONTWAIT): 1 if data is there, -1 if nothing is yet, 0 at the end or on an error. */
    final int peek() {
        if (peekedPosition < peeked.length) {
            return 1;
        }
        try {
            if (!finishConnect()) {
                return 0; // ENOTCONN
            }
            ByteBuffer ahead = ByteBuffer.allocate(8192);
            int n = channel.read(ahead);
            if (n > 0) {
                peeked = java.util.Arrays.copyOf(ahead.array(), n);
                peekedPosition = 0;
                return 1;
            }
            return n == 0 ? -1 : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** The read-ahead bytes, taken (for a TLS layer that starts reading the socket itself). */
    final byte[] takePeeked() {
        byte[] rest = java.util.Arrays.copyOfRange(peeked, peekedPosition, peeked.length);
        peeked = new byte[0];
        peekedPosition = 0;
        return rest;
    }

    @Override
    boolean populateMeta(PhpArray meta) {
        meta.put(FileFunctions.key("timed_out"), timeoutEvent);
        meta.put(FileFunctions.key("blocked"), blocked);
        meta.put(FileFunctions.key("eof"), eof);
        return true;
    }

    /** PHP_STREAM_OPTION_BLOCKING: php_set_sock_blocking; the socket itself stays non-blocking (see above). */
    @Override
    int setBlocking(boolean block) {
        int old = blocked ? 1 : 0;
        blocked = block;
        return old;
    }

    /** PHP_STREAM_OPTION_READ_TIMEOUT. */
    @Override
    boolean setReadTimeout(long seconds, long microseconds) {
        timeout = new Network.Timeval(seconds, microseconds);
        timeoutEvent = false;
        return true;
    }

    /** STREAM_XPORT_OP_GET_NAME / GET_PEER_NAME: the address's text, or null. */
    @TruffleBoundary
    String name(boolean peer) {
        if (channel == null) {
            return null;
        }
        try {
            SocketAddress address = peer ? channel.getRemoteAddress() : channel.getLocalAddress();
            return address == null ? null : Network.nameOf(address);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    void rawClose() throws IOException {
        if (selector != null) {
            selector.close();
        }
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }
}
