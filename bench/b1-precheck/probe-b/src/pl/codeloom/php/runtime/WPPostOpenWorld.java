package pl.codeloom.php.runtime;

import static pl.codeloom.php.runtime.Values.*;

/**
 * Open-world stdClass and WP_Post extending real D17 PhpObject:
 * - Resolves variable property writes ($this->$key = $value) and property reads through
 *   runtime PhpClass metadata (phpClass.findPropertySlot(key), mirroring zend_class_entry.properties_info)
 * - Zero hardcoded 24-branch linear if-chain, zero direct Java-field shortcut on timed reads
 * - Full D17 refcounting on all 24 declared fields + dynamic overflow
 */
final class StdClass extends PhpObject {
    private PhpArray props = owned(new PhpArray());

    StdClass(PhpRequestContext ctx) {
        super(ctx, ctx.stdClassMeta);
    }

    void set(ByteStr name, Object value) {
        props = writable(props);
        props.put(name, value);
    }

    @Override
    Object readProp(ByteStr name) {
        Object v = props.get(name);
        return v == null ? PhpNull.NULL : v;
    }

    @Override
    void writeProp(ByteStr name, Object value) {
        set(name, value);
    }

    PhpArray getObjectVars() {
        for (int p = props.firstPosition(); p >= 0; p = props.nextPosition(p)) {
            ByteStr key = (ByteStr) props.keyAt(p);
            if (ArrayKey.ofString(key) instanceof Long) {
                PhpArray result = new PhpArray();
                for (int i = props.firstPosition(); i >= 0; i = props.nextPosition(i)) {
                    Object k = ArrayKey.ofString((ByteStr) props.keyAt(i));
                    result.putCopied(k, props.copiedAt(i));
                }
                return owned(result);
            }
        }
        return owned(props);
    }

    @Override
    void freeProperties() {
        props.removeHolder();
    }

    @Override
    void visitChildren(ChildVisitor visitor) {
        visitor.visit(props);
    }
}

final class WPPost extends PhpObject {
    static final ByteStr K_ID = ByteStr.of("ID");
    static final ByteStr K_POST_AUTHOR = ByteStr.of("post_author");
    static final ByteStr K_POST_DATE = ByteStr.of("post_date");
    static final ByteStr K_POST_DATE_GMT = ByteStr.of("post_date_gmt");
    static final ByteStr K_POST_CONTENT = ByteStr.of("post_content");
    static final ByteStr K_POST_TITLE = ByteStr.of("post_title");
    static final ByteStr K_POST_EXCERPT = ByteStr.of("post_excerpt");
    static final ByteStr K_POST_STATUS = ByteStr.of("post_status");
    static final ByteStr K_COMMENT_STATUS = ByteStr.of("comment_status");
    static final ByteStr K_PING_STATUS = ByteStr.of("ping_status");
    static final ByteStr K_POST_PASSWORD = ByteStr.of("post_password");
    static final ByteStr K_POST_NAME = ByteStr.of("post_name");
    static final ByteStr K_TO_PING = ByteStr.of("to_ping");
    static final ByteStr K_PINGED = ByteStr.of("pinged");
    static final ByteStr K_POST_MODIFIED = ByteStr.of("post_modified");
    static final ByteStr K_POST_MODIFIED_GMT = ByteStr.of("post_modified_gmt");
    static final ByteStr K_POST_CONTENT_FILTERED = ByteStr.of("post_content_filtered");
    static final ByteStr K_POST_PARENT = ByteStr.of("post_parent");
    static final ByteStr K_GUID = ByteStr.of("guid");
    static final ByteStr K_MENU_ORDER = ByteStr.of("menu_order");
    static final ByteStr K_POST_TYPE = ByteStr.of("post_type");
    static final ByteStr K_POST_MIME_TYPE = ByteStr.of("post_mime_type");
    static final ByteStr K_COMMENT_COUNT = ByteStr.of("comment_count");
    static final ByteStr K_FILTER = ByteStr.of("filter");

