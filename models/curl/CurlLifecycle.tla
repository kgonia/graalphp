---------------------------- MODULE CurlLifecycle ----------------------------
(***************************************************************************)
(* The lifecycle of ext/curl's handles in Truffle PHP                      *)
(* (src/main/java/pl/codeloom/php/builtins/CurlFunctions.java), against    *)
(* php-src 8.5.12's ext/curl (interface.c, multi.c, share.c).              *)
(*                                                                         *)
(* What the code does: a CurlHandle's PHP object holds an Easy, whose      *)
(* native part (EasyNative: the CURL*, the error buffer's arena, the       *)
(* ToFree its clones share) sits in the context's State.live. A            *)
(* java.lang.ref.Cleaner watches one Java object per handle; when it is    *)
(* unreachable, the Cleaner's thread queues the native part on             *)
(* State.released, and the context's thread frees it at its next curl call *)
(* (State.drain) or when the context closes (State.close). The Cleaner     *)
(* watches the Easy for a CurlHandle, but the PHP object for a             *)
(* CurlMultiHandle and a CurlShareHandle (whose Multi is itself the native *)
(* part in State.live, and whose Share an Easy holds for CURLOPT_SHARE).   *)
(*                                                                         *)
(* php-src frees a handle when its refcount drops to zero: the variables   *)
(* holding it, the arguments of a call running (the call frame holds a     *)
(* ref), a multi's easyh (Z_ADDREF in curl_multi_add_handle), an easy's    *)
(* share (GC_ADDREF in CURLOPT_SHARE). PhpAlive below is that refcount.    *)
(*                                                                         *)
(* A built-in runs in a Truffle frame whose arguments array holds the      *)
(* values passed (debug_backtrace() reads them from it), so the PHP        *)
(* objects passed to a curl function stay reachable until it returns, as   *)
(* php-src's call frame holds them. FRAME_ARGS = FALSE drops that, leaving *)
(* only what the Java code itself keeps reachable (its locals used after a *)
(* call), to show what the code relies on.                                 *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
    Ctx,        \* the contexts (a PHP request each)
    Thr,        \* the threads
    K,          \* handle slots per context
    MaxCb,      \* callbacks into PHP per curl call
    FRAME_ARGS, \* see above
    MIGRATE,    \* a context may move to another thread between its calls
    DUP_SHARE,  \* curl_easy_duphandle attaches the copy to the source's share
    None        \* a model value

ASSUME K \in Nat /\ MaxCb \in Nat /\ FRAME_ARGS \in BOOLEAN /\ MIGRATE \in BOOLEAN
       /\ DUP_SHARE \in BOOLEAN

Hs == Ctx \X (1..K)                    \* a context's handles
PNat == {<<t, 0>> : t \in Thr}         \* the persistent shares' natives, one per thread
Owner(h) == h[1]
Kinds == {"unused", "easy", "multi", "share", "pshare"}

VARIABLES
    kind,      \* [Hs -> Kinds]; "pshare": a CurlSharePersistentHandle
    rooted,    \* [Hs -> BOOLEAN]: PHP variables (or arrays, properties) hold the object
    easyh,     \* [Hs -> SUBSET Hs]: a multi's easyh (the CurlHandle objects added)
    shareOf,   \* [Hs -> Hs \cup {None}]: Easy.share (php_curl.share)
    attached,  \* [Hs -> Hs \cup PNat \cup {None}]: libcurl's data->share of an easy
    lmulti,    \* [Hs -> Hs \cup {None}]: libcurl's data->multi of an easy
    pnat,      \* [Hs -> PNat \cup {None}]: the native of a persistent share object
    group,     \* [Hs -> Hs]: the ToFree an easy shares with its clones (by its first handle)
    toFreeDone,\* [Hs -> BOOLEAN]: a ToFree's resources freed (mime, MIME_SOURCES, slists)
    nat,       \* [Hs -> {"none", "live", "freed", "leaked"}]: the native handle; leaked: our
               \* pointer dropped but libcurl refused to free it
    inLive,    \* [Hs -> BOOLEAN]: in State.live
    enq,       \* [Hs -> BOOLEAN]: the Cleaner ran the handle's action (once per registration)
    queue,     \* [Ctx -> SUBSET Hs]: State.released
    deferred,  \* [Ctx -> SUBSET Hs]: State.deferredShares
    closed,    \* [Ctx -> BOOLEAN]
    thread,    \* [Ctx -> Thr]
    call,      \* [Ctx -> record or None]: the curl call running on the context's thread
    bad        \* the violations seen (a ghost)

vars == <<kind, rooted, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat, inLive,
          enq, queue, deferred, closed, thread, call, bad>>

-----------------------------------------------------------------------------
(* Reachability in the JVM: what keeps the object the Cleaner watches alive *)

\* The PHP objects the frames of a curl call hold: its arguments (in its Truffle frame), and the
\* CurlHandle of the callback running (the upcall's ch, passed to the callable).
FrameObjects(c) ==
    IF call[c] = None THEN {}
    ELSE (IF FRAME_ARGS THEN call[c].args ELSE {})
         \cup (IF call[c].phase = "cb" THEN {call[c].cbh} ELSE {})

\* The Java objects a curl call's frames hold: those, and the internals its Java locals hold after
\* libcurl returns (an Easy or a Multi, not the PHP object).
FrameHeld(c) == FrameObjects(c) \cup (IF call[c] = None THEN {} ELSE call[c].frame)

\* A Multi's easyh list is reachable while the Multi is: from State.live (the Multi is the native
\* part), from the Cleaner's action or State.released until drained, from a frame, or from its PHP
\* object.
EasyhHeld(m) ==
    \/ inLive[m]
    \/ (nat[m] = "live" /\ ~enq[m])
    \/ m \in queue[Owner(m)]
    \/ m \in FrameHeld(Owner(m))
    \/ rooted[m]

EasyAlive(e) ==
    \/ rooted[e]
    \/ e \in FrameHeld(Owner(e))
    \/ \E m \in Hs : kind[m] = "multi" /\ e \in easyh[m] /\ EasyhHeld(m)

\* The object the Cleaner watches is reachable.
Watched(h) ==
    CASE kind[h] = "easy"  -> EasyAlive(h)
      [] kind[h] = "multi" -> rooted[h] \/ h \in FrameObjects(Owner(h))
      [] kind[h] = "share" -> rooted[h] \/ h \in FrameObjects(Owner(h))
      [] OTHER -> TRUE

(* php-src's refcount *)
PhpArgs(c) == IF call[c] = None THEN {}
              ELSE call[c].args \cup (IF call[c].phase = "cb" THEN {call[c].cbh} ELSE {})
PhpMultiAlive(m) == rooted[m] \/ m \in PhpArgs(Owner(m))
PhpEasyAlive(e) == rooted[e] \/ e \in PhpArgs(Owner(e))
                   \/ \E m \in Hs : kind[m] = "multi" /\ e \in easyh[m] /\ PhpMultiAlive(m)
PhpShareAlive(s) == rooted[s] \/ s \in PhpArgs(Owner(s))
                    \/ \E e \in Hs : kind[e] = "easy" /\ shareOf[e] = s /\ PhpEasyAlive(e)
PhpAlive(h) ==
    CASE kind[h] = "easy"  -> PhpEasyAlive(h)
      [] kind[h] = "multi" -> PhpMultiAlive(h)
      [] kind[h] = "share" -> PhpShareAlive(h)
      [] OTHER -> TRUE

ShareNative(s) == IF kind[s] = "pshare" THEN pnat[s] ELSE s
Running(c) == call[c] # None

\* The context's thread runs PHP code (and so may call any curl function): between calls, or in a
\* callback or an error handler inside one.
InPhp(c) == ~closed[c] /\ IF call[c] = None THEN TRUE ELSE call[c].phase \in {"cb", "warn"}

\* The shares libcurl reaches from easies (data->share, read when a transfer needs it).
SharesOf(es) == {attached[e] : e \in es} \ {None}

\* The handles libcurl is running in a call (perform, pause) on any thread, and the natives
\* (with the shares the transfers use) of a context's call.
InFlight == UNION {call[c].inflight : c \in {d \in Ctx : Running(d)}}
Natives(c) == call[c].inflight \cup SharesOf(call[c].inflight)

-----------------------------------------------------------------------------
Init ==
    /\ kind = [h \in Hs |-> "unused"]
    /\ rooted = [h \in Hs |-> FALSE]
    /\ easyh = [h \in Hs |-> {}]
    /\ shareOf = [h \in Hs |-> None]
    /\ attached = [h \in Hs |-> None]
    /\ lmulti = [h \in Hs |-> None]
    /\ pnat = [h \in Hs |-> None]
    /\ group = [h \in Hs |-> h]
    /\ toFreeDone = [h \in Hs |-> FALSE]
    /\ nat = [h \in Hs |-> "none"]
    /\ inLive = [h \in Hs |-> FALSE]
    /\ enq = [h \in Hs |-> FALSE]
    /\ queue = [c \in Ctx |-> {}]
    /\ deferred = [c \in Ctx |-> {}]
    /\ closed = [c \in Ctx |-> FALSE]
    /\ thread \in [Ctx -> Thr]
    /\ \A c, d \in Ctx : c # d => thread[c] # thread[d]
    /\ call = [c \in Ctx |-> None]
    /\ bad = {}

Use(hs) == IF \E h \in hs : h \in Hs /\ nat[h] # "live"
           THEN {"a native handle used after it was freed"} ELSE {}

FreeSlot(c) == {h \in Hs : Owner(h) = c /\ kind[h] = "unused"}

\* curl_init, curl_multi_init, curl_share_init (State.track)
New(c, k) ==
    /\ InPhp(c)
    /\ \E h \in FreeSlot(c) :
        /\ kind' = [kind EXCEPT ![h] = k]
        /\ rooted' = [rooted EXCEPT ![h] = TRUE]
        /\ nat' = [nat EXCEPT ![h] = "live"]
        /\ inLive' = [inLive EXCEPT ![h] = TRUE]
    /\ UNCHANGED <<easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, enq, queue,
                   deferred, closed, thread, call, bad>>

\* curl_share_init_persistent: PERSISTENT_SHARES' handle of the current thread, not tracked
NewPersistent(c) ==
    /\ InPhp(c)
    /\ \E h \in FreeSlot(c) :
        /\ kind' = [kind EXCEPT ![h] = "pshare"]
        /\ rooted' = [rooted EXCEPT ![h] = TRUE]
        /\ pnat' = [pnat EXCEPT ![h] = <<thread[c], 0>>]
    /\ UNCHANGED <<easyh, shareOf, attached, lmulti, group, toFreeDone, nat, inLive, enq, queue,
                   deferred, closed, thread, call, bad>>

\* unset(), reassignment, a scope's end: the last PHP reference outside the curl internals goes
Drop(h) ==
    /\ InPhp(Owner(h))
    /\ rooted[h]
    /\ rooted' = [rooted EXCEPT ![h] = FALSE]
    /\ UNCHANGED <<kind, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat, inLive,
                   enq, queue, deferred, closed, thread, call, bad>>

\* curl_copy_handle (cloneHandle, setupEasyCopyHandlers: the clone shares the ToFree)
Copy(e) ==
    /\ InPhp(Owner(e))
    /\ kind[e] = "easy" /\ rooted[e]
    /\ \E h \in FreeSlot(Owner(e)) :
        /\ kind' = [kind EXCEPT ![h] = "easy"]
        /\ rooted' = [rooted EXCEPT ![h] = TRUE]
        /\ nat' = [nat EXCEPT ![h] = "live"]
        /\ inLive' = [inLive EXCEPT ![h] = TRUE]
        /\ group' = [group EXCEPT ![h] = group[e]]
        /\ attached' = [attached EXCEPT ![h] = IF DUP_SHARE THEN attached[e] ELSE None]
    /\ bad' = bad \cup Use({e})
    /\ UNCHANGED <<easyh, shareOf, lmulti, pnat, toFreeDone, enq, queue, deferred, closed,
                   thread, call>>

\* curl_setopt($ch, CURLOPT_SHARE, $sh)
SetShare(e, s) ==
    /\ InPhp(Owner(e))
    /\ Owner(s) = Owner(e)
    /\ kind[e] = "easy" /\ kind[s] \in {"share", "pshare"} /\ rooted[e] /\ rooted[s]
    /\ attached' = [attached EXCEPT ![e] = ShareNative(s)]
    /\ shareOf' = [shareOf EXCEPT ![e] = s]
    /\ bad' = bad \cup Use({e, ShareNative(s)})
    /\ UNCHANGED <<kind, rooted, easyh, lmulti, pnat, group, toFreeDone, nat, inLive, enq, queue,
                   deferred, closed, thread, call>>

\* curl_multi_remove_handle (between calls: from a callback libcurl refuses it, CURLM_RECURSIVE_API_CALL)
MultiRemove(m, e) ==
    /\ ~closed[Owner(m)] /\ call[Owner(m)] = None
    /\ Owner(e) = Owner(m)
    /\ kind[m] = "multi" /\ kind[e] = "easy" /\ rooted[m] /\ rooted[e]
    /\ lmulti[e] = m
    /\ lmulti' = [lmulti EXCEPT ![e] = None]
    /\ easyh' = [easyh EXCEPT ![m] = @ \ {e}]
    /\ bad' = bad \cup Use({m, e})
    /\ UNCHANGED <<kind, rooted, shareOf, attached, pnat, group, toFreeDone, nat, inLive, enq,
                   queue, deferred, closed, thread, call>>

-----------------------------------------------------------------------------
(* Calls that run PHP code before libcurl returns: callbacks (upcalls), and *)
(* the error handler a warning calls (_php_curl_verify_handlers).          *)

Begin(c, rec) ==
    /\ ~closed[c] /\ call[c] = None
    /\ call' = [call EXCEPT ![c] = rec]
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat,
                   inLive, enq, queue, deferred, closed, thread, bad>>

Rec(op, h, h2, args, frame, cbs, inflight, post, phase) ==
    [op |-> op, h |-> h, h2 |-> h2, args |-> args, frame |-> frame, cbs |-> cbs,
     inflight |-> inflight, post |-> post, phase |-> phase, cbh |-> None, n |-> 0]

\* curl_exec: libcurl runs the easy; curlExec uses ch after easyPerform returns
Exec(e) ==
    /\ kind[e] = "easy" /\ rooted[e] /\ lmulti[e] = None
    /\ Begin(Owner(e), Rec("exec", e, None, {e}, {e}, {e}, {e}, {}, "lib"))

\* curl_pause: unpausing delivers the data held back; curlPause doesn't use ch after libcurl returns.
\* Only a transfer running has data held back: in a multi (whose easyh holds the easy), or in
\* curl_exec (whose frame holds it; nested calls aren't modelled), so callbacks come only for an
\* easy in a multi.
Pause(e) ==
    /\ kind[e] = "easy" /\ rooted[e]
    /\ Begin(Owner(e), Rec("pause", e, None, {e}, {}, IF lmulti[e] # None THEN {e} ELSE {},
                           {e}, {}, "lib"))

\* curl_multi_exec: the frame holds the Multi (mh.errNo after multiPerform), not its PHP object
MultiExec(m) ==
    /\ kind[m] = "multi" /\ rooted[m]
    /\ Begin(Owner(m), Rec("mexec", m, None, {m}, {m}, easyh[m], {m} \cup easyh[m], {}, "lib"))

\* curl_multi_add_handle: a warning from _php_curl_verify_handlers first, then libcurl
MultiAdd(m, e) ==
    /\ Owner(e) = Owner(m)
    /\ kind[m] = "multi" /\ kind[e] = "easy" /\ rooted[m] /\ rooted[e] /\ lmulti[e] = None
    /\ Begin(Owner(m), Rec("madd", m, e, {m, e}, {m, e}, {}, {}, {m, e}, "warn"))

\* curl_multi_close: a warning per easy, then curl_multi_remove_handle for each
MultiClose(m) ==
    /\ kind[m] = "multi" /\ rooted[m]
    /\ Begin(Owner(m), Rec("mclose", m, None, {m}, {m}, {}, {}, {m} \cup easyh[m],
                           IF easyh[m] = {} THEN "lib" ELSE "warn"))

\* libcurl calls back: the upcall looks the Easy up in EASIES (a WeakReference); a collected
\* Easy means the callback is skipped (easyOrNull), where php-src would run it
EnterCb(c, e) ==
    /\ ~closed[c] /\ Running(c)
    /\ call[c].phase = "lib" /\ call[c].n < MaxCb
    /\ e \in call[c].cbs
    /\ IF EasyAlive(e)
       THEN /\ call' = [call EXCEPT ![c].phase = "cb", ![c].cbh = e, ![c].n = @ + 1]
            /\ UNCHANGED bad
       ELSE /\ call' = [call EXCEPT ![c].n = @ + 1]
            /\ bad' = bad \cup {"a callback skipped: the Easy was collected while libcurl ran it"}
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat,
                   inLive, enq, queue, deferred, closed, thread>>

LeaveCb(c) ==
    /\ Running(c) /\ call[c].phase = "cb"
    /\ call' = [call EXCEPT ![c].phase = "lib", ![c].cbh = None]
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat,
                   inLive, enq, queue, deferred, closed, thread, bad>>

\* libcurl returns; then the post-callback native calls
End(c) ==
    /\ ~closed[c] /\ Running(c) /\ call[c].phase \in {"lib", "warn"}
    /\ LET r == call[c] IN
       /\ bad' = bad \cup Use(Natives(c) \cup r.post)
       /\ CASE r.op = "madd" /\ nat[r.h] = "live" /\ nat[r.h2] = "live" ->
                 /\ easyh' = [easyh EXCEPT ![r.h] = @ \cup {r.h2}]
                 /\ lmulti' = [lmulti EXCEPT ![r.h2] = r.h]
            [] r.op = "mclose" /\ nat[r.h] = "live" ->
                 /\ easyh' = [easyh EXCEPT ![r.h] = {}]
                 /\ lmulti' = [e \in Hs |-> IF lmulti[e] = r.h THEN None ELSE lmulti[e]]
            [] OTHER -> UNCHANGED <<easyh, lmulti>>
    /\ call' = [call EXCEPT ![c] = None]
    /\ UNCHANGED <<kind, rooted, shareOf, attached, pnat, group, toFreeDone, nat, inLive, enq,
                   queue, deferred, closed, thread>>

-----------------------------------------------------------------------------
(* Freeing                                                                  *)

\* curl_share_cleanup succeeds once no easy uses the share (CURLSHE_IN_USE otherwise)
ShareInUse(s, att, n) == \E e \in Hs : att[e] = s /\ n[e] = "live"

\* The Cleaner's thread: the watched object is unreachable (any time after that)
Clean(h) ==
    /\ kind[h] \in {"easy", "multi", "share"}
    /\ nat[h] # "none" /\ ~enq[h]
    /\ ~Watched(h)
    /\ enq' = [enq EXCEPT ![h] = TRUE]
    /\ queue' = [queue EXCEPT ![Owner(h)] = @ \cup {h}]
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat,
                   inLive, deferred, closed, thread, call, bad>>

\* State.drain, at the start of a curl function: free(handle) for one queued handle
Drain(c, h) ==
    /\ InPhp(c)
    /\ h \in queue[c]
    /\ queue' = [queue EXCEPT ![c] = @ \ {h}]
    /\ IF ~inLive[h]
       THEN UNCHANGED <<attached, lmulti, toFreeDone, nat, inLive, deferred, bad>>
       ELSE /\ inLive' = [inLive EXCEPT ![h] = FALSE]
            /\ CASE kind[h] = "easy" ->
                      \* EasyNative.free: curl_easy_cleanup (leaves its multi and share), the
                      \* ToFree by the last clone, then the deferred shares retried
                      LET att == [attached EXCEPT ![h] = None]
                          n == [nat EXCEPT ![h] = "freed"]
                          last == ~\E o \in Hs : o # h /\ group[o] = group[h] /\ inLive[o]
                          retry == {s \in deferred[c] : ~ShareInUse(s, att, n)}
                      IN /\ nat' = [x \in Hs |-> IF x \in retry THEN "freed" ELSE n[x]]
                         /\ attached' = att
                         /\ lmulti' = [lmulti EXCEPT ![h] = None]
                         /\ toFreeDone' = IF last THEN [toFreeDone EXCEPT ![group[h]] = TRUE]
                                          ELSE toFreeDone
                         /\ deferred' = [deferred EXCEPT ![c] = @ \ retry]
                         /\ bad' = bad \cup (IF h \in InFlight
                                   THEN {"curl_easy_cleanup of an easy libcurl is running"} ELSE {})
                   [] kind[h] = "multi" ->
                      \* Multi.free: curl_multi_cleanup; from a callback of the same multi libcurl
                      \* refuses (CURLM_RECURSIVE_API_CALL), and the pointer is dropped anyway
                      /\ IF h \in InFlight
                         THEN /\ nat' = [nat EXCEPT ![h] = "leaked"]
                              /\ UNCHANGED lmulti
                         ELSE /\ nat' = [nat EXCEPT ![h] = "freed"]
                              /\ lmulti' = [e \in Hs |-> IF lmulti[e] = h THEN None ELSE lmulti[e]]
                      /\ UNCHANGED <<attached, toFreeDone, deferred, bad>>
                   [] kind[h] = "share" ->
                      \* ShareNative.free: tryCleanup, or deferred while in use
                      /\ IF ShareInUse(h, attached, nat)
                         THEN /\ deferred' = [deferred EXCEPT ![c] = @ \cup {h}]
                              /\ UNCHANGED nat
                         ELSE /\ nat' = [nat EXCEPT ![h] = "freed"]
                              /\ UNCHANGED deferred
                      /\ UNCHANGED <<attached, lmulti, toFreeDone, bad>>
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, pnat, group, enq, closed, thread, call>>

\* State.close (BuiltinState.close, from finalizeContext after the script and its shutdown
\* functions): the released queue cleared, every live handle freed (easy, then multi, then share),
\* the deferred shares retried and then dropped
Close(c) ==
    /\ ~closed[c] /\ call[c] = None
    /\ closed' = [closed EXCEPT ![c] = TRUE]
    /\ queue' = [queue EXCEPT ![c] = {}]
    /\ LET mine(h) == Owner(h) = c /\ inLive[h]
           att == [e \in Hs |-> IF mine(e) /\ kind[e] = "easy" THEN None ELSE attached[e]]
           n1 == [h \in Hs |-> IF mine(h) /\ kind[h] \in {"easy", "multi"} THEN "freed" ELSE nat[h]]
           n2 == [h \in Hs |-> IF (mine(h) \/ h \in deferred[c]) /\ kind[h] = "share"
                               THEN IF ShareInUse(h, att, n1) THEN "leaked" ELSE "freed"
                               ELSE n1[h]]
       IN /\ nat' = n2
          /\ attached' = att
          /\ lmulti' = [e \in Hs |-> IF Owner(e) = c THEN None ELSE lmulti[e]]
          /\ toFreeDone' = [g \in Hs |-> IF Owner(g) = c THEN TRUE ELSE toFreeDone[g]]
    /\ inLive' = [h \in Hs |-> IF Owner(h) = c THEN FALSE ELSE inLive[h]]
    /\ deferred' = [deferred EXCEPT ![c] = {}]
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, pnat, group, enq, thread, call, bad>>

\* An embedder enters the context from another thread between two calls
Migrate(c, t) ==
    /\ MIGRATE /\ ~closed[c] /\ call[c] = None
    /\ \A d \in Ctx : d # c /\ ~closed[d] => thread[d] # t
    /\ thread' = [thread EXCEPT ![c] = t]
    /\ UNCHANGED <<kind, rooted, easyh, shareOf, attached, lmulti, pnat, group, toFreeDone, nat,
                   inLive, enq, queue, deferred, closed, call, bad>>

Next ==
    \/ \E c \in Ctx : \/ \E k \in {"easy", "multi", "share"} : New(c, k)
                      \/ NewPersistent(c)
                      \/ Close(c)
                      \/ LeaveCb(c) \/ End(c)
                      \/ \E e \in Hs : EnterCb(c, e)
                      \/ \E h \in Hs : Drain(c, h)
                      \/ \E t \in Thr : Migrate(c, t)
    \/ \E h \in Hs : Drop(h) \/ Copy(h) \/ Exec(h) \/ Pause(h) \/ MultiExec(h) \/ MultiClose(h)
                     \/ Clean(h)
    \/ \E e, s \in Hs : SetShare(e, s) \/ MultiAdd(s, e) \/ MultiRemove(s, e)

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties                                                               *)

\* No native used after it was freed (by a call, a multi, a share, a copy, a callback in flight),
\* no curl_easy_cleanup under a running transfer, no callback lost
NoBad == bad = {}

\* State.live's guard: a handle is freed once (free() runs only for handles still in live)
NoDoubleFree == \A h \in Hs : nat[h] \in {"freed", "leaked"} => ~inLive[h]

\* After a context closes, nothing of it stays native
NoLeakAfterClose ==
    \A h \in Hs : closed[Owner(h)] /\ kind[h] \in {"easy", "multi", "share"}
                  => nat[h] = "freed" /\ (kind[h] = "easy" => toFreeDone[group[h]])

\* No state of one context is reachable from another: a multi's easies, an easy's share and
\* libcurl's links stay within the context; two threads never run libcurl on the same native at
\* once (a persistent share is per thread and has no lock callbacks)
Isolation ==
    /\ \A m \in Hs : \A e \in easyh[m] : Owner(e) = Owner(m)
    /\ \A e \in Hs : shareOf[e] # None => Owner(shareOf[e]) = Owner(e)
    /\ \A e \in Hs : attached[e] \in Hs => Owner(attached[e]) = Owner(e)
    /\ \A e \in Hs : lmulti[e] # None => Owner(lmulti[e]) = Owner(e)
    /\ \A c, d \in Ctx : c # d /\ Running(c) /\ Running(d)
                         => Natives(c) \cap Natives(d) = {}

\* Nothing is freed while php-src would still hold it
NoEarlyFree == \A h \in Hs : ~closed[Owner(h)] /\ nat[h] \in {"freed", "leaked"} => ~PhpAlive(h)

\* php-src destroys a handle when its refcount drops to zero; at every point PHP code runs after
\* that, what curl_free_obj does (curl_easy_cleanup writing CURLOPT_COOKIEJAR, the CURLOPT_FILE
\* stream's last reference, the share's) has happened
DestroyedWhenPhpDoes ==
    \A h \in Hs : InPhp(Owner(h)) /\ kind[h] \in {"easy", "multi", "share"} /\ nat[h] # "none"
                  /\ ~PhpAlive(h) => nat[h] = "freed"
=============================================================================
