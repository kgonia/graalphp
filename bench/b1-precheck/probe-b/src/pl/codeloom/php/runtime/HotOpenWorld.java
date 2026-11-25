package pl.codeloom.php.runtime;

import static pl.codeloom.php.runtime.PhpNull.NULL;
import static pl.codeloom.php.runtime.Values.*;

/**
 * Open-world implementations of hot.php kernels: aget, post, and mega.
 * - mega uses MegaGenerated (60 distinct function classes, 20 distinct HotC classes + 60 method classes, 40 distinct closures)
 * - post resolves property writes AND reads through runtime PhpClass metadata
 * - close() explicitly releases kernel-owned row and hotObjects before ctx.shutdown()
 */
final class HotOpenWorld {
    static final PhpArray PALETTE = literal(PhpArray.list(ByteStr.of("color"), ByteStr.of("palette"), ByteStr.of("default")));
    static final PhpArray GRADIENTS = literal(PhpArray.list(ByteStr.of("color"), ByteStr.of("gradients")));
    static final PhpArray LINE_HEIGHT = literal(PhpArray.list(ByteStr.of("typography"), ByteStr.of("lineHeight")));
    static final PhpArray UNITS = literal(PhpArray.list(ByteStr.of("spacing"), ByteStr.of("units")));
    static final PhpArray EMPTY = literal(new PhpArray());

    static final ByteStr HOT_HOOK_PREFIX = ByteStr.of("hot_hook_");
    static final ByteStr METHOD_A = ByteStr.of("a");
    static final ByteStr METHOD_B = ByteStr.of("b");
    static final ByteStr METHOD_S = ByteStr.of("s");

    final PhpRequestContext ctx;
    volatile PhpArray settings;
    final StdClass row;
    final PhpObject[] hotObjects;
    static volatile WPPost postSink;

    HotOpenWorld(PhpRequestContext ctx, boolean overrideMegaCtxB) {
        this.ctx = ctx;
        this.settings = literal(assoc(
                ByteStr.of("color"), assoc(
                        ByteStr.of("palette"), assoc(
                                ByteStr.of("default"), PhpArray.list(assoc(ByteStr.of("slug"), ByteStr.of("black"), ByteStr.of("color"), ByteStr.of("#000"))),
                                ByteStr.of("theme"), new PhpArray()),
                        ByteStr.of("gradients"), NULL,
                        ByteStr.of("custom"), Boolean.TRUE),
                ByteStr.of("typography"), assoc(
                        ByteStr.of("fontSizes"), assoc(ByteStr.of("default"), PhpArray.list(1L, 2L, 3L))),
                ByteStr.of("spacing"), assoc(
                        ByteStr.of("units"), PhpArray.list(ByteStr.of("px"), ByteStr.of("em")))));

        this.row = new StdClass(ctx);
        ByteStr date = ByteStr.of("2026-11-01 10:00:00");
        PhpArray fields = assoc(
                WPPost.K_ID, 1L,
                WPPost.K_POST_AUTHOR, ByteStr.of("1"),
                WPPost.K_POST_DATE, date,
                WPPost.K_POST_DATE_GMT, date,
                WPPost.K_POST_CONTENT, ByteStr.of("<p>Hello world</p>".repeat(20)),
                WPPost.K_POST_TITLE, ByteStr.of("Hello world!"),
                WPPost.K_POST_EXCERPT, ByteStr.EMPTY,
                WPPost.K_POST_STATUS, ByteStr.of("publish"),
                WPPost.K_COMMENT_STATUS, ByteStr.of("open"),
                WPPost.K_PING_STATUS, ByteStr.of("open"),
                WPPost.K_POST_PASSWORD, ByteStr.EMPTY,
                WPPost.K_POST_NAME, ByteStr.of("hello-world"),
                WPPost.K_TO_PING, ByteStr.EMPTY,
                WPPost.K_PINGED, ByteStr.EMPTY,
                WPPost.K_POST_MODIFIED, date,
                WPPost.K_POST_MODIFIED_GMT, date,
                WPPost.K_POST_CONTENT_FILTERED, ByteStr.EMPTY,
                WPPost.K_POST_PARENT, 0L,
                WPPost.K_GUID, ByteStr.of("http://localhost:8080/?p=1"),
                WPPost.K_MENU_ORDER, 0L,
                WPPost.K_POST_TYPE, ByteStr.of("post"),
                WPPost.K_POST_MIME_TYPE, ByteStr.EMPTY,
                WPPost.K_COMMENT_COUNT, ByteStr.of("1"),
                WPPost.K_FILTER, ByteStr.of("raw"));
        for (int p = fields.firstPosition(); p >= 0; p = fields.nextPosition(p)) {
            row.set((ByteStr) fields.keyAt(p), fields.valueAt(p));
        }

        // 160 distinct JVM targets (60 functions, 20 HotC classes + 60 methods, 40 closures)
        this.hotObjects = MegaGenerated.registerAndCreate(ctx, overrideMegaCtxB);
    }

    /** MEDIUM-2 fix: explicitly release kernel-owned row and hotObjects before context shutdown. */
    void close() {
        row.release();
        for (int i = 0; i < hotObjects.length; i++) {
            if (hotObjects[i] != null) {
                hotObjects[i].release();
                hotObjects[i] = null;
            }
        }
    }

    static Object arrayGet(PhpRequestContext ctx, Object input, Object pathArg, Object fallback) {
        if (!(pathArg instanceof PhpArray path) || path.size() == 0) return fallback;
        for (int p = path.firstPosition(); p >= 0; p = path.nextPosition(p)) {
            if (!(input instanceof PhpArray a)) return fallback;
            Object key = path.valueAt(p);
            if (key instanceof ByteStr s) key = ArrayKey.ofString(s);
            else if (!(key instanceof Long)) return fallback;
            Object value = a.get(key);
            if (isset(value)) {
                input = value;
                continue;
            }
            if (a.containsKey(key)) {
                input = a.get(key);
                continue;
            }
            return fallback;
        }
        return input;
    }

    long aget() {
        PhpRequestContext c = this.ctx;
        PhpArray input = settings;
        long v = 0;
        for (int i = 0; i < 40; i++) {
            v += ((PhpArray) arrayGet(c, input, PALETTE, EMPTY)).size();
            Object gradients = arrayGet(c, input, GRADIENTS, 5L);
            v += gradients == NULL ? 1L : (long) gradients;
            v += (long) arrayGet(c, input, LINE_HEIGHT, 2L);
            v += ((PhpArray) arrayGet(c, input, UNITS, NULL)).size();
        }
        return v;
    }

    long post() {
        PhpRequestContext c = this.ctx;
        long v = 0;
        for (int i = 0; i < 40; i++) {
            WPPost post = new WPPost(c, row);
            // Resolve property reads via runtime PhpClass metadata (zero direct Java-field shortcut)
            v += (long) post.readProp(WPPost.K_ID) + ((ByteStr) post.readProp(WPPost.K_POST_TITLE)).length();
            postSink = post;
            post.release();
        }
        return v;
    }

    long mega() {
        PhpRequestContext c = this.ctx;
        long v = 0;
        for (int h = 0; h < 40; h++) {
            ByteStr hookName = HOT_HOOK_PREFIX.concat(ByteStr.ofLong(h));
            v = (long) HooksOpenWorld.applyFilters(c, hookName, v, 3L);
        }
        return v;
    }
}