    static final ByteStr[] DECLARED_NAMES = {
            K_ID, K_POST_AUTHOR, K_POST_DATE, K_POST_DATE_GMT, K_POST_CONTENT, K_POST_TITLE,
            K_POST_EXCERPT, K_POST_STATUS, K_COMMENT_STATUS, K_PING_STATUS, K_POST_PASSWORD, K_POST_NAME,
            K_TO_PING, K_PINGED, K_POST_MODIFIED, K_POST_MODIFIED_GMT, K_POST_CONTENT_FILTERED,
            K_POST_PARENT, K_GUID, K_MENU_ORDER, K_POST_TYPE, K_POST_MIME_TYPE, K_COMMENT_COUNT, K_FILTER
    };

    private static final ByteStr ZERO_STR = ByteStr.of("0");
    private static final ByteStr ZERO_DATE = ByteStr.of("0000-00-00 00:00:00");
    private static final ByteStr PUBLISH = ByteStr.of("publish");
    private static final ByteStr OPEN = ByteStr.of("open");
    private static final ByteStr POST_TYPE = ByteStr.of("post");

    static PhpClass registerClass(PhpRequestContext ctx) {
        PhpClass cls = ctx.defineClass(ByteStr.of("WP_Post"));
        for (int i = 0; i < DECLARED_NAMES.length; i++) {
            cls.defineProperty(DECLARED_NAMES[i], i);
        }
        return cls;
    }

    Object ID = PhpNull.NULL;
    Object post_author = ZERO_STR;
    Object post_date = ZERO_DATE;
    Object post_date_gmt = ZERO_DATE;
    Object post_content = ByteStr.EMPTY;
    Object post_title = ByteStr.EMPTY;
    Object post_excerpt = ByteStr.EMPTY;
    Object post_status = PUBLISH;
    Object comment_status = OPEN;
    Object ping_status = OPEN;
    Object post_password = ByteStr.EMPTY;
    Object post_name = ByteStr.EMPTY;
    Object to_ping = ByteStr.EMPTY;
    Object pinged = ByteStr.EMPTY;
    Object post_modified = ZERO_DATE;
    Object post_modified_gmt = ZERO_DATE;
    Object post_content_filtered = ByteStr.EMPTY;
    Object post_parent = 0L;
    Object guid = ByteStr.EMPTY;
    Object menu_order = 0L;
    Object post_type = POST_TYPE;
    Object post_mime_type = ByteStr.EMPTY;
    Object comment_count = ZERO_STR;
    Object filter = PhpNull.NULL;
    PhpArray dynamic;
    private boolean mayHoldCountedFields;

    WPPost(PhpRequestContext ctx, StdClass row) {
        super(ctx, ctx.wpPostClass);
        PhpArray vars = row.getObjectVars();
        for (int p = vars.firstPosition(); p >= 0; p = vars.nextPosition(p)) {
            Object key = vars.keyAt(p);
            ByteStr keyStr = key instanceof ByteStr bs ? bs : ByteStr.ofLong((Long) key);
            writeProp(keyStr, vars.valueAt(p));
        }
        vars.removeHolder();
    }

    private Object retainField(Object value, Object old) {
        if (value != old && (value instanceof PhpArray || value instanceof PhpObject
                || value instanceof PhpClosure || value instanceof PhpRef)) {
            retainValue(value);
            mayHoldCountedFields = true;
        }
        return value;
    }

    @Override
    void freeProperties() {
        if (mayHoldCountedFields) {
            releaseValue(ID); releaseValue(post_author); releaseValue(post_date); releaseValue(post_date_gmt);
            releaseValue(post_content); releaseValue(post_title); releaseValue(post_excerpt); releaseValue(post_status);
            releaseValue(comment_status); releaseValue(ping_status); releaseValue(post_password); releaseValue(post_name);
            releaseValue(to_ping); releaseValue(pinged); releaseValue(post_modified); releaseValue(post_modified_gmt);
            releaseValue(post_content_filtered); releaseValue(post_parent); releaseValue(guid); releaseValue(menu_order);
            releaseValue(post_type); releaseValue(post_mime_type); releaseValue(comment_count); releaseValue(filter);
            mayHoldCountedFields = false;
        }
        if (dynamic != null) {
            dynamic.removeHolder();
            dynamic = null;
        }
    }

