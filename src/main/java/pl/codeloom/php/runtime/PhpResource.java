package pl.codeloom.php.runtime;

/**
 * A PHP resource: a stream or directory handle. The handle is the built-in's own state; type is what
 * get_resource_type() reports ("stream"), and a closed resource reports "Unknown".
 */
public final class PhpResource {
    private final int id;
    private final String type;
    private Object handle;

    public PhpResource(int id, String type, Object handle) {
        this.id = id;
        this.type = type;
        this.handle = handle;
    }

    public int getId() {
        return id;
    }

    public String getType() {
        return handle == null ? "Unknown" : type;
    }

    public Object getHandle() {
        return handle;
    }

    public boolean isClosed() {
        return handle == null;
    }

    public void close() {
        handle = null;
    }
}
