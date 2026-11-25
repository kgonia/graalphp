package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic probe: walks the post-bootstrap PHP object graph at wp_loaded / rest_api_init,
 * tracks all post-barrier mutations (both transient and persistent) with an independent
 * end-of-request structural diff cross-check, and logs pre-barrier taint reads (superglobals, SQL, time).
 */
public final class BootstrapCensus {
    public static final String OUT_DIR = System.getProperty("php.bootstrap.census");
    public static final boolean ENABLED = OUT_DIR != null && !OUT_DIR.isEmpty();

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final Map<PhpContext, Session> BY_CTX = new IdentityHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private BootstrapCensus() {
    }

    private static final class ObjSnap {
        final String className;
        final String[] keys;
        final Object[] values;
        final Object internal;
        final boolean hasDestructor;
        final boolean lazy;
        final boolean hasHooks;
        final long estBytes;

        ObjSnap(String className, String[] keys, Object[] values, Object internal,
                boolean hasDestructor, boolean lazy, boolean hasHooks, long estBytes) {
            this.className = className;
            this.keys = keys;
            this.values = values;
            this.internal = internal;
            this.hasDestructor = hasDestructor;
            this.lazy = lazy;
            this.hasHooks = hasHooks;
            this.estBytes = estBytes;
        }
    }

    private static final class ArrSnap {
        final int size;
        final int pointer;
        final long nextIndex;
        final String storeKind;
        final long estBytes;
        final Object[] keys;
        final Object[] values;

        ArrSnap(int size, int pointer, long nextIndex, String storeKind, long estBytes, Object[] keys, Object[] values) {
            this.size = size;
            this.pointer = pointer;
            this.nextIndex = nextIndex;
            this.storeKind = storeKind;
            this.estBytes = estBytes;
            this.keys = keys;
            this.values = values;
        }
    }

    private static final class RootBucketStats {
        long firstTouchBytes;
        int firstTouchNodes;
        long exclusiveBytes;
        int exclusiveNodes;
        long totalReachableBytes;
        int totalReachableNodes;
    }

    private static final class Session {
        final int seq = SEQ.incrementAndGet();
        final PhpContext ctx;
        final long startNanos = System.nanoTime();
        String page = "unknown";
        String barrier = "";
        boolean barrierTaken;
        boolean afterBarrier;
        boolean inCensus;

        // Pre-barrier taint tracking
        final IdentityHashMap<PhpArray, String> superglobals = new IdentityHashMap<>();
        final Map<String, Integer> superglobalReads = new LinkedHashMap<>();
        final Map<String, Set<String>> superglobalReadSites = new LinkedHashMap<>();
        final List<String> preSqlQueries = new ArrayList<>();
        final Map<String, Integer> preNonDetCalls = new LinkedHashMap<>();

        // Barrier snapshot
        long barrierNanos;
        final IdentityHashMap<PhpObject, ObjSnap> preObjects = new IdentityHashMap<>();
        final IdentityHashMap<PhpArray, ArrSnap> preArrays = new IdentityHashMap<>();
        final IdentityHashMap<PhpRef, Object> preRefs = new IdentityHashMap<>();
        final IdentityHashMap<PhpRef, String> preRefLabels = new IdentityHashMap<>();
        final IdentityHashMap<PhpClosure, Long> preClosures = new IdentityHashMap<>();
        final IdentityHashMap<TruffleString, Long> preStrings = new IdentityHashMap<>();
        final IdentityHashMap<byte[], Boolean> preStringBacking = new IdentityHashMap<>();
        long preStringBackingBytes;
        long preScalarBoxCount;

        // Symbol tables at barrier
        Set<String> preIncluded = Set.of();
        Set<String> preClasses = Set.of();
        Set<String> preFunctions = Set.of();
        Set<String> preConstants = Set.of();
        int preGlobalCount;
        int preStaticVarCount;

        // Class & special stats at barrier
        final Map<String, Integer> classCounts = new HashMap<>();
        final Map<String, Long> classShallowBytes = new HashMap<>();
        final Map<String, Integer> classPropTotals = new HashMap<>();
        final Map<String, Integer> internalClassCounts = new LinkedHashMap<>();
        final Map<String, Integer> dtorClassCounts = new LinkedHashMap<>();
        int lazyCount;
        int hookedCount;
        final Map<String, Integer> arrKindCounts = new LinkedHashMap<>();
        final Map<String, Long> arrKindBytes = new LinkedHashMap<>();
        long arrTotalElements;

        // Root attribution & fingerprints
        final Map<String, RootBucketStats> rootStats = new LinkedHashMap<>();
        final Map<String, String> rootFingerprints = new LinkedHashMap<>();

        // Post-barrier write tracking
        final IdentityHashMap<PhpObject, Set<String>> dirtyObjProps = new IdentityHashMap<>();
        final Map<String, Long> objPropWriteCounts = new HashMap<>();
        final IdentityHashMap<PhpArray, Boolean> dirtyArrContent = new IdentityHashMap<>();
        final IdentityHashMap<PhpArray, Boolean> dirtyArrPointer = new IdentityHashMap<>();
        final IdentityHashMap<PhpArray, Integer> copiedPreArrays = new IdentityHashMap<>();
        final IdentityHashMap<PhpRef, Integer> dirtyRefs = new IdentityHashMap<>();
        long totalObjWriteEvents;
        long totalArrContentWriteEvents;
        long totalArrPointerWriteEvents;
        long totalArrCopyEvents;
        long totalRefWriteEvents;

        Session(PhpContext ctx) {
            this.ctx = ctx;
        }
    }

    @TruffleBoundary
    public static void onContextStart(PhpContext ctx) {
        if (!ENABLED) {
            return;
        }
        Session s = new Session(ctx);
        for (String name : new String[]{"_SERVER", "_COOKIE", "_GET", "_POST", "_REQUEST"}) {
            PhpRef ref = ctx.findGlobal(name);
            if (ref != null && ref.get() instanceof PhpArray arr) {
                s.superglobals.put(arr, "$" + name);
            }
        }
        synchronized (BY_CTX) {
            BY_CTX.put(ctx, s);
        }
        CURRENT.set(s);
    }