    @Override
    void visitChildren(ChildVisitor visitor) {
        if (mayHoldCountedFields) {
            visitor.visit(ID); visitor.visit(post_author); visitor.visit(post_date); visitor.visit(post_date_gmt);
            visitor.visit(post_content); visitor.visit(post_title); visitor.visit(post_excerpt); visitor.visit(post_status);
            visitor.visit(comment_status); visitor.visit(ping_status); visitor.visit(post_password); visitor.visit(post_name);
            visitor.visit(to_ping); visitor.visit(pinged); visitor.visit(post_modified); visitor.visit(post_modified_gmt);
            visitor.visit(post_content_filtered); visitor.visit(post_parent); visitor.visit(guid); visitor.visit(menu_order);
            visitor.visit(post_type); visitor.visit(post_mime_type); visitor.visit(comment_count); visitor.visit(filter);
        }
        if (dynamic != null) visitor.visit(dynamic);
    }

    /** Open-world dynamic property read resolved through runtime PhpClass property metadata. */
    @Override
    Object readProp(ByteStr key) {
        int slot = phpClass.findPropertySlot(key);
        return switch (slot) {
            case 0 -> ID;
            case 1 -> post_author;
            case 2 -> post_date;
            case 3 -> post_date_gmt;
            case 4 -> post_content;
            case 5 -> post_title;
            case 6 -> post_excerpt;
            case 7 -> post_status;
            case 8 -> comment_status;
            case 9 -> ping_status;
            case 10 -> post_password;
            case 11 -> post_name;
            case 12 -> to_ping;
            case 13 -> pinged;
            case 14 -> post_modified;
            case 15 -> post_modified_gmt;
            case 16 -> post_content_filtered;
            case 17 -> post_parent;
            case 18 -> guid;
            case 19 -> menu_order;
            case 20 -> post_type;
            case 21 -> post_mime_type;
            case 22 -> comment_count;
            case 23 -> filter;
            default -> {
                if (dynamic != null) {
                    Object v = dynamic.get(key);
                    yield v == null ? PhpNull.NULL : v;
                }
                yield PhpNull.NULL;
            }
        };
    }

    /** Open-world variable property write ($this->$key = $value) resolved through runtime PhpClass metadata. */
    @Override
    void writeProp(ByteStr key, Object value) {
        int slot = phpClass.findPropertySlot(key);
        Object old;
        switch (slot) {
            case 0 -> { old = ID; ID = retainField(value, old); }
            case 1 -> { old = post_author; post_author = retainField(value, old); }
            case 2 -> { old = post_date; post_date = retainField(value, old); }
            case 3 -> { old = post_date_gmt; post_date_gmt = retainField(value, old); }
            case 4 -> { old = post_content; post_content = retainField(value, old); }
            case 5 -> { old = post_title; post_title = retainField(value, old); }
            case 6 -> { old = post_excerpt; post_excerpt = retainField(value, old); }
            case 7 -> { old = post_status; post_status = retainField(value, old); }
            case 8 -> { old = comment_status; comment_status = retainField(value, old); }
            case 9 -> { old = ping_status; ping_status = retainField(value, old); }
            case 10 -> { old = post_password; post_password = retainField(value, old); }
            case 11 -> { old = post_name; post_name = retainField(value, old); }
            case 12 -> { old = to_ping; to_ping = retainField(value, old); }
            case 13 -> { old = pinged; pinged = retainField(value, old); }
            case 14 -> { old = post_modified; post_modified = retainField(value, old); }
            case 15 -> { old = post_modified_gmt; post_modified_gmt = retainField(value, old); }
            case 16 -> { old = post_content_filtered; post_content_filtered = retainField(value, old); }
            case 17 -> { old = post_parent; post_parent = retainField(value, old); }
            case 18 -> { old = guid; guid = retainField(value, old); }
            case 19 -> { old = menu_order; menu_order = retainField(value, old); }
            case 20 -> { old = post_type; post_type = retainField(value, old); }
            case 21 -> { old = post_mime_type; post_mime_type = retainField(value, old); }
            case 22 -> { old = comment_count; comment_count = retainField(value, old); }
            case 23 -> { old = filter; filter = retainField(value, old); }
            default -> {
                if (dynamic == null) dynamic = owned(new PhpArray());
                dynamic.put(key, value);
                return;
            }
        }
        // Reentrant destructors read the published field and may replace it again.
        if (old != value) releaseValue(old);
    }

    void set(ByteStr key, Object value) {
        writeProp(key, value);
    }
}
