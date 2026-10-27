---------------------------- MODULE SharedEngine ----------------------------
(***************************************************************************)
(* Per-request state in Truffle PHP's shared engine (wiki/tla-engine.md).  *)
(*                                                                         *)
(* HttpMain runs each request in a new context on one Engine, on a pool of *)
(* threads. The translated ASTs (and their specialized nodes) belong to    *)
(* the language and are shared by every context and thread; PHP state      *)
(* (classes, globals, ini, streams...) belongs to a context. This model    *)
(* keeps the mechanisms through which state can cross from one request to  *)
(* another:                                                                *)
(*   - node caches filled on first execution and reused afterwards, in the *)
(*     forms the runtime has: a value kept under the single-context        *)
(*     assumption (ClassRefNode.single and the like), a DSL inline cache   *)
(*     on a language-level key (method calls, callables), and a field a    *)
(*     node writes by hand (PropNode's accessible class); a DSL cache is   *)
(*     specialized in two steps, so two threads can specialize one node at *)
(*     once;                                                               *)
(*   - process-wide state: flock()'s lock table, and the compiled-pattern  *)
(*     cache with each context's native match blocks;                      *)
(*   - context creation (Truffle's initializeMultipleContexts) and close   *)
(*     (finalizeContext, also after exit()).                               *)
(* Everything else a context owns (globals, statics, ini, handlers,        *)
(* output, ids) is reached only through the context, so it is abstracted  *)
(* to the ownership tag on values.                                         *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
    Threads,        \* the server's threads
    Requests,       \* the requests; each runs in its own context
    Ops,            \* how many operations a request performs
    ExplicitEngine, \* the contexts share an Engine made with Engine.create() (HttpMain, SharedContexts)
    Fixed,          \* the runtime after this model's findings were fixed (FALSE: as it was at b4f3f52)
    NaiveKey        \* with Fixed: PropNode keeps the key for any access, also one the key doesn't decide

None == "none"
Lang == "lang"

(* Class Foo has two declarations, as a conditionally declared class has: one   *)
(* request declares it from T1, another from T2. Their ClassKeys differ. T1    *)
(* declares __call and __callStatic, T2 only __callStatic.                      *)
Templates == {"T1", "T2"}
HasCall(t) == t = "T1"
(* The parent the lexical class of a property access has in a request (the    *)
(* same template, extending a class declared differently).                     *)
Parents == {"P1", "P2"}

Nodes == {"classRef", "method", "callable"}

VARIABLES
    rstate,     \* each request: queued, running or closed
    decl,       \* the template a request declared Foo from
    parent,     \* the parent of the lexical class of the property access, per request
    contexts,   \* how many contexts were created
    single,     \* PhpLanguage.singleContext: valid until initializeMultipleContexts
    thr,        \* each thread: the request it runs, operations left, a specialization in progress
    cache,      \* the DSL caches of shared nodes
    prop,       \* PropNode's hand-written field (accessibleClass, or accessibleKey once fixed)
    lock,       \* FileLocks.FILES for one file: the request holding it, or None
    views,      \* requests holding native match blocks (PcreFunctions.regexes)
    isoBad,     \* ghost: a request read a value another request owns
    hitBad      \* ghost: a cache hit gave what the uncached path would not have

vars == <<rstate, decl, parent, contexts, single, thr, cache, prop, lock, views, isoBad, hitBad>>

(* Values. A per-context object (a PhpClass) is owned by its request; language-level values are Lang's. *)
Val(o, k) == [owner |-> o, key |-> k]
NoVal == Val(Lang, None)
ClsVal(r) == Val(r, decl[r])                     \* the request's PhpClass for Foo
MethodVal(k) == Val(Lang, k)                     \* the PhpFunction (and call target) the key determines
(* What ['Foo', 'missing'] resolves to (Callables.method): from code running on a Foo, __call, or nothing     *)
(* (the TypeError of a callable that isn't one) when Foo has no __call; elsewhere __callStatic.                *)
Target(k, this) == Val(Lang, IF this THEN (IF HasCall(k) THEN "__call" ELSE "error") ELSE "__callStatic")
IsStatic(v) == v.key = "__callStatic"

EmptyCache == [st |-> "uninit", key |-> None, val |-> NoVal]
NoPend == [node |-> None, key |-> None, val |-> NoVal, cacheable |-> FALSE]
Idle == [req |-> None, ops |-> 0, pend |-> NoPend]

Init ==
    /\ rstate = [r \in Requests |-> "queued"]
    /\ decl = [r \in Requests |-> None]
    /\ parent = [r \in Requests |-> None]
    /\ contexts = 0
    \* an explicit engine: Truffle calls initializeMultipleContexts before the first context
    /\ single = ~ExplicitEngine
    /\ thr = [t \in Threads |-> Idle]
    /\ cache = [n \in Nodes |-> EmptyCache]
    /\ prop = NoVal
    /\ lock = None
    /\ views = {}
    /\ isoBad = FALSE
    /\ hitBad = FALSE

(* A hit: the value a cache gave request r, against what the uncached path gives. *)
Check(r, v, expected) ==
    /\ isoBad' = (isoBad \/ v.owner \notin {r, Lang})
    /\ hitBad' = (hitBad \/ v # expected)

Done(t) == thr' = [thr EXCEPT ![t].ops = @ - 1, ![t].pend = NoPend]

(* A thread takes a queued request and creates its context. Truffle's contract: initializeMultipleContexts *)
(* before a second context exists (an explicit engine: before the first, see Init).                        *)
Create(t, r) ==
    /\ thr[t].req = None
    /\ rstate[r] = "queued"
    \* a bound engine (Context.create() with no Engine) refuses a second context
    /\ ExplicitEngine \/ contexts = 0
    /\ \E d \in Templates, p \in Parents :
        /\ decl' = [decl EXCEPT ![r] = d]
        /\ parent' = [parent EXCEPT ![r] = p]
    /\ rstate' = [rstate EXCEPT ![r] = "running"]
    /\ contexts' = contexts + 1
    /\ single' = (single /\ contexts = 0)
    /\ thr' = [thr EXCEPT ![t] = [req |-> r, ops |-> Ops, pend |-> NoPend]]
    /\ UNCHANGED <<cache, prop, lock, views, isoBad, hitBad>>

Running(t) == thr[t].req # None /\ thr[t].ops > 0 /\ thr[t].pend.node = None

(* `Foo::` (ClassRefNode.Named): the class kept under the single-context assumption, else the context's by id. *)
ClassRef(t) ==
    LET r == thr[t].req  c == cache["classRef"] IN
    /\ Running(t)
    /\ \/ /\ c.st = "single" /\ single
          /\ Check(r, c.val, ClsVal(r))
          /\ Done(t)
          /\ UNCHANGED cache
       \/ /\ c.st = "single" /\ ~single
          \* the assumption failed: the DSL removes the specialization, perContext replaces it
          /\ cache' = [cache EXCEPT !["classRef"] = [st |-> "perContext", key |-> None, val |-> NoVal]]
          /\ Done(t)
          /\ UNCHANGED <<isoBad, hitBad>>
       \/ /\ c.st = "uninit" /\ single
          \* executeAndSpecialize: find() in this context; installed in a second step
          /\ thr' = [thr EXCEPT ![t].pend = [node |-> "classRef", key |-> None, val |-> ClsVal(r),
                                             cacheable |-> TRUE]]
          /\ UNCHANGED <<cache, isoBad, hitBad>>
       \/ /\ (c.st = "uninit" /\ ~single) \/ c.st = "perContext"
          /\ cache' = [cache EXCEPT !["classRef"] = [st |-> "perContext", key |-> None, val |-> NoVal]]
          /\ Done(t)
          /\ UNCHANGED <<isoBad, hitBad>>
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, prop, lock, views>>

(* A DSL inline cache on a language-level key (limit 1): hit when the guard holds, else specialize. *)
Keyed(t, n, key, expected, cacheable) ==
    LET r == thr[t].req  c == cache[n] IN
    /\ Running(t)
    /\ \/ /\ c.st = "cached" /\ c.key = key
          /\ Check(r, c.val, expected)
          /\ Done(t)
       \/ /\ c.st = "generic"
          /\ Done(t)
          /\ UNCHANGED <<isoBad, hitBad>>
       \/ /\ c.st = "uninit" \/ (c.st = "cached" /\ c.key # key)
          /\ thr' = [thr EXCEPT ![t].pend = [node |-> n, key |-> key, val |-> expected, cacheable |-> cacheable]]
          /\ UNCHANGED <<isoBad, hitBad>>
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, cache, prop, lock, views>>

(* `$obj->m()` (MethodCallNode.Dispatch): the method is what the receiver's ClassKey determines. *)
Method(t) == Keyed(t, "method", decl[thr[t].req], MethodVal(decl[thr[t].req]), TRUE)

(* call_user_func(['Foo', 'missing']) (CallableDispatchNode.classMethod), from code running on a Foo or not  *)
(* (the same closure, bound and unbound). The entry was kept for a static target, which __callStatic is;     *)
(* fixed, a method standing in for a missing one is never kept. A target that throws installs nothing.      *)
Callable(t) ==
    \E this \in BOOLEAN :
        LET k == decl[thr[t].req]
            v == Target(k, this)
        IN Keyed(t, "callable", k, v, ~Fixed /\ IsStatic(v))

(* The second step of a DSL specialization, atomic: install the entry unless another thread has            *)
(* installed one meanwhile; one that doesn't match, or a target that may not be kept, makes the node generic. *)
(* The thread's own call uses what it computed.                                                              *)
Install(t) ==
    LET p == thr[t].pend  c == cache[p.node] IN
    /\ thr[t].req # None
    /\ p.node \in Nodes
    /\ cache' = [cache EXCEPT ![p.node] =
                    IF p.node = "classRef"
                    THEN IF c.st = "uninit" THEN [st |-> "single", key |-> None, val |-> p.val] ELSE c
                    ELSE IF p.val.key = "error" THEN c
                    ELSE IF ~p.cacheable THEN [st |-> "generic", key |-> None, val |-> NoVal]
                    ELSE IF c.st = "uninit" THEN [st |-> "cached", key |-> p.key, val |-> p.val]
                    ELSE IF c.st = "cached" /\ c.key = p.key THEN c
                    ELSE [st |-> "generic", key |-> None, val |-> NoVal]]
    /\ Done(t)
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, prop, lock, views, isoBad, hitBad>>

(* `$obj->x` of a non-public property in a method (PropNode.accessible), the object a Foo. Whether the       *)
(* method's class may access it is decided by the object's ClassKey (KeyDetermined: a private property, a     *)
(* protected one of an ancestor of the scope), or else also by the lexical class's own ancestors, which       *)
(* depend on the parent it was declared with in the request. The node's field is read and written without a   *)
(* lock: two threads can both see it empty and both write. As it was, the field kept the object's PhpClass;   *)
(* fixed, it keeps the ClassKey, and only for an access the key decides.                                     *)
KeyDetermined(k) == k = "T2"
Allowed(r) == KeyDetermined(decl[r]) \/ parent[r] = "P1"
Prop(t) ==
    LET r == thr[t].req
        mine == IF Fixed THEN Val(Lang, decl[r]) ELSE ClsVal(r)
    IN /\ Running(t)
       /\ \/ /\ prop = mine
             \* the fast path: allowed without asking the class
             /\ isoBad' = (isoBad \/ prop.owner \notin {r, Lang})
             /\ hitBad' = (hitBad \/ ~Allowed(r))
             /\ Done(t)
          \/ /\ prop # mine
             \* the slow path (canAccessProperty); an empty field is filled in a second step
             /\ thr' = [thr EXCEPT ![t].pend =
                           [node |-> "prop", key |-> None, val |-> mine,
                            cacheable |-> prop = NoVal /\ Allowed(r)
                                         /\ (~Fixed \/ NaiveKey \/ KeyDetermined(decl[r]))]]
             /\ UNCHANGED <<isoBad, hitBad>>
       /\ UNCHANGED <<rstate, decl, parent, contexts, single, cache, prop, lock, views>>

PropWrite(t) ==
    /\ thr[t].pend.node = "prop"
    /\ prop' = IF thr[t].pend.cacheable THEN thr[t].pend.val ELSE prop
    /\ Done(t)
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, cache, lock, views, isoBad, hitBad>>

(* flock($f, LOCK_EX | LOCK_NB) on one file: the JVM-wide table, as the kernel's is machine-wide. *)
Flock(t) ==
    LET r == thr[t].req IN
    /\ Running(t)
    /\ lock' = IF lock = None THEN r ELSE lock
    /\ Done(t)
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, cache, prop, views, isoBad, hitBad>>

(* preg_match: the compiled pattern is the language's (immutable); the match block is the context's. *)
Match(t) ==
    /\ Running(t)
    /\ views' = views \cup {thr[t].req}
    /\ Done(t)
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, cache, prop, lock, isoBad, hitBad>>

(* exit(): the script ends; the context closes as after its last statement. *)
Exit(t) ==
    /\ Running(t)
    /\ thr' = [thr EXCEPT ![t].ops = 0]
    /\ UNCHANGED <<rstate, decl, parent, contexts, single, cache, prop, lock, views, isoBad, hitBad>>

(* Context.close: finalizeContext ends the request and BuiltinState.close releases what it holds. *)
Close(t) ==
    LET r == thr[t].req IN
    /\ r # None
    /\ thr[t].ops = 0
    /\ thr[t].pend.node = None
    /\ rstate' = [rstate EXCEPT ![r] = "closed"]
    /\ lock' = IF lock = r THEN None ELSE lock
    /\ views' = views \ {r}
    /\ thr' = [thr EXCEPT ![t] = Idle]
    \* a bound engine closes with its one context, and its ASTs go with it
    /\ cache' = IF ExplicitEngine THEN cache ELSE [n \in Nodes |-> EmptyCache]
    /\ prop' = IF ExplicitEngine THEN prop ELSE NoVal
    /\ UNCHANGED <<decl, parent, contexts, single, isoBad, hitBad>>

Next ==
    \/ \E t \in Threads, r \in Requests : Create(t, r)
    \/ \E t \in Threads :
        \/ ClassRef(t) \/ Method(t) \/ Callable(t) \/ Install(t) \/ Prop(t) \/ PropWrite(t)
        \/ Flock(t) \/ Match(t) \/ Exit(t) \/ Close(t)

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties *)

TypeOK ==
    /\ rstate \in [Requests -> {"queued", "running", "closed"}]
    /\ contexts \in 0..Cardinality(Requests)
    /\ single \in BOOLEAN
    /\ lock \in Requests \cup {None}
    /\ views \subseteq Requests

(* Nothing one request does is observable by another: no request reads a value another request owns. *)
Isolation == ~isoBad

(* Every cache hit in a shared node is what the uncached path gives the context (and call) reading it. *)
ValidHits == ~hitBad

(* A closed context leaves nothing behind: no shared node, lock or native block refers to it. *)
Retained(r) ==
    \/ \E n \in Nodes : cache[n].val.owner = r
    \/ prop.owner = r
    \/ lock = r
    \/ r \in views
CloseReleases == \A r \in Requests : rstate[r] = "closed" => ~Retained(r)

(* The single-context assumption is gone before a second context exists. *)
SingleUntilSecond == contexts > 1 => ~single

=============================================================================
