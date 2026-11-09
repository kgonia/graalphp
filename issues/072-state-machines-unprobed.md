# 072: Implicit state machines with likely defects, not probed yet

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**What:** the audit gave a concrete scenario for each of these. Each needs a server, sockets, processes or a crafted
failure to show, so none has a smoke test yet. Lines are on main 438231d.

| Area | Scenario | Where |
|---|---|---|
| Processes | `proc_open` pipes and the child are never cleaned up at request end (the pipes aren't in `openStreams`): a descriptor leak and a zombie per request. Child pipe ends can be inherited by other threads' spawns | `ExecFunctions.java:674-815`, `986-1047` |
| Sockets, TLS | `blocked` and `hasBufferedData` aren't restored when an error handler throws; `stream_socket_enable_crypto` on a never-connected socket throws NullPointerException | `SslSocketStream.java:615-1070`, `FileFunctions.java:93-280` |
| curl | `curl_multi_close` iterates `mh.easyh` while a warning can run a handler that adds a handle (ConcurrentModificationException); a denied server push leaves `cp == null`, and later calls throw NullPointerException | `CurlFunctions.java:2018-2106` |
| mysqli | "never connected" and "closed" are both `connection == null`: NullPointerException instead of "mysqli object is already closed", a second close returns true, results are usable after free | `MysqliFunctions.java:43-533` |
| Request end | `exit()` calls `endRequest` unguarded: a throwing output handler's exception escapes `exit` and user code can catch it. Its shutdown also runs before the frames unwind (073) | `PhpContext.java:766-771` |
| Request end | `destroyGenerators` walks by index while `removeIf(isClosed)` can shift the list (a probe with 70 generators matched PHP; the trigger needs a finally that makes generators while the list is pruned) | `PhpContext.java:807-835` |
| Engine | the `strict_types` registry only grows: a file once strict stays strict after an edit removes the declare (a long-running server) | `PhpLanguage.java:233-249` |
| Engine | the prelude file is marked loaded before it runs: a failure part-way is never retried | `PhpContext.java:484-515` |
| Classes | `canEarlyBind` resets `compiledFile` to null instead of the previous value; a throwing autoloader in `Variance`'s delayed loop leaves a class declared without `checkAbstract` | `Variance.java:61-93` |
| Objects | lazy objects: readonly re-initialization marks can survive `__clone`'s cleanup | `PhpObject.java:395-519` |
| syslog | `openlog`'s flag is per context, over process-wide state | `Syslog.java:84-87` |
| Translator | `writeFetched` is never cleared: a node translated again in a read role loses its "Undefined variable" warning | `BytecodeTranslator.java:395` |

**Fix:** probe each one. Give it its own issue once it is confirmed, or close it here as matching PHP.
