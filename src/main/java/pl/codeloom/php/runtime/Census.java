package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.vars.IncludeScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The refcount census for test mode (wiki/destructors.md, prototype step 3; -Dphp.rc.verify=true): whether an
 * object whose count reached zero is still reachable from the roots PHP code sees (each frame's locals and
 * arguments, globals, static variables, constants, static properties), through references, array elements,
 * properties and closures' captures. Reachable at zero means a slot that holds it wasn't counted: the report names
 * the path and the calls, and the object isn't destroyed. Values only built-ins' Java state holds (SPL storage,
 * handlers) aren't roots yet, nor are suspended generators' frames, so a zero they hide goes unreported.
 */
final class Census {
    private Census() {
    }

    private static final boolean VERIFY = Boolean.getBoolean("php.rc.verify");
    /** Besides every object of a class with a destructor, every n-th zero is checked (a census walks the heap). */
    private static final int EVERY = Integer.getInteger("php.rc.verify.every", 100);
    private static final int MAX_REPORTS = Integer.getInteger("php.rc.verify.reports", 50);
    private static long zeros;
    private static long checked;
    private static long reachable;
    private static final Map<String, Integer> sites = new LinkedHashMap<>();

    static {
        if (VERIFY) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println("rc-verify: " + checked
                    + " of " + zeros + " zeros checked, " + reachable + " still reachable at " + sites.size()
                    + " sites")));
        }
    }

    static boolean enabled() {
        return VERIFY;
    }

    /** Whether the object at zero is still reachable (reported, then); false if not checked or not reachable. */
    @TruffleBoundary
    static boolean reachableAtZero(PhpObject object) {
        zeros++;
        if (object.getPhpClass().findMethod("__destruct") == null && zeros % EVERY != 0) {
            return false;
        }
        checked++;
        String path = pathTo(object);
        if (path == null) {
            return false;
        }
        reachable++;
        String calls = calls();
        String site = object.getPhpClass().getName() + " at " + calls;
        int seen = sites.merge(site, 1, Integer::sum);
        if (seen == 1 && sites.size() <= MAX_REPORTS) {
            System.err.println("rc-verify: " + object.getPhpClass().getName() + " #" + object.id()
                    + " reached zero but is reachable via " + path + "\n    in " + calls);
        }
        return true;
    }

    private record Step(Object value, Step parent, String label) {
        String path() {
            List<String> labels = new ArrayList<>();
            for (Step s = this; s != null; s = s.parent) {
                labels.add(0, s.label);
            }
            return String.join("", labels);
        }
    }

    /** Breadth first from the roots: the shortest path to target, or null. */
    private static String pathTo(PhpObject target) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Step> queue = new ArrayDeque<>();
        roots((label, value) -> visit(value, null, label, seen, queue));
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            Object value = step.value;
            if (value == target) {
                return step.path();
            }
            if (value instanceof PhpRef ref) {
                visit(ref.get(), step, "&", seen, queue);
            } else if (value instanceof PhpArray array) {
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    visit(array.rawAt(p), step, "[" + array.keyAt(p) + "]", seen, queue);
                }
            } else if (value instanceof PhpObject object) {
                Object[] table = object.propertyTable();
                for (int i = 0; i + 1 < table.length; i += 2) {
                    visit(table[i + 1], step, "->" + table[i], seen, queue);
                }
            } else if (value instanceof PhpClosure closure) {
                visit(closure.getBoundThis(), step, " $this", seen, queue);
                for (int i = 0; i < closure.getCaptureCount(); i++) {
                    visit(closure.getCaptured(i), step, " use#" + i, seen, queue);
                }
            }
        }
        return null;
    }

    private static void visit(Object value, Step parent, String label, IdentityHashMap<Object, Boolean> seen,
                              ArrayDeque<Step> queue) {
        if ((value instanceof PhpRef || value instanceof PhpArray || value instanceof PhpObject
                || value instanceof PhpClosure) && seen.put(value, Boolean.TRUE) == null) {
            queue.add(new Step(value, parent, label));
        }
    }

    private static void roots(java.util.function.BiConsumer<String, Object> root) {
        PhpContext.get(null).censusRoots(root);
        Truffle.getRuntime().iterateFrames(instance -> {
            RootNode node = ((RootCallTarget) instance.getCallTarget()).getRootNode();
            Frame frame = instance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
            String name = node.getName();
            if (!(node instanceof BytecodeRootNode)) {
                // a built-in's arguments, held for its call (a PHP function's are its parameters, locals, which
                // its epilogue releases while the arguments array still has them)
                Object[] arguments = frame.getArguments();
                for (int i = 0; i < arguments.length; i++) {
                    root.accept(name + " argument " + i, arguments[i]);
                }
            } else {
                BytecodeLocation location = BytecodeLocation.get(instance);
                if (location != null) {
                    try {
                        Object[] locals = location.getBytecodeNode().getLocalValues(location.getBytecodeIndex(),
                                frame);
                        for (int i = 0; i < locals.length; i++) {
                            root.accept(name + " local " + i, locals[i]);
                        }
                    } catch (RuntimeException e) {
                        // a frame whose locals can't be read here: its values aren't roots
                    }
                }
            }
            return null;
        });
    }

    // ---- the count audit (-Dphp.rc.audit=true): each object's count against its holders ----

    /**
     * Whether objects are tracked for {@link #audit}. A count too high (a leak: the destructor runs late or never)
     * never reaches zero, so the check at zero can't see it; the audit compares every object's count with the
     * holders php-src would count.
     */
    static final boolean AUDIT = Boolean.getBoolean("php.rc.audit");
    private static final int AUDIT_GROUPS = Integer.getInteger("php.rc.audit.groups", 40);
    /** Where the report goes (appended), else stderr. */
    private static final String AUDIT_OUT = System.getProperty("php.rc.audit.out");
    private static final boolean DESTRUCTORS = Boolean.getBoolean("php.rc.destructors");

    /** An object made in the context, with where: the PHP calls running then, innermost first. */
    private record Tracked(PhpObject object, String site) {
    }

    private static final Map<String, String> internedSites = new java.util.HashMap<>();

    @TruffleBoundary
    static void track(PhpObject object) {
        PhpContext context;
        try {
            context = PhpContext.get(null);
        } catch (RuntimeException | AssertionError e) {
            return;
        }
        if (context != null) {
            String site = allocationSite();
            context.auditedObjects().add(new Tracked(object, internedSites.computeIfAbsent(site, s -> s)));
        }
    }

    /** The innermost three PHP calls, each with its file's line. */
    private static String allocationSite() {
        List<String> names = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(instance -> {
            RootNode node = SourceLocation.codeRoot(((RootCallTarget) instance.getCallTarget()).getRootNode());
            String name = node.getName() == null ? "{main}" : node.getName();
            BytecodeLocation location = null;
            try {
                location = BytecodeLocation.get(instance);
            } catch (RuntimeException e) {
                // no instruction known for this frame
            }
            SourceLocation where = location == null ? null : SourceLocation.ofStatement(location);
            if (where != null) {
                String file = where.file();
                file = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
                name += "@" + file + ":" + where.line();
            }
            names.add(name);
            return names.size() >= 3 ? names : null;
        });
        return String.join(" < ", names);
    }

    /** A holder's step in the walk: the value, the step that reached it (null at a root), and how. */
    private static final class Node {
        final Object value;
        final Node parent;
        final String label;
        final boolean fromRoot;

        Node(Object value, Node parent, String label, boolean fromRoot) {
            this.value = value;
            this.parent = parent;
            this.label = label;
            this.fromRoot = fromRoot;
        }

        String path() {
            List<String> labels = new ArrayList<>();
            for (Node n = this; n != null; n = n.parent) {
                labels.add(0, n.label);
            }
            return String.join("", labels);
        }
    }

    /** The walk: every holder reached once, and how many counted slots hold each object. */
    private static final class Walk {
        final IdentityHashMap<Object, Node> seen = new IdentityHashMap<>();
        final IdentityHashMap<PhpObject, int[]> holders = new IdentityHashMap<>();
        final ArrayDeque<Node> queue = new ArrayDeque<>();
        boolean fromRoots = true;
        /** Objects whose properties were released (destroyed) though a slot still holds them, with the first path. */
        final IdentityHashMap<PhpObject, Node> freedHeld = new IdentityHashMap<>();
        /** The frames walked from the stack: a running generator's frame isn't counted again from its object. */
        final IdentityHashMap<Object, Boolean> frames = new IdentityHashMap<>();

        /** A slot holding value; weight: whether php-src counts it (1) or it only makes the value reachable (0). */
        void edge(Node from, String label, Object value, int weight) {
            if (value instanceof PhpObject object && weight > 0) {
                holders.computeIfAbsent(object, o -> new int[1])[0] += weight;
            }
            if ((value instanceof PhpRef || value instanceof PhpArray || value instanceof PhpObject
                    || value instanceof PhpClosure || value instanceof IncludeScope || value instanceof Refcount.Holder)
                    && !seen.containsKey(value)) {
                Node node = new Node(value, from, label, fromRoots);
                seen.put(value, node);
                queue.add(node);
            }
        }

        /** A holder's slots: each one's label, value and weight (see edge). */
        interface Slots {
            void slot(String label, Object value, int weight);
        }

        void children(Object value, Slots slots) {
            if (value instanceof PhpRef ref) {
                slots.slot("&", ref.get(), 1);
            } else if (value instanceof PhpArray array) {
                int weight = array.isFrozen() ? 0 : 1;
                for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                    slots.slot("[" + array.keyAt(p) + "]", array.rawAt(p), weight);
                }
            } else if (value instanceof PhpObject object) {
                if (object.isFreed()) {
                    return;
                }
                Object[] table = object.propertyTable();
                for (int i = 0; i + 1 < table.length; i += 2) {
                    slots.slot("->" + table[i], table[i + 1], 1);
                }
                if (object.getInternal() instanceof PhpGenerator generator) {
                    generator.censusChildren(frames::containsKey, (label, child) -> slots.slot(label, child, 1));
                }
            } else if (value instanceof PhpClosure closure) {
                slots.slot(" $this", closure.getBoundThis(), 1);
                for (int i = 0; i < closure.getCaptureCount(); i++) {
                    slots.slot(" use#" + i, closure.getCaptured(i), 1);
                }
                for (PhpRef ref : closure.staticRefs()) {
                    slots.slot(" static", ref, 1);
                }
            } else if (value instanceof IncludeScope scope) {
                scope.forEachOther((name, ref) -> slots.slot(" $" + name, ref, 1));
            } else if (value instanceof Refcount.Holder holder) {
                slots.slot(" foreach", holder.held(), 1);
            }
        }

        void drain() {
            while (!queue.isEmpty()) {
                Node node = queue.poll();
                if (node.value instanceof PhpObject object && object.isFreed()) {
                    freedHeld.putIfAbsent(object, node);
                }
                children(node.value, (label, child, weight) -> edge(node, label, child, weight));
            }
        }

        /** The roots' counted slots, for {@link #held}. */
        final List<Object> countedRoots = new ArrayList<>();

        /**
         * What php-src would still hold: reached from a root through counted slots only. An object with a higher
         * count that isn't held here is one php-src would have destroyed already (late); one held, but by more
         * counts than php-src's, isn't late yet (only the count differs, which no program sees).
         */
        IdentityHashMap<Object, Boolean> held() {
            IdentityHashMap<Object, Boolean> held = new IdentityHashMap<>();
            ArrayDeque<Object> work = new ArrayDeque<>();
            for (Object root : countedRoots) {
                if (held.put(root, Boolean.TRUE) == null) {
                    work.add(root);
                }
            }
            while (!work.isEmpty()) {
                children(work.poll(), (label, child, weight) -> {
                    if (weight > 0 && child != null && held.put(child, Boolean.TRUE) == null) {
                        work.add(child);
                    }
                });
            }
            return held;
        }

        /** A root's slot (a variable, a built-in's state, a frame's local or argument). */
        void root(String label, Object value, int weight) {
            if (weight > 0 && value != null) {
                countedRoots.add(value);
            }
            edge(null, label, value, weight);
        }
    }

    /** One kind of mismatch: its objects (each once), the audits it was seen at, and the first one's details. */
    private static final class Group {
        final IdentityHashMap<PhpObject, Boolean> objects = new IdentityHashMap<>();
        int audits;
        String example;
    }

    /**
     * Audits during the run (-Dphp.rc.audit.every=n): at every n-th statement end, all frames' locals are roots
     * too. A count too high only for a while (an object held to its function's end instead of its statement's) is
     * gone again by the script's end; these points see it.
     */
    private static final int AUDIT_EVERY = Integer.getInteger("php.rc.audit.every", 0);
    static final boolean AUDIT_POINTS = AUDIT && AUDIT_EVERY > 0;
    private static long statementEnds;
    private static int pointAudits;
    /** The points' findings until the script's end, by kind, class and where the object was made. */
    private static final Map<String, Group> pointGroups = new LinkedHashMap<>();

    /** The objects whose destructor runs now: each holds the count zend_objects_store_del adds around it. */
    private static final IdentityHashMap<PhpObject, int[]> inDestructor = new IdentityHashMap<>();

    @TruffleBoundary
    static void destroying(PhpObject object, boolean starts) {
        if (starts) {
            inDestructor.computeIfAbsent(object, o -> new int[1])[0]++;
        } else {
            int[] count = inDestructor.get(object);
            if (count != null && --count[0] <= 0) {
                inDestructor.remove(object);
            }
        }
    }

    /** The owning temporaries sent as arguments and still kept in a hidden local (Refcount.sent). */
    private static final IdentityHashMap<PhpObject, int[]> sentTemporaries = new IdentityHashMap<>();

    @TruffleBoundary
    static void sent(PhpObject object) {
        sentTemporaries.computeIfAbsent(object, o -> new int[1])[0]++;
    }

    @TruffleBoundary
    static void unsent(PhpObject object) {
        int[] sent = sentTemporaries.get(object);
        if (sent != null && --sent[0] <= 0) {
            sentTemporaries.remove(object);
        }
    }

    /** A statement ended (AuditPoint): every n-th one, the counts are audited with the frames as roots. */
    @TruffleBoundary
    static void auditPoint(com.oracle.truffle.api.frame.MaterializedFrame innermost, com.oracle.truffle.api.bytecode.BytecodeNode innermostBytecode) {
        if (++statementEnds % AUDIT_EVERY != 0) {
            return;
        }
        PhpContext context = PhpContext.get(null);
        pointAudits++;
        Findings findings = check(context, true, innermost, innermostBytecode);
        findings.groups.forEach((key, group) -> {
            Group total = pointGroups.computeIfAbsent(key, k -> new Group());
            if (total.audits++ == 0) {
                total.example = group.example;
            }
            total.objects.putAll(group.objects);
        });
        // freed objects nothing holds can't show up again: the list keeps the live ones and those held
        context.auditedObjects().removeIf(entry -> {
            PhpObject object = ((Tracked) entry).object();
            return object.isFreed() && !findings.walk.freedHeld.containsKey(object);
        });
    }

    /** One audit's result: the walk, the mismatches by group, and the totals for its summary line. */
    private static final class Findings {
        Walk walk;
        final Map<String, Group> groups = new LinkedHashMap<>();
        int alive;
        int reachable;
        int garbage;
        int enums;
        int leaks;
        int unders;
        int zeroUnfreed;
        int freedHeld;
    }

    /**
     * The audit: walks from the roots (globals, statics, constants, static properties, the values built-ins keep,
     * and with frames the running calls' locals), then from every live object nothing reached (garbage, in a cycle
     * or leaked), counting for each object the slots php-src counts: a variable or temporary, a reference, an element
     * of an array that isn't frozen, a property of a live object, a closure's $this, captures and statics, a
     * suspended generator's locals, key, value and return value. An object whose count is higher leaks (its
     * destructor runs late or never); lower, it reaches zero while still held (early).
     * <p>
     * Mid-run, some counts are held where the walk can't name the holder: a call's receiver ($this, counted by the
     * caller around the call) and an owning value on a caller's operand stack (`new`'s object while its constructor
     * runs, an array literal's element while the next one is evaluated). Each such appearance is slack: a count up to
     * the holders plus the slack isn't reported as a leak. A count below the holders always is.
     */
    private static Findings check(PhpContext context, boolean frames, Frame innermost,
                                  com.oracle.truffle.api.bytecode.BytecodeNode innermostBytecode) {
        Findings f = new Findings();
        Walk walk = new Walk();
        f.walk = walk;
        IdentityHashMap<PhpObject, int[]> slack = new IdentityHashMap<>();
        // the destructor's own hold (zend_objects_store_del's GC_ADDREF) has no slot either
        inDestructor.forEach((object, count) -> slack.computeIfAbsent(object, o -> new int[1])[0] += count[0]);
        context.censusRoots((label, value) -> walk.root(label, value, 1));
        context.censusHolders((label, value) -> walk.root(label, value, 1));
        String[] builtin = {null};
        boolean[] first = {true};
        if (frames) {
            Truffle.getRuntime().iterateFrames(instance -> {
                RootNode root = ((RootCallTarget) instance.getCallTarget()).getRootNode();
                if (root instanceof PhpGenerator.LinkedFrame) {
                    return null;
                }
                RootNode code = SourceLocation.codeRoot(root);
                boolean isInnermost = first[0];
                first[0] = false;
                Frame frame = isInnermost && innermost != null ? innermost
                        : SourceLocation.codeFrame(root, instance.getFrame(FrameInstance.FrameAccess.READ_ONLY));
                String name = code.getName() == null ? "{main}" : code.getName();
                Object[] arguments = frame.getArguments();
                if (arguments.length > PhpFunction.ARG_THIS && arguments[PhpFunction.ARG_THIS] instanceof PhpObject self) {
                    slack.computeIfAbsent(self, o -> new int[1])[0]++;
                }
                boolean user = code instanceof BytecodeRootNode;
                for (int i = 0; i < arguments.length; i++) {
                    // a built-in's argument slot holds what php-src moved into it (a temporary sent); a user
                    // function's parameters are its locals
                    int[] sent = !user && i >= PhpFunction.ARG_FIRST ? sentTemporaries.get(arguments[i]) : null;
                    walk.root(name + " argument " + i, arguments[i], sent != null && sent[0] > 0 ? 1 : 0);
                }
                if (!user) {
                    if (builtin[0] == null && code instanceof pl.codeloom.php.nodes.call.BuiltinRootNode) {
                        builtin[0] = name;
                    }
                    return null;
                }
                walk.frames.put(frame, Boolean.TRUE);
                BytecodeLocation location = BytecodeLocation.get(instance);
                // the innermost frame, and one that called Java code calling back into PHP (a destructor at zero),
                // has no call instruction: with block scoping off, every instruction sees the same locals
                com.oracle.truffle.api.bytecode.BytecodeNode bytecode = isInnermost && innermostBytecode != null
                        ? innermostBytecode : location != null ? location.getBytecodeNode()
                        : com.oracle.truffle.api.bytecode.BytecodeNode.get(instance);
                if (bytecode == null) {
                    bytecode = ((BytecodeRootNode) code).getBytecodeNode();
                }
                int index = location != null ? location.getBytecodeIndex() : 0;
                if (bytecode == null) {
                    return null;
                }
                IdentityHashMap<Object, int[]> inLocals = new IdentityHashMap<>();
                try {
                    Object[] locals = bytecode.getLocalValues(index, frame);
                    int variables = code instanceof Refcount.Variables v ? v.variableCount() : locals.length;
                    for (int i = 0; i < locals.length; i++) {
                        // a temporary sent as an argument: php-src moved it into the call
                        int[] sent = i >= variables ? sentTemporaries.get(locals[i]) : null;
                        int weight = sent != null && sent[0] > 0 ? 0 : 1;
                        walk.root(name + (i >= variables ? " temporary " : " local ") + i, locals[i], weight);
                        inLocals.computeIfAbsent(locals[i], o -> new int[1])[0]++;
                    }
                } catch (RuntimeException e) {
                    // a frame whose locals can't be read here
                }
                // the frame's other slots: its operand stack (and stale values, which only widen the slack)
                int slots = frame.getFrameDescriptor().getNumberOfSlots();
                for (int i = 0; i < slots; i++) {
                    Object value;
                    try {
                        value = frame.getValue(i);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (value instanceof PhpObject object) {
                        int[] local = inLocals.get(object);
                        if (local != null && local[0] > 0) {
                            local[0]--;
                        } else {
                            slack.computeIfAbsent(object, o -> new int[1])[0]++;
                        }
                    }
                }
                return null;
            });
        }
        walk.drain();
        walk.fromRoots = false;
        List<Object> tracked = context.auditedObjects();
        for (Object entry : tracked) {
            PhpObject object = ((Tracked) entry).object();
            if (!object.isFreed() && !walk.seen.containsKey(object)) {
                walk.edge(null, "unreachable #" + object.id(), object, 0);
                walk.drain();
            }
        }
        String under = builtin[0] == null ? "" : " (under built-in " + builtin[0] + ")";
        IdentityHashMap<Object, Boolean> stillHeld = walk.held();
        for (Object entry : tracked) {
            Tracked t = (Tracked) entry;
            PhpObject object = t.object();
            if (object.isFreed()) {
                Node held = walk.freedHeld.get(object);
                if (held != null) {
                    f.freedHeld++;
                    add(f, "FREED while held  " + object.getPhpClass().getName() + "  made in " + t.site(), object,
                            "#" + object.id() + " count " + object.refcount() + ", via " + held.path()
                                    + (held.fromRoot ? "" : " (from garbage)") + under);
                }
                continue;
            }
            f.alive++;
            Node node = walk.seen.get(object);
            boolean fromRoot = node != null && node.fromRoot;
            if (fromRoot) {
                f.reachable++;
            }
            if (object.getPhpClass().isEnum()) {
                f.enums++;
                continue;
            }
            int[] counted = walk.holders.get(object);
            int expected = counted == null ? 0 : counted[0];
            int[] extra = slack.get(object);
            int allowed = expected + (extra == null ? 0 : extra[0]);
            int rc = object.refcount();
            String kind;
            if (rc >= expected && rc <= allowed && rc > 0) {
                if (!fromRoot) {
                    f.garbage++;
                }
                continue;
            } else if (rc == 0 && expected == 0) {
                f.zeroUnfreed++;
                kind = "zero, not freed";
            } else if (rc > allowed) {
                f.leaks++;
                // php-src would have destroyed it (no counted holder left), or only its count is higher
                kind = (expected == 0 && !stillHeld.containsKey(object) ? "LATE +" : "EXTRA +") + (rc - allowed);
            } else {
                f.unders++;
                kind = "EARLY -" + (expected - rc);
            }
            add(f, kind + "  " + object.getPhpClass().getName() + "  made in " + t.site(), object,
                    "#" + object.id() + " count " + rc + ", holders " + expected
                            + (allowed > expected ? " (+" + (allowed - expected) + " unnamed)" : "")
                            + (fromRoot ? ", via " + node.path() : ", unreachable") + under);
        }
        return f;
    }

    private static void add(Findings f, String key, PhpObject object, String example) {
        Group group = f.groups.computeIfAbsent(key, k -> new Group());
        if (group.objects.isEmpty()) {
            group.example = example;
            group.audits = 1;
        }
        group.objects.put(object, Boolean.TRUE);
    }

    /** At the script's end (or exit): the last audit, with the points' findings, reported; then all is reset. */
    @TruffleBoundary
    static void audit(PhpContext context, String when) {
        Findings f = check(context, when.equals("exit"), null, null);
        StringBuilder out = new StringBuilder();
        out.append("rc-audit (").append(when).append("): ").append(f.alive).append(" objects alive, ")
                .append(f.reachable).append(" reachable, ").append(f.garbage).append(" in garbage cycles, ")
                .append(f.enums).append(" enum cases; ").append(f.leaks).append(" leaked, ").append(f.unders)
                .append(" undercounted, ").append(f.zeroUnfreed).append(" at zero not freed; ")
                .append(f.freedHeld).append(" freed while held; ").append(f.groups.size()).append(" groups")
                .append(DESTRUCTORS ? "" : " (destructors off: zeros free nothing)").append('\n');
        report(out, f.groups, false);
        if (AUDIT_POINTS) {
            out.append("rc-audit (").append(pointAudits).append(" points, every ").append(AUDIT_EVERY)
                    .append(" statement ends): ").append(pointGroups.size()).append(" groups\n");
            report(out, pointGroups, true);
            pointGroups.clear();
            pointAudits = 0;
        }
        if (AUDIT_OUT != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(AUDIT_OUT), out,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException e) {
                System.err.print(out);
            }
        } else {
            System.err.print(out);
        }
        context.auditedObjects().clear();
    }

    private static void report(StringBuilder out, Map<String, Group> groups, boolean points) {
        groups.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().objects.size(), a.getValue().objects.size()))
                .limit(AUDIT_GROUPS)
                .forEach(e -> out.append("  ").append(e.getValue().objects.size()).append(" x ").append(e.getKey())
                        .append(points ? "  (at " + e.getValue().audits + " points)" : "")
                        .append("\n      e.g. ").append(e.getValue().example).append('\n'));
    }

    /** The PHP calls running, innermost first. */
    private static String calls() {
        List<String> names = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(instance -> {
            names.add(((RootCallTarget) instance.getCallTarget()).getRootNode().getName());
            return names.size() >= 8 ? names : null;
        });
        return String.join(" < ", names);
    }
}
