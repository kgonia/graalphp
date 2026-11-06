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