    @TruffleBoundary
    public static void onArrayRead(PhpArray array, Object key) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || s.afterBarrier || s.inCensus) {
            return;
        }
        String sg = s.superglobals.get(array);
        if (sg == null) {
            return;
        }
        s.inCensus = true;
        try {
            String keyStr = formatKey(key);
            String fullKey = sg + "[" + keyStr + "]";
            s.superglobalReads.merge(fullKey, 1, Integer::sum);
            String loc = shortLoc();
            s.superglobalReadSites.computeIfAbsent(fullKey, k -> new LinkedHashSet<>());
            Set<String> sites = s.superglobalReadSites.get(fullKey);
            if (sites.size() < 8) {
                sites.add(loc);
            }
        } finally {
            s.inCensus = false;
        }
    }

    @TruffleBoundary
    public static void onSql(byte[] sqlBytes) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || s.afterBarrier || s.inCensus) {
            return;
        }
        String sql = new String(sqlBytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        if (sql.length() > 160) {
            sql = sql.substring(0, 160) + "...";
        }
        s.preSqlQueries.add(sql);
    }

    @TruffleBoundary
    public static void onNonDet(String builtin) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || s.afterBarrier || s.inCensus) {
            return;
        }
        s.inCensus = true;
        try {
            String key = builtin + " @ " + shortLoc();
            s.preNonDetCalls.merge(key, 1, Integer::sum);
        } finally {
            s.inCensus = false;
        }
    }

    @TruffleBoundary
    public static void onObjectWrite(PhpObject obj, String propName) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || !s.afterBarrier || s.inCensus || obj == null) {
            return;
        }
        ObjSnap snap = s.preObjects.get(obj);
        if (snap == null) {
            return;
        }
        s.totalObjWriteEvents++;
        Set<String> props = s.dirtyObjProps.get(obj);
        if (props == null) {
            props = new HashSet<>();
            s.dirtyObjProps.put(obj, props);
        }
        String p = propName == null ? "<internal>" : propName;
        props.add(p);
        s.objPropWriteCounts.merge(snap.className + "::$" + p, 1L, Long::sum);
    }

    @TruffleBoundary
    public static void onArrayWrite(PhpArray arr, boolean pointerOnly) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || !s.afterBarrier || s.inCensus || arr == null) {
            return;
        }
        if (!s.preArrays.containsKey(arr)) {
            return;
        }
        if (pointerOnly) {
            s.totalArrPointerWriteEvents++;
            s.dirtyArrPointer.put(arr, Boolean.TRUE);
        } else {
            s.totalArrContentWriteEvents++;
            s.dirtyArrContent.put(arr, Boolean.TRUE);
        }
    }

    @TruffleBoundary
    public static void onArrayCopy(PhpArray arr) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || !s.afterBarrier || s.inCensus || arr == null) {
            return;
        }
        if (!s.preArrays.containsKey(arr)) {
            return;
        }
        s.totalArrCopyEvents++;
        s.copiedPreArrays.merge(arr, 1, Integer::sum);
    }

    @TruffleBoundary
    public static void onRefWrite(PhpRef ref) {
        if (!ENABLED) {
            return;
        }
        Session s = CURRENT.get();
        if (s == null || !s.afterBarrier || s.inCensus || ref == null) {
            return;
        }
        if (!s.preRefs.containsKey(ref)) {
            return;
        }
        s.totalRefWriteEvents++;
        s.dirtyRefs.merge(ref, 1, Integer::sum);
    }

    @TruffleBoundary
    public static void onBarrier(PhpContext ctx, String barrier, String page) {
        if (!ENABLED) {
            return;
        }
        Session s = getSession(ctx);
        if (s == null || s.barrierTaken) {
            return;
        }
        s.inCensus = true;
        try {
            s.barrierTaken = true;
            s.barrier = barrier;
            s.page = (page == null || page.isEmpty()) ? ("req-" + s.seq) : page;
            s.barrierNanos = System.nanoTime();

            // Refresh superglobal identity map in case WordPress replaced one
            s.superglobals.clear();

            // Record symbol table sizes at barrier
            s.preIncluded = ctx.censusIncludedSnapshot();
            s.preClasses = ctx.censusClassSnapshot();
            s.preFunctions = ctx.censusFunctionSnapshot();
            s.preConstants = ctx.censusConstantSnapshot();
            s.preGlobalCount = ctx.censusGlobalCount();
            s.preStaticVarCount = ctx.censusStaticVarCount();

            // Collect ordered top-level roots
            List<Map.Entry<String, Object>> roots = new ArrayList<>();
            ctx.censusRoots((label, value) -> {
                if (!"$GLOBALS".equals(label)) {
                    roots.add(Map.entry(label, value));
                }
            });
            ctx.censusHolders((label, value) -> roots.add(Map.entry("holder:" + label, value)));

            // Global BFS: discover all reachable values + attribute first-touch bucket
            IdentityHashMap<Object, String> firstOwner = new IdentityHashMap<>();
            IdentityHashMap<Object, Long> nodeBytes = new IdentityHashMap<>();
            ArrayDeque<Object> queue = new ArrayDeque<>();

            for (Map.Entry<String, Object> entry : roots) {
                String label = entry.getKey();
                Object val = entry.getValue();
                String bucket = bucketName(label);
                if (val instanceof PhpRef ref) {
                    s.preRefLabels.putIfAbsent(ref, label);
                }
                enqueue(s, val, bucket, firstOwner, nodeBytes, queue);
                drain(s, bucket, firstOwner, nodeBytes, queue);
            }

            // Aggregate first-touch stats per bucket
            for (Map.Entry<Object, String> e : firstOwner.entrySet()) {
                String bucket = e.getValue();
                long b = nodeBytes.getOrDefault(e.getKey(), 0L);
                RootBucketStats st = s.rootStats.computeIfAbsent(bucket, k -> new RootBucketStats());
                st.firstTouchNodes++;
                st.firstTouchBytes += b;
            }

            // Compute multi-root reachability (exclusive vs shared) & total reachable per bucket
            Map<String, List<Object>> bucketRoots = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : roots) {
                bucketRoots.computeIfAbsent(bucketName(entry.getKey()), k -> new ArrayList<>()).add(entry.getValue());
            }
            IdentityHashMap<Object, Integer> rootReachCount = new IdentityHashMap<>();
            IdentityHashMap<Object, String> singleOwner = new IdentityHashMap<>();

            for (Map.Entry<String, List<Object>> be : bucketRoots.entrySet()) {
                String bucket = be.getKey();
                IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
                ArrayDeque<Object> q = new ArrayDeque<>();
                for (Object v : be.getValue()) {
                    if (isTrackableNode(v) && visited.put(v, Boolean.TRUE) == null) {
                        q.add(v);
                    }
                }
                long reachBytes = 0;
                int reachNodes = 0;
                while (!q.isEmpty()) {
                    Object cur = q.poll();
                    reachNodes++;
                    reachBytes += nodeBytes.getOrDefault(cur, 0L);
                    int prev = rootReachCount.getOrDefault(cur, 0);
                    rootReachCount.put(cur, prev + 1);
                    if (prev == 0) {
                        singleOwner.put(cur, bucket);
                    }
                    visitChildren(cur, child -> {
                        if (isTrackableNode(child) && visited.put(child, Boolean.TRUE) == null) {
                            q.add(child);
                        }
                    });
                }
                RootBucketStats st = s.rootStats.computeIfAbsent(bucket, k -> new RootBucketStats());
                st.totalReachableNodes = reachNodes;
                st.totalReachableBytes = reachBytes;
            }

            for (Map.Entry<Object, Integer> e : rootReachCount.entrySet()) {
                if (e.getValue() == 1) {
                    String owner = singleOwner.get(e.getKey());
                    RootBucketStats st = s.rootStats.get(owner);
                    if (st != null) {
                        st.exclusiveNodes++;
                        st.exclusiveBytes += nodeBytes.getOrDefault(e.getKey(), 0L);
                    }
                }
            }

            // Cross-request structural fingerprints for top-level roots
            for (Map.Entry<String, Object> entry : roots) {
                String label = entry.getKey();
                if (label.startsWith("holder:")) {
                    continue;
                }
                IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
                long h = fingerprint(entry.getValue(), 0, seen);
                s.rootFingerprints.put(label, Long.toHexString(h) + "|" + shortDesc(entry.getValue()));
            }

            s.afterBarrier = true;
        } finally {
            s.inCensus = false;
        }
    }

    @TruffleBoundary
    public static void onContextEnd(PhpContext ctx) {
        if (!ENABLED) {
            return;
        }
        Session s;
        synchronized (BY_CTX) {
            s = BY_CTX.remove(ctx);
        }
        CURRENT.remove();
        if (s == null || !s.barrierTaken) {
            return;
        }
        s.inCensus = true;
        try {
            finishAndWrite(s);
        } catch (IOException e) {
            System.err.println("BootstrapCensus write failed: " + e);
        } finally {
            s.inCensus = false;
        }
    }

    private static Session getSession(PhpContext ctx) {
        Session s = CURRENT.get();
        if (s != null && s.ctx == ctx) {
            return s;
        }
        synchronized (BY_CTX) {
            return BY_CTX.get(ctx);
        }
    }

    private static boolean isTrackableNode(Object v) {
        return v instanceof PhpObject || v instanceof PhpArray || v instanceof PhpClosure
                || v instanceof PhpRef || v instanceof TruffleString;
    }

    private static void enqueue(Session s, Object v, String bucket,
                                IdentityHashMap<Object, String> firstOwner,
                                IdentityHashMap<Object, Long> nodeBytes,
                                ArrayDeque<Object> queue) {
        if (v == null || v == PhpNull.NULL || v instanceof Boolean) {
            return;
        }
        if (v instanceof Long || v instanceof Double) {
            s.preScalarBoxCount++;
            return;
        }
        if (!isTrackableNode(v)) {
            return;
        }
        if (firstOwner.putIfAbsent(v, bucket) != null) {
            return;
        }
        long bytes = recordNode(s, v);
        nodeBytes.put(v, bytes);
        queue.add(v);
    }

    private static void drain(Session s, String bucket,
                              IdentityHashMap<Object, String> firstOwner,
                              IdentityHashMap<Object, Long> nodeBytes,
                              ArrayDeque<Object> queue) {
        while (!queue.isEmpty()) {
            Object cur = queue.poll();
            visitChildren(cur, child -> enqueue(s, child, bucket, firstOwner, nodeBytes, queue));
        }
    }

    private static void visitChildren(Object cur, java.util.function.Consumer<Object> out) {
        if (cur instanceof PhpRef ref) {
            out.accept(ref.get());
        } else if (cur instanceof PhpArray arr) {
            for (int p = arr.firstPosition(); p >= 0; p = arr.nextPosition(p)) {
                out.accept(arr.keyAt(p));
                out.accept(arr.rawAt(p));
            }
        } else if (cur instanceof PhpObject obj) {
            if (obj.lazy() == null) {
                Object[] names = obj.propertyNames();
                for (Object n : names) {
                    if (n instanceof String key) {
                        out.accept(obj.getProperty(key));
                    }
                }
            }
        } else if (cur instanceof PhpClosure cl) {
            out.accept(cl.getBoundThis());
            for (int i = 0; i < cl.getCaptureCount(); i++) {
                out.accept(cl.getCaptured(i));
            }
            if (cl.hasStatics()) {
                for (PhpRef r : cl.staticRefs()) {
                    out.accept(r);
                }
            }
        }
    }

    private static long recordNode(Session s, Object v) {
        if (v instanceof PhpObject obj) {
            PhpClass cls = obj.getPhpClass();
            String clsName = cls.getName();
            boolean lazy = obj.lazy() != null;
            boolean hasDtor = cls.findMethod("__destruct") != null;
            boolean hasHooks = cls.hasHooks();
            String[] keys;
            Object[] vals;
            if (lazy) {
                keys = new String[0];
                vals = new Object[0];
                s.lazyCount++;
            } else {
                Object[] rawNames = obj.propertyNames();
                List<String> kList = new ArrayList<>(rawNames.length);
                for (Object n : rawNames) {
                    if (n instanceof String k) {
                        kList.add(k);
                    }
                }
                keys = kList.toArray(new String[0]);
                vals = new Object[keys.length];
                for (int i = 0; i < keys.length; i++) {
                    vals[i] = obj.getProperty(keys[i]);
                }
            }
            // HotSpot CompressedOops estimate: DynamicObject subclass (~48B) + overflow Object[] if >3 props
            long est = 48L + (keys.length > 3 ? align8(16L + 4L * keys.length) : 0L);
            Object internal = obj.getInternal();
            if (internal != null) {
                String ik = clsName + " (" + internal.getClass().getSimpleName() + ")";
                s.internalClassCounts.merge(ik, 1, Integer::sum);
            }
            if (hasDtor) {
                s.dtorClassCounts.merge(clsName, 1, Integer::sum);
            }
            if (hasHooks) {
                s.hookedCount++;
            }
            s.classCounts.merge(clsName, 1, Integer::sum);
            s.classShallowBytes.merge(clsName, est, Long::sum);
            s.classPropTotals.merge(clsName, keys.length, Integer::sum);
            s.preObjects.put(obj, new ObjSnap(clsName, keys, vals, internal, hasDtor, lazy, hasHooks, est));
            return est;
        }
        if (v instanceof PhpArray arr) {
            int sz = arr.size();
            Object[] keys = new Object[sz];
            Object[] vals = new Object[sz];
            int idx = 0;
            for (int p = arr.firstPosition(); p >= 0 && idx < sz; p = arr.nextPosition(p)) {
                keys[idx] = arr.keyAt(p);
                vals[idx] = arr.rawAt(p);
                idx++;
            }
            if (idx != sz) {
                keys = Arrays.copyOf(keys, idx);
                vals = Arrays.copyOf(vals, idx);
            }
            String kind = arr.censusStoreKind();
            long est = arr.censusEstimateBytes();
            s.arrKindCounts.merge(kind, 1, Integer::sum);
            s.arrKindBytes.merge(kind, est, Long::sum);
            s.arrTotalElements += sz;
            s.preArrays.put(arr, new ArrSnap(sz, arr.pointer(), arr.nextIndex(), kind, est, keys, vals));
            return est;
        }
        if (v instanceof PhpRef ref) {
            s.preRefs.put(ref, ref.get());
            return 32L;
        }
        if (v instanceof PhpClosure cl) {
            long est = 56L + align8(16L + 4L * cl.getCaptureCount()) + (cl.hasStatics() ? 48L : 0L);
            s.preClosures.put(cl, est);
            return est;
        }
        if (v instanceof TruffleString ts) {
            long est = 32L;
            InternalByteArray iba = ts.getInternalByteArrayUncached(Str.ENC);
            byte[] backing = iba.getArray();
            if (backing != null && s.preStringBacking.put(backing, Boolean.TRUE) == null) {
                long bBytes = align8(16L + backing.length);
                s.preStringBackingBytes += bBytes;
                est += bBytes;
            }
            s.preStrings.put(ts, est);
            return est;
        }
        return 0L;
    }

    private static void finishAndWrite(Session s) throws IOException {
        long endNanos = System.nanoTime();
        PhpContext ctx = s.ctx;

        // 1. Cross-check PhpObjects
        int objUntouched = 0;
        int objTransient = 0;
        int objPersistent = 0;
        int missedObjWrites = 0;
        long objUntouchedBytes = 0;
        long objMutatedBytes = 0;
        Map<String, Integer> mutatedClassTotal = new HashMap<>();
        Map<String, Integer> mutatedClassPersistent = new HashMap<>();
        Map<String, Integer> mutatedClassTransient = new HashMap<>();
        Map<String, Integer> persistentPropDiffCounts = new HashMap<>();
        List<String> missedObjSamples = new ArrayList<>();

        for (Map.Entry<PhpObject, ObjSnap> entry : s.preObjects.entrySet()) {
            PhpObject obj = entry.getKey();
            ObjSnap snap = entry.getValue();
            Set<String> trackedProps = s.dirtyObjProps.get(obj);
            boolean wasTracked = trackedProps != null && !trackedProps.isEmpty();

            List<String> changedProps = diffObject(obj, snap);
            boolean diffChanged = !changedProps.isEmpty();

            if (diffChanged && !wasTracked) {
                missedObjWrites++;
                if (missedObjSamples.size() < 20) {
                    missedObjSamples.add(snap.className + "::$" + String.join(",", changedProps));
                }
            }
            if (diffChanged) {
                objPersistent++;
                objMutatedBytes += snap.estBytes;
                mutatedClassTotal.merge(snap.className, 1, Integer::sum);
                mutatedClassPersistent.merge(snap.className, 1, Integer::sum);
                for (String cp : changedProps) {
                    persistentPropDiffCounts.merge(snap.className + "::$" + cp, 1, Integer::sum);
                }
            } else if (wasTracked) {
                objTransient++;
                objMutatedBytes += snap.estBytes;
                mutatedClassTotal.merge(snap.className, 1, Integer::sum);
                mutatedClassTransient.merge(snap.className, 1, Integer::sum);
            } else {
                objUntouched++;
                objUntouchedBytes += snap.estBytes;
            }
        }

        // 2. Cross-check PhpArrays
        int arrUntouched = 0;
        int arrPointerOnly = 0;
        int arrContentTransient = 0;
        int arrContentPersistent = 0;
        int arrCopiedCount = s.copiedPreArrays.size();
        int missedArrContent = 0;
        int missedArrPointer = 0;
        long arrUntouchedBytes = 0;
        long arrPointerOnlyBytes = 0;
        long arrContentMutatedBytes = 0;
        long arrCopiedBytes = 0;

        for (Map.Entry<PhpArray, ArrSnap> entry : s.preArrays.entrySet()) {
            PhpArray arr = entry.getKey();
            ArrSnap snap = entry.getValue();
            boolean trackedContent = s.dirtyArrContent.containsKey(arr);
            boolean trackedPointer = s.dirtyArrPointer.containsKey(arr);
            if (s.copiedPreArrays.containsKey(arr)) {
                arrCopiedBytes += snap.estBytes;
            }

            boolean contentChanged = diffArrayContent(arr, snap);
            boolean pointerChanged = arr.pointer() != snap.pointer;

            if (contentChanged && !trackedContent) {
                missedArrContent++;
            }
            if (pointerChanged && !trackedPointer && !trackedContent) {
                missedArrPointer++;
            }

            if (contentChanged) {
                arrContentPersistent++;
                arrContentMutatedBytes += snap.estBytes;
            } else if (trackedContent) {
                arrContentTransient++;
                arrContentMutatedBytes += snap.estBytes;
            } else if (pointerChanged || trackedPointer) {
                arrPointerOnly++;
                arrPointerOnlyBytes += snap.estBytes;
            } else {
                arrUntouched++;
                arrUntouchedBytes += snap.estBytes;
            }
        }

        // 3. Cross-check PhpRefs
        int refUntouched = 0;
        int refTransient = 0;
        int refPersistent = 0;
        int missedRefWrites = 0;
        List<String> mutatedRefLabels = new ArrayList<>();
        for (Map.Entry<PhpRef, Object> entry : s.preRefs.entrySet()) {
            PhpRef ref = entry.getKey();
            Object before = entry.getValue();
            Object after = ref.get();
            boolean changed = !sameRawValue(before, after);
            boolean tracked = s.dirtyRefs.containsKey(ref);
            if (changed && !tracked) {
                missedRefWrites++;
            }
            if (changed) {
                refPersistent++;
                String lbl = s.preRefLabels.get(ref);
                if (lbl != null) {
                    mutatedRefLabels.add(lbl + " (persistent)");
                }
            } else if (tracked) {
                refTransient++;
                String lbl = s.preRefLabels.get(ref);
                if (lbl != null) {
                    mutatedRefLabels.add(lbl + " (transient)");
                }
            } else {
                refUntouched++;
            }
        }

        // 4. Symbol table deltas after barrier
        Set<String> postIncluded = ctx.censusIncludedSnapshot();
        postIncluded.removeAll(s.preIncluded);
        Set<String> postClasses = ctx.censusClassSnapshot();
        postClasses.removeAll(s.preClasses);
        Set<String> postFunctions = ctx.censusFunctionSnapshot();
        postFunctions.removeAll(s.preFunctions);
        Set<String> postConstants = ctx.censusConstantSnapshot();
        postConstants.removeAll(s.preConstants);

        // Totals
        long totalObjBytes = 0;
        for (ObjSnap os : s.preObjects.values()) totalObjBytes += os.estBytes;
        long totalArrBytes = 0;
        for (ArrSnap as : s.preArrays.values()) totalArrBytes += as.estBytes;
        long totalClosureBytes = 0;
        for (Long b : s.preClosures.values()) totalClosureBytes += b;
        long totalRefBytes = s.preRefs.size() * 32L;
        long totalStringBytes = s.preStrings.size() * 32L + s.preStringBackingBytes;
        long totalGraphBytes = totalObjBytes + totalArrBytes + totalClosureBytes + totalRefBytes + totalStringBytes;

        // Classify pre-barrier PhpArrays: pure-data (no PhpObject/PhpClosure/PhpRef transitively) vs holds-object/closure/ref
        // AND whether it transitively reaches any MUTATED object/ref (so we know exact cloning cost of Hybrid Freeze!)
        IdentityHashMap<PhpArray, Boolean> holdsObjOrRef = new IdentityHashMap<>();
        IdentityHashMap<PhpArray, Boolean> reachesMutated = new IdentityHashMap<>();
        IdentityHashMap<PhpObject, Boolean> mutatedObjSet = new IdentityHashMap<>();
        for (Map.Entry<PhpObject, ObjSnap> entry : s.preObjects.entrySet()) {
            PhpObject o = entry.getKey();
            Set<String> tp = s.dirtyObjProps.get(o);
            if ((tp != null && !tp.isEmpty()) || !diffObject(o, entry.getValue()).isEmpty()) {
                mutatedObjSet.put(o, Boolean.TRUE);
            }
        }
        IdentityHashMap<PhpRef, Boolean> mutatedRefSet = new IdentityHashMap<>();
        for (Map.Entry<PhpRef, Object> entry : s.preRefs.entrySet()) {
            PhpRef r = entry.getKey();
            if (s.dirtyRefs.containsKey(r) || !sameRawValue(entry.getValue(), r.get())) {
                mutatedRefSet.put(r, Boolean.TRUE);
            }
        }
        // Bottom-up fixed point over preArrays (using barrier snapshot values so it reflects wp_loaded state)
        boolean changed = true;
        int iter = 0;
        while (changed && iter++ < 64) {
            changed = false;
            for (Map.Entry<PhpArray, ArrSnap> entry : s.preArrays.entrySet()) {
                PhpArray a = entry.getKey();
                boolean ho = holdsObjOrRef.containsKey(a);
                boolean rm = reachesMutated.containsKey(a);
                if (ho && rm) continue;
                ArrSnap snap = entry.getValue();
                for (Object val : snap.values) {
                    if (!ho) {
                        if (val instanceof PhpObject || val instanceof PhpClosure || val instanceof PhpRef
                                || (val instanceof PhpArray na && holdsObjOrRef.containsKey(na))) {
                            holdsObjOrRef.put(a, Boolean.TRUE);
                            ho = true;
                            changed = true;
                        }
                    }
                    if (!rm) {
                        if ((val instanceof PhpObject o && mutatedObjSet.containsKey(o))
                                || (val instanceof PhpRef r && mutatedRefSet.containsKey(r))
                                || (val instanceof PhpArray na && reachesMutated.containsKey(na))) {
                            reachesMutated.put(a, Boolean.TRUE);
                            rm = true;
                            changed = true;
                        }
                    }
                }
            }
        }
        int pureArrCount = 0;
        long pureArrBytes = 0;
        int objHoldingArrCount = 0;
        long objHoldingArrBytes = 0;
        int mutReachingArrCount = 0;
        long mutReachingArrBytes = 0;
        for (Map.Entry<PhpArray, ArrSnap> entry : s.preArrays.entrySet()) {
            long b = entry.getValue().estBytes;
            if (holdsObjOrRef.containsKey(entry.getKey())) {
                objHoldingArrCount++;
                objHoldingArrBytes += b;
            } else {
                pureArrCount++;
                pureArrBytes += b;
            }
            if (reachesMutated.containsKey(entry.getKey())) {
                mutReachingArrCount++;
                mutReachingArrBytes += b;
            }
        }
        System.err.printf(java.util.Locale.ROOT,
                "[ARRAY-PURITY %02d] page=%s barrier=%s | pure=%d (%.2fMB) | holdsObj/Cl/Ref=%d (%.2fMB) | reachesMutatedObj/Ref=%d (%.2fMB)%n",
                s.seq, s.page, s.barrier,
                pureArrCount, pureArrBytes / (1024.0 * 1024.0),
                objHoldingArrCount, objHoldingArrBytes / (1024.0 * 1024.0),
                mutReachingArrCount, mutReachingArrBytes / (1024.0 * 1024.0));

        // Build JSON
        StringBuilder j = new StringBuilder(65536);
        j.append("{\n");
        j.append("  \"seq\": ").append(s.seq).append(",\n");
        j.append("  \"page\": ").append(jsonStr(s.page)).append(",\n");
        j.append("  \"barrier\": ").append(jsonStr(s.barrier)).append(",\n");
        j.append("  \"boot_ms\": ").append(String.format(java.util.Locale.ROOT, "%.2f", (s.barrierNanos - s.startNanos) / 1e6)).append(",\n");
        j.append("  \"total_ms\": ").append(String.format(java.util.Locale.ROOT, "%.2f", (endNanos - s.startNanos) / 1e6)).append(",\n");

        // Graph summary
        j.append("  \"graph\": {\n");
        j.append("    \"total_est_bytes\": ").append(totalGraphBytes).append(",\n");
        j.append("    \"objects\": {\"count\": ").append(s.preObjects.size()).append(", \"est_bytes\": ").append(totalObjBytes).append("},\n");
        j.append("    \"arrays\": {\"count\": ").append(s.preArrays.size()).append(", \"est_bytes\": ").append(totalArrBytes)
                .append(", \"pure_count\": ").append(pureArrCount).append(", \"pure_bytes\": ").append(pureArrBytes)
                .append(", \"obj_holding_count\": ").append(objHoldingArrCount).append(", \"obj_holding_bytes\": ").append(objHoldingArrBytes)
                .append(", \"mut_reaching_count\": ").append(mutReachingArrCount).append(", \"mut_reaching_bytes\": ").append(mutReachingArrBytes)
                .append(", \"total_elements\": ").append(s.arrTotalElements).append(", \"kinds\": ").append(jsonIntMap(s.arrKindCounts))
                .append(", \"kind_bytes\": ").append(jsonLongMap(s.arrKindBytes)).append("},\n");
        j.append("    \"strings\": {\"count\": ").append(s.preStrings.size()).append(", \"unique_backing\": ").append(s.preStringBacking.size())
                .append(", \"backing_bytes\": ").append(s.preStringBackingBytes).append(", \"est_bytes\": ").append(totalStringBytes).append("},\n");
        j.append("    \"closures\": {\"count\": ").append(s.preClosures.size()).append(", \"est_bytes\": ").append(totalClosureBytes).append("},\n");
        j.append("    \"refs\": {\"count\": ").append(s.preRefs.size()).append(", \"est_bytes\": ").append(totalRefBytes).append("},\n");
        j.append("    \"scalar_box_edges\": ").append(s.preScalarBoxCount).append(",\n");
        j.append("    \"special\": {\n");
        j.append("      \"lazy_objects\": ").append(s.lazyCount).append(",\n");
        j.append("      \"hooked_objects\": ").append(s.hookedCount).append(",\n");
        j.append("      \"internal_objects\": ").append(jsonIntMap(s.internalClassCounts)).append(",\n");
        j.append("      \"destructor_objects\": ").append(jsonIntMap(s.dtorClassCounts)).append("\n");
        j.append("    }\n");
        j.append("  },\n");

        // Symbols
        j.append("  \"symbols\": {\n");
        j.append("    \"pre_included\": ").append(s.preIncluded.size()).append(", \"post_included\": ").append(postIncluded.size()).append(",\n");
        j.append("    \"pre_classes\": ").append(s.preClasses.size()).append(", \"post_classes\": ").append(postClasses.size()).append(",\n");
        j.append("    \"pre_functions\": ").append(s.preFunctions.size()).append(", \"post_functions\": ").append(postFunctions.size()).append(",\n");
        j.append("    \"pre_constants\": ").append(s.preConstants.size()).append(", \"post_constants\": ").append(postConstants.size()).append(",\n");
        j.append("    \"pre_globals\": ").append(s.preGlobalCount).append(", \"post_globals\": ").append(ctx.censusGlobalCount()).append(",\n");
        j.append("    \"pre_static_vars\": ").append(s.preStaticVarCount).append(", \"post_static_vars\": ").append(ctx.censusStaticVarCount()).append(",\n");
        j.append("    \"post_included_sample\": ").append(jsonStrList(postIncluded, 25)).append(",\n");
        j.append("    \"post_classes_sample\": ").append(jsonStrList(postClasses, 25)).append("\n");
        j.append("  },\n");

        // Top 30 classes at barrier
        List<Map.Entry<String, Integer>> topClasses = new ArrayList<>(s.classCounts.entrySet());
        topClasses.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        j.append("  \"top_classes\": [\n");
        for (int i = 0; i < Math.min(35, topClasses.size()); i++) {
            String c = topClasses.get(i).getKey();
            int cnt = topClasses.get(i).getValue();
            int mut = mutatedClassTotal.getOrDefault(c, 0);
            int mutP = mutatedClassPersistent.getOrDefault(c, 0);
            int mutT = mutatedClassTransient.getOrDefault(c, 0);
            j.append("    {\"class\": ").append(jsonStr(c))
                    .append(", \"count\": ").append(cnt)
                    .append(", \"shallow_bytes\": ").append(s.classShallowBytes.getOrDefault(c, 0L))
                    .append(", \"props_total\": ").append(s.classPropTotals.getOrDefault(c, 0))
                    .append(", \"mutated\": ").append(mut)
                    .append(", \"persistent\": ").append(mutP)
                    .append(", \"transient\": ").append(mutT).append("}");
            if (i + 1 < Math.min(35, topClasses.size())) j.append(",");
            j.append("\n");
        }
        j.append("  ],\n");

        // Top mutated classes (sorted by mutated count)
        List<Map.Entry<String, Integer>> topMutClasses = new ArrayList<>(mutatedClassTotal.entrySet());
        topMutClasses.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        j.append("  \"top_mutated_classes\": [\n");
        for (int i = 0; i < Math.min(35, topMutClasses.size()); i++) {
            String c = topMutClasses.get(i).getKey();
            j.append("    {\"class\": ").append(jsonStr(c))
                    .append(", \"mutated\": ").append(topMutClasses.get(i).getValue())
                    .append(", \"total\": ").append(s.classCounts.getOrDefault(c, 0))
                    .append(", \"persistent\": ").append(mutatedClassPersistent.getOrDefault(c, 0))
                    .append(", \"transient\": ").append(mutatedClassTransient.getOrDefault(c, 0)).append("}");
            if (i + 1 < Math.min(35, topMutClasses.size())) j.append(",");
            j.append("\n");
        }
        j.append("  ],\n");

        // Top mutated object properties by write event count
        List<Map.Entry<String, Long>> topPropWrites = new ArrayList<>(s.objPropWriteCounts.entrySet());
        topPropWrites.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        j.append("  \"top_prop_writes\": [\n");
        for (int i = 0; i < Math.min(40, topPropWrites.size()); i++) {
            String k = topPropWrites.get(i).getKey();
            j.append("    {\"prop\": ").append(jsonStr(k))
                    .append(", \"writes\": ").append(topPropWrites.get(i).getValue())
                    .append(", \"persistent_objs\": ").append(persistentPropDiffCounts.getOrDefault(k, 0)).append("}");
            if (i + 1 < Math.min(40, topPropWrites.size())) j.append(",");
            j.append("\n");
        }
        j.append("  ],\n");

        // Top 30 roots by total reachable bytes
        List<Map.Entry<String, RootBucketStats>> topRoots = new ArrayList<>(s.rootStats.entrySet());
        topRoots.sort((a, b) -> Long.compare(b.getValue().totalReachableBytes, a.getValue().totalReachableBytes));
        j.append("  \"top_roots\": [\n");
        for (int i = 0; i < Math.min(35, topRoots.size()); i++) {
            String r = topRoots.get(i).getKey();
            RootBucketStats st = topRoots.get(i).getValue();
            j.append("    {\"root\": ").append(jsonStr(r))
                    .append(", \"reachable_bytes\": ").append(st.totalReachableBytes)
                    .append(", \"reachable_nodes\": ").append(st.totalReachableNodes)
                    .append(", \"exclusive_bytes\": ").append(st.exclusiveBytes)
                    .append(", \"exclusive_nodes\": ").append(st.exclusiveNodes)
                    .append(", \"first_touch_bytes\": ").append(st.firstTouchBytes).append("}");
            if (i + 1 < Math.min(35, topRoots.size())) j.append(",");
            j.append("\n");
        }
        j.append("  ],\n");

        // Mutation summary + cross-check
        j.append("  \"mutations\": {\n");
        j.append("    \"objects\": {\"total\": ").append(s.preObjects.size())
                .append(", \"untouched\": ").append(objUntouched)
                .append(", \"transient\": ").append(objTransient)
                .append(", \"persistent\": ").append(objPersistent)
                .append(", \"untouched_bytes\": ").append(objUntouchedBytes)
                .append(", \"mutated_bytes\": ").append(objMutatedBytes)
                .append(", \"write_events\": ").append(s.totalObjWriteEvents)
                .append(", \"missed_writes\": ").append(missedObjWrites)
                .append(", \"missed_samples\": ").append(jsonStrList(missedObjSamples, 20)).append("},\n");
        j.append("    \"arrays\": {\"total\": ").append(s.preArrays.size())
                .append(", \"untouched\": ").append(arrUntouched)
                .append(", \"pointer_only\": ").append(arrPointerOnly)
                .append(", \"content_transient\": ").append(arrContentTransient)
                .append(", \"content_persistent\": ").append(arrContentPersistent)
                .append(", \"cow_copied\": ").append(arrCopiedCount)
                .append(", \"untouched_bytes\": ").append(arrUntouchedBytes)
                .append(", \"pointer_only_bytes\": ").append(arrPointerOnlyBytes)
                .append(", \"content_mutated_bytes\": ").append(arrContentMutatedBytes)
                .append(", \"cow_copied_bytes\": ").append(arrCopiedBytes)
                .append(", \"content_write_events\": ").append(s.totalArrContentWriteEvents)
                .append(", \"pointer_write_events\": ").append(s.totalArrPointerWriteEvents)
                .append(", \"copy_events\": ").append(s.totalArrCopyEvents)
                .append(", \"missed_content\": ").append(missedArrContent)
                .append(", \"missed_pointer\": ").append(missedArrPointer).append("},\n");
        j.append("    \"refs\": {\"total\": ").append(s.preRefs.size())
                .append(", \"untouched\": ").append(refUntouched)
                .append(", \"transient\": ").append(refTransient)
                .append(", \"persistent\": ").append(refPersistent)
                .append(", \"write_events\": ").append(s.totalRefWriteEvents)
                .append(", \"missed_writes\": ").append(missedRefWrites)
                .append(", \"mutated_labels\": ").append(jsonStrList(mutatedRefLabels, 80)).append("}\n");
        j.append("  },\n");

        // Pre-barrier taint
        j.append("  \"pre_barrier_taint\": {\n");
        j.append("    \"sql_count\": ").append(s.preSqlQueries.size()).append(",\n");
        j.append("    \"sql_queries\": ").append(jsonStrList(s.preSqlQueries, 100)).append(",\n");
        List<Map.Entry<String, Integer>> sgList = new ArrayList<>(s.superglobalReads.entrySet());
        sgList.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        j.append("    \"superglobal_reads\": [\n");
        for (int i = 0; i < sgList.size(); i++) {
            String k = sgList.get(i).getKey();
            j.append("      {\"key\": ").append(jsonStr(k))
                    .append(", \"count\": ").append(sgList.get(i).getValue())
                    .append(", \"sites\": ").append(jsonStrList(s.superglobalReadSites.getOrDefault(k, Set.of()), 5)).append("}");
            if (i + 1 < sgList.size()) j.append(",");
            j.append("\n");
        }
        j.append("    ],\n");
        List<Map.Entry<String, Integer>> ndList = new ArrayList<>(s.preNonDetCalls.entrySet());
        ndList.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        j.append("    \"nondet_calls\": [\n");
        for (int i = 0; i < Math.min(60, ndList.size()); i++) {
            j.append("      {\"site\": ").append(jsonStr(ndList.get(i).getKey()))
                    .append(", \"count\": ").append(ndList.get(i).getValue()).append("}");
            if (i + 1 < Math.min(60, ndList.size())) j.append(",");
            j.append("\n");
        }
        j.append("    ]\n");
        j.append("  },\n");

        // Root fingerprints for cross-request comparison
        j.append("  \"root_fingerprints\": {\n");
        int fi = 0;
        for (Map.Entry<String, String> fe : s.rootFingerprints.entrySet()) {
            j.append("    ").append(jsonStr(fe.getKey())).append(": ").append(jsonStr(fe.getValue()));
            if (++fi < s.rootFingerprints.size()) j.append(",");
            j.append("\n");
        }
        j.append("  }\n");
        j.append("}\n");

        Path outDir = Path.of(OUT_DIR);
        Files.createDirectories(outDir);
        String safeName = s.page.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path jsonPath = outDir.resolve(String.format("%02d-%s-%s.json", s.seq, safeName, s.barrier));
        Files.writeString(jsonPath, j.toString(), StandardCharsets.UTF_8);

        String summary = String.format(java.util.Locale.ROOT,
                "[CENSUS %02d] page=%s barrier=%s graph=%.2fMB (obj=%d/%.2fMB arr=%d/%.2fMB str=%d/%.2fMB cl=%d ref=%d) "
                        + "| objMut=%d+%d/%d (missed=%d) arrMut=content(%d+%d)+ptr(%d)+cow(%d)/%d (missed=%d/%d) "
                        + "| preSQL=%d preSG=%d\n",
                s.seq, s.page, s.barrier, totalGraphBytes / (1024.0 * 1024.0),
                s.preObjects.size(), totalObjBytes / (1024.0 * 1024.0),
                s.preArrays.size(), totalArrBytes / (1024.0 * 1024.0),
                s.preStrings.size(), totalStringBytes / (1024.0 * 1024.0),
                s.preClosures.size(), s.preRefs.size(),
                objPersistent, objTransient, s.preObjects.size(), missedObjWrites,
                arrContentPersistent, arrContentTransient, arrPointerOnly, arrCopiedCount, s.preArrays.size(),
                missedArrContent, missedArrPointer,
                s.preSqlQueries.size(), s.superglobalReads.size());
        System.err.print(summary);
        Files.writeString(outDir.resolve("summary.txt"), summary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<String> diffObject(PhpObject obj, ObjSnap snap) {
        List<String> diffs = new ArrayList<>();
        if (obj.getInternal() != snap.internal) {
            diffs.add("<internal>");
        }
        if (snap.lazy) {
            if (obj.lazy() == null) {
                diffs.add("<lazy-initialized>");
            }
            return diffs;
        }
        Map<String, Object> before = new HashMap<>(snap.keys.length * 2);
        for (int i = 0; i < snap.keys.length; i++) {
            before.put(snap.keys[i], snap.values[i]);
        }
        Object[] afterNames = obj.propertyNames();
        Set<String> seenAfter = new HashSet<>(afterNames.length * 2);
        for (Object n : afterNames) {
            if (n instanceof String k) {
                seenAfter.add(k);
                Object afterVal = obj.getProperty(k);
                if (!before.containsKey(k)) {
                    diffs.add(k);
                } else if (!sameRawValue(before.get(k), afterVal)) {
                    diffs.add(k);
                }
            }
        }
        for (String k : snap.keys) {
            if (!seenAfter.contains(k)) {
                diffs.add(k);
            }
        }
        return diffs;
    }

    private static boolean diffArrayContent(PhpArray arr, ArrSnap snap) {
        if (arr.size() != snap.size || arr.nextIndex() != snap.nextIndex) {
            return true;
        }
        int idx = 0;
        for (int p = arr.firstPosition(); p >= 0; p = arr.nextPosition(p)) {
            if (idx >= snap.keys.length) {
                return true;
            }
            if (!sameRawValue(snap.keys[idx], arr.keyAt(p))) {
                return true;
            }
            if (!sameRawValue(snap.values[idx], arr.rawAt(p))) {
                return true;
            }
            idx++;
        }
        return idx != snap.keys.length;
    }

    private static boolean sameRawValue(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Long || a instanceof Double || a instanceof Boolean || a instanceof TruffleString) {
            return a.equals(b);
        }
        return false;
    }

    private static long fingerprint(Object v, int depth, IdentityHashMap<Object, Integer> seen) {
        if (v == null || v == PhpNull.NULL) return 0x11L;
        if (v instanceof Boolean b) return b ? 0x22L : 0x23L;
        if (v instanceof Long l) return Long.rotateLeft(l, 7) ^ 0x33L;
        if (v instanceof Double d) return Long.rotateLeft(Double.doubleToRawLongBits(d), 11) ^ 0x44L;
        if (v instanceof TruffleString ts) return ((long) ts.hashCode() << 1) ^ Str.length(ts) ^ 0x55L;
        if (depth >= 6) return v.getClass().getName().hashCode() ^ 0x99L;
        Integer id = seen.get(v);
        if (id != null) return id * 0x9e3779b97f4a7c15L;
        seen.put(v, seen.size() + 1);
        if (v instanceof PhpRef ref) {
            return fingerprint(ref.get(), depth + 1, seen) * 31L + 7L;
        }
        if (v instanceof PhpArray arr) {
            long h = 0x77L ^ arr.size();
            int n = 0;
            for (int p = arr.firstPosition(); p >= 0 && n < 512; p = arr.nextPosition(p), n++) {
                h = h * 31L + fingerprint(arr.keyAt(p), depth + 1, seen);
                h = h * 31L + fingerprint(arr.rawAt(p), depth + 1, seen);
            }
            return h;
        }
        if (v instanceof PhpObject obj) {
            long h = obj.getPhpClass().getName().hashCode() ^ 0x88L;
            if (obj.lazy() == null) {
                Object[] names = obj.propertyNames();
                for (int i = 0; i < Math.min(names.length, 64); i++) {
                    if (names[i] instanceof String k) {
                        h = h * 31L + k.hashCode();
                        h = h * 31L + fingerprint(obj.getProperty(k), depth + 1, seen);
                    }
                }
            }
            return h;
        }
        if (v instanceof PhpClosure cl) {
            long h = cl.getFunction().getName().hashCode() ^ 0xAAL;
            h = h * 31L + cl.getCaptureCount();
            for (int i = 0; i < Math.min(cl.getCaptureCount(), 16); i++) {
                h = h * 31L + fingerprint(cl.getCaptured(i), depth + 1, seen);
            }
            return h;
        }
        return v.getClass().getName().hashCode();
    }

    private static String shortDesc(Object v) {
        if (v instanceof PhpRef ref) v = ref.get();
        if (v == null || v == PhpNull.NULL) return "null";
        if (v instanceof Boolean b) return "bool:" + b;
        if (v instanceof Long l) return "int:" + l;
        if (v instanceof Double d) return "float:" + d;
        if (v instanceof TruffleString ts) {
            String s = Str.java(ts);
            if (s.length() > 40) s = s.substring(0, 40) + "...";
            return "str(" + Str.length(ts) + "):" + s.replaceAll("[\\r\\n]+", " ");
        }
        if (v instanceof PhpArray arr) return "arr(" + arr.size() + ")";
        if (v instanceof PhpObject obj) return "obj:" + obj.getPhpClass().getName();
        if (v instanceof PhpClosure cl) return "closure:" + cl.getFunction().getName();
        return v.getClass().getSimpleName();
    }

    private static String bucketName(String label) {
        if (label.startsWith("$")) return label;
        if (label.startsWith("constant ")) return "constants";
        if (label.startsWith("static ")) return label;
        if (label.startsWith("holder:")) return "holders";
        int sep = label.indexOf("::$");
        if (sep > 0) return "static:" + label.substring(0, sep);
        return label;
    }

    private static String formatKey(Object key) {
        if (key instanceof TruffleString ts) return Str.java(ts);
        return String.valueOf(key);
    }

    private static String shortLoc() {
        try {
            SourceLocation loc = SourceLocation.current();
            if (loc == null || loc.file() == null) return "<native>";
            String f = loc.file();
            int idx = f.indexOf("wordpress/");
            if (idx >= 0) f = f.substring(idx + "wordpress/".length());
            return f + ":" + loc.line();
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    public static long align8(long n) {
        return (n + 7L) & ~7L;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String jsonIntMap(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(jsonStr(e.getKey())).append(": ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String jsonLongMap(Map<String, Long> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(jsonStr(e.getKey())).append(": ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String jsonStrList(Iterable<String> items, int limit) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (String s : items) {
            if (i >= limit) break;
            if (i++ > 0) sb.append(", ");
            sb.append(jsonStr(s));
        }
        return sb.append(']').toString();
    }
}
