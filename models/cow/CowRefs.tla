------------------------------ MODULE CowRefs ------------------------------
(***************************************************************************)
(* Copy-on-write arrays and PHP references.                                *)
(*                                                                         *)
(* Two heaps run the same PHP statements side by side:                     *)
(*   I  what Truffle PHP's runtime does: PhpArray holders (addHolder,      *)
(*      removeHolder, countIn/countOut, copy), PhpRef bindings (bind,      *)
(*      unbind, copyElement) and the nodes that call them (LocalVarNode,   *)
(*      DimNode, FunctionRootNode, ClosureNode, ForeachNode,               *)
(*      StaticVarNode);                                                    *)
(*   S  what php-src does: exact refcounts, SEPARATE_ARRAY,                *)
(*      zend_array_dup (a reference with refcount 1 is copied as its       *)
(*      value, a shared one stays shared), FE_RESET_RW / FE_FETCH_RW with  *)
(*      their hash iterator and its copies.                                *)
(* php-src's refcounts are not stored: SRC counts the slots pointing at a  *)
(* value from the roots and the live arrays and references, which is the   *)
(* refcount as long as no garbage cycle points into live data.             *)
(*                                                                         *)
(* The property (Agree): every variable reads the same deep value in both  *)
(* heaps, foreach visits the same keys and stops at the same time. A write *)
(* through a value copy that shows through another variable, or a write    *)
(* through a reference that one alias misses, breaks it at once or after a *)
(* later write. See wiki/tla-cow.md.                                       *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS
    NA,         \* arrays in a heap (ids 1..NA; 0 is the overflow sink)
    NR,         \* references in a heap (ids 1..NR; 0 is the overflow sink)
    MainVars,   \* the caller's variables, e.g. {"a", "b", "c"}
    MaxSteps,   \* statements per trace
    MaxCount,   \* bound on holder and binding counters (leaks only grow them)
    Features,   \* the statement groups explored, see Enabled
    Excluded,   \* findings left open: their triggers are not explored, see Allowed
    Fixed       \* findings whose fix the runtime side models (without it, the code as it was when found)

KS == <<0, 1>>          \* the keys, in insertion order: every array is [0 => _, 1 => _]
Keys == {0, 1}
NONE == -1              \* unset variable, absent element
NUL == -2               \* null, as a value (a reference to an unset variable makes it null)
OrNull(s) == IF s = NONE THEN NUL ELSE s
WriteInts == {1, 2}     \* new arrays hold 0s; writes store 1 or 2

AIds == 0..NA
RIds == 0..NR
A(i) == 100 + i
R(j) == 200 + j
IsA(s) == s >= 100 /\ s < 200
IsR(s) == s >= 200
AId(s) == s - 100
RId(s) == s - 200

FnVars == {"p", "q"}    \* the called function's (or closure's) locals; p is the parameter
Vars == MainVars \cup FnVars

EmptyArr == [k \in Keys |-> NONE]
NoIter == [on |-> FALSE, ht |-> 0, pos |-> 1, copies |-> {}]

(* A heap: variables, arrays, references; h and b are the runtime's holders and bindings and rel its released
   references (unused in S);
   cl is the closure's capture, lp the foreach temporary, st the function's static variable; it is php-src's
   foreach hash iterator (unused in I). *)
Heap0 == [v |-> [x \in Vars |-> NONE], a |-> [i \in AIds |-> EmptyArr], h |-> [i \in AIds |-> 0],
          r |-> [j \in RIds |-> NONE], b |-> [j \in RIds |-> 0], rel |-> [j \in RIds |-> FALSE],
          cl |-> NONE, lp |-> NONE, st |-> NONE, it |-> NoIter]

----------------------------------------------------------------------------
(* Reachability, allocation, garbage *)

Roots(H) == {H.v[x] : x \in Vars} \cup {H.cl, H.lp, H.st}
Kids(H, s) == IF IsA(s) THEN {H.a[AId(s)][k] : k \in Keys}
              ELSE IF IsR(s) THEN {H.r[RId(s)]} ELSE {}
RECURSIVE Expand(_, _)
Expand(H, T) == LET T2 == T \cup UNION {Kids(H, s) : s \in T} IN IF T2 = T THEN T ELSE Expand(H, T2)
Reach(H) == Expand(H, Roots(H))
LiveA(H) == {i \in AIds : A(i) \in Reach(H)}
LiveR(H) == {j \in RIds : R(j) \in Reach(H)}
Min(T) == CHOOSE m \in T : \A n \in T : m <= n
AllocA(H) == LET free == (1..NA) \ LiveA(H) IN IF free = {} THEN 0 ELSE Min(free)
AllocR(H) == LET free == (1..NR) \ LiveR(H) IN IF free = {} THEN 0 ELSE Min(free)

(* Garbage is reset, so states differ only in what can still be observed. The runtime's counters on live
   objects keep whatever unreachable holders added to them. A freed array poisons the iterator on it. *)
Norm(H) ==
    LET la == LiveA(H)
        lr == LiveR(H)
    IN [H EXCEPT !.a = [i \in AIds |-> IF i \in la THEN H.a[i] ELSE EmptyArr],
                 !.h = [i \in AIds |-> IF i \in la THEN H.h[i] ELSE 0],
                 !.r = [j \in RIds |-> IF j \in lr THEN H.r[j] ELSE NONE],
                 !.b = [j \in RIds |-> IF j \in lr THEN H.b[j] ELSE 0],
                 !.rel = [j \in RIds |-> j \in lr /\ H.rel[j]],
                 !.it = [H.it EXCEPT !.ht = IF H.it.ht \in la THEN H.it.ht ELSE 0,
                                     !.copies = {c \in H.it.copies : c[1] \in la}]]

Overflow(H) == 0 \in LiveA(H) \/ 0 \in LiveR(H)

Deref(H, s) == IF IsR(s) THEN H.r[RId(s)] ELSE s
ArrOf(H, x) == AId(Deref(H, H.v[x]))
IsArr(H, x) == IsA(Deref(H, H.v[x]))
HasKey(H, x, k) == IsArr(H, x) /\ H.a[ArrOf(H, x)][k] # NONE
ElemIsArrWithKey(H, x, k, k2) ==
    HasKey(H, x, k) /\ IsA(Deref(H, H.a[ArrOf(H, x)][k]))
    /\ H.a[AId(Deref(H, H.a[ArrOf(H, x)][k]))][k2] # NONE

(* What var_dump would print, without the "&" marks: <<n>> for a scalar, <<d0, d1>> for an array. *)
RECURSIVE Deep(_, _, _)
Deep(H, s, d) == IF IsR(s) THEN Deep(H, H.r[RId(s)], d)
                 ELSE IF IsA(s) THEN (IF d = 0 THEN <<>>
                                      ELSE <<Deep(H, H.a[AId(s)][0], d - 1), Deep(H, H.a[AId(s)][1], d - 1)>>)
                 ELSE <<s>>
Obs(H, s) == Deep(H, s, NA + 1)

(* First position at or after n holding an element; Len(KS) + 1 at the end. *)
RECURSIVE FirstFrom(_, _)
FirstFrom(arr, n) == IF n > Len(KS) THEN n ELSE IF arr[KS[n]] # NONE THEN n ELSE FirstFrom(arr, n + 1)

----------------------------------------------------------------------------
(* I: the runtime. Each operator is named after the Java method it follows. *)

\* PhpArray.addHolder / removeHolder with countReferences (mayHoldRefs is taken as always set), and
\* PhpRef.bind / unbind. With F5's fix a reference whose last binding goes releases its array (rel), and takes
\* it back when bound again; that can cascade through nested arrays and references.
RECURSIVE AddHolder(_, _), RemoveHolder(_, _), Bind(_, _), Unbind(_, _), BindSlots(_, _, _), UnbindSlots(_, _, _)
AddHolder(H, i) ==
    IF H.h[i] = 0 THEN BindSlots([H EXCEPT !.h[i] = 1], i, 1) ELSE [H EXCEPT !.h[i] = @ + 1]
RemoveHolder(H, i) ==
    IF H.h[i] = 0 THEN H
    ELSE IF H.h[i] = 1 THEN UnbindSlots([H EXCEPT !.h[i] = 0], i, 1)
    ELSE [H EXCEPT !.h[i] = @ - 1]
BindSlots(H, i, n) ==
    IF n > Len(KS) THEN H
    ELSE LET s == H.a[i][KS[n]] IN BindSlots(IF IsR(s) THEN Bind(H, RId(s)) ELSE H, i, n + 1)
UnbindSlots(H, i, n) ==
    IF n > Len(KS) THEN H
    ELSE LET s == H.a[i][KS[n]] IN UnbindSlots(IF IsR(s) THEN Unbind(H, RId(s)) ELSE H, i, n + 1)
Bind(H, j) ==
    IF "F5" \in Fixed /\ H.b[j] = 0 /\ H.rel[j]
    THEN LET H1 == [H EXCEPT !.b[j] = 1, !.rel[j] = FALSE]
         IN IF IsA(H.r[j]) THEN AddHolder(H1, AId(H.r[j])) ELSE H1
    ELSE [H EXCEPT !.b[j] = @ + 1]
Unbind(H, j) ==
    IF H.b[j] = 0 THEN H
    ELSE IF H.b[j] = 1 /\ "F5" \in Fixed
         THEN LET H1 == [H EXCEPT !.b[j] = 0, !.rel[j] = TRUE]
              IN IF IsA(H.r[j]) THEN RemoveHolder(H1, AId(H.r[j])) ELSE H1
    ELSE [H EXCEPT !.b[j] = @ - 1]
CountIn(H, s) == IF IsA(s) THEN AddHolder(H, AId(s)) ELSE H
CountOut(H, i, old, repl) ==
    IF old = repl THEN H
    ELSE IF IsA(old) THEN RemoveHolder(H, AId(old))
    ELSE IF IsR(old) /\ H.h[i] > 0 THEN Unbind(H, RId(old))
    ELSE H

\* new PhpRef(value): the reference holds an array as one more holder
INewRef(H, val) ==
    LET j == AllocR(H) IN [hp |-> [CountIn(H, val) EXCEPT !.r[j] = val, !.b[j] = 0, !.rel[j] = FALSE], id |-> j]
\* PhpRef.set: a released reference doesn't hold what it stores
IRefSet(H, j, new) ==
    LET old == H.r[j]
        H2 == [IF H.rel[j] THEN H ELSE CountIn(H, new) EXCEPT !.r[j] = new]
    IN IF IsA(old) /\ old # new /\ ~H.rel[j] THEN RemoveHolder(H2, AId(old)) ELSE H2

\* a copy n of array i made while a foreach by reference walks i, or a copy of it, gets an iterator copy at its
\* position: zend_array_dup_ht_iterators in php-src, RefIteration.copied in the runtime
CopyIters(H, i, n) ==
    LET poss == (IF H.it.on /\ H.it.ht = i THEN {H.it.pos} ELSE {})
                \cup {c[2] : c \in {c \in H.it.copies : c[1] = i}}
    IN [H EXCEPT !.it.copies = @ \cup {<<n, p>> : p \in poss}]

\* PhpArray.copy with copyElement and PhpRef.copyElement
RECURSIVE ICopyElems(_, _, _, _)
ICopyElems(H, src, dst, n) ==
    IF n > Len(KS) THEN H
    ELSE LET k == KS[n]
             e == H.a[src][k]
             e1 == IF IsR(e) /\ H.b[RId(e)] <= (IF H.h[src] > 0 THEN 1 ELSE 0) /\ H.r[RId(e)] # A(src)
                   THEN H.r[RId(e)] ELSE e
         IN ICopyElems(CountIn([H EXCEPT !.a[dst][k] = e1], e1), src, dst, n + 1)
ICopy(H, i) == LET n == AllocA(H)
               IN [hp |-> CopyIters(ICopyElems([H EXCEPT !.a[n] = EmptyArr, !.h[n] = 0], i, n, 1), i, n), id |-> n]

\* LocalVarNode
IWriteVar(H, x, val) ==
    LET old == H.v[x] IN
    IF IsR(old) THEN IRefSet(H, RId(old), val)
    ELSE LET H2 == [CountIn(H, val) EXCEPT !.v[x] = val]
         IN IF IsA(old) /\ old # val THEN RemoveHolder(H2, AId(old)) ELSE H2
IMakeRefVar(H, x) ==
    LET old == H.v[x] IN
    IF IsR(old) THEN [hp |-> H, id |-> RId(old)]
    ELSE LET nr == INewRef(H, OrNull(old))
             H2 == IF IsA(old) THEN RemoveHolder(nr.hp, AId(old)) ELSE nr.hp
         IN [hp |-> [Bind(H2, nr.id) EXCEPT !.v[x] = R(nr.id)], id |-> nr.id]
IRelease(H, x) ==
    LET old == H.v[x]
        H1 == IF IsA(old) THEN RemoveHolder(H, AId(old)) ELSE IF IsR(old) THEN Unbind(H, RId(old)) ELSE H
    IN [H1 EXCEPT !.v[x] = NONE]
IBindRefVar(H, x, j) == [Bind(IRelease(H, x), j) EXCEPT !.v[x] = R(j)]
\* PhpRef.arrayForWrite (through Places.refArrayForWrite)
IRefArrayForWrite(H, j) ==
    LET arr == AId(H.r[j]) IN
    IF H.h[arr] > 1
    THEN LET c == ICopy(H, arr)
             H3 == IF H.rel[j] THEN c.hp ELSE RemoveHolder(AddHolder(c.hp, c.id), arr)
         IN [hp |-> [H3 EXCEPT !.r[j] = A(c.id)], id |-> c.id]
    ELSE [hp |-> H, id |-> arr]
IArrayForWriteVar(H, x) ==
    LET s == H.v[x] IN
    IF IsR(s) THEN IRefArrayForWrite(H, RId(s))
    ELSE IF H.h[AId(s)] > 1
         THEN LET c == ICopy(H, AId(s))
                  H3 == RemoveHolder(AddHolder(c.hp, c.id), AId(s))
              IN [hp |-> [H3 EXCEPT !.v[x] = A(c.id)], id |-> c.id]
         ELSE [hp |-> H, id |-> AId(s)]

\* PhpArray element operations
IPut(H, i, k, val) ==
    LET old == H.a[i][k] IN
    IF IsR(old) THEN IRefSet(H, RId(old), val)
    ELSE CountOut([CountIn(H, val) EXCEPT !.a[i][k] = val], i, old, val)
IPutRef(H, i, k, j) ==
    LET H1 == IF H.h[i] > 0 THEN Bind(H, j) ELSE H
    IN [CountOut(H1, i, H1.a[i][k], NONE) EXCEPT !.a[i][k] = R(j)]
IRefAt(H, i, k) ==
    LET raw == H.a[i][k] IN
    IF IsR(raw) THEN [hp |-> H, id |-> RId(raw)]
    ELSE LET nr == INewRef(H, raw) IN [hp |-> IPutRef(nr.hp, i, k, nr.id), id |-> nr.id]
IRemove(H, i, k) == [CountOut(H, i, H.a[i][k], NONE) EXCEPT !.a[i][k] = NONE]
IArrayForWriteKey(H, i, k) ==
    LET raw == H.a[i][k] IN
    IF IsR(raw) THEN IRefArrayForWrite(H, RId(raw))
    ELSE IF H.h[AId(raw)] > 1
         THEN LET c == ICopy(H, AId(raw)) IN [hp |-> IPut(c.hp, i, k, A(c.id)), id |-> c.id]
         ELSE [hp |-> H, id |-> AId(raw)]
IRefAtPosition(H, i, k) ==
    LET raw == H.a[i][k] IN
    IF IsR(raw) THEN [hp |-> H, id |-> RId(raw)]
    ELSE LET nr == INewRef(H, raw)
             H1 == IF H.h[i] > 0 THEN Bind(nr.hp, nr.id) ELSE nr.hp
             H2 == IF IsA(raw) THEN RemoveHolder(H1, AId(raw)) ELSE H1
         IN [hp |-> [H2 EXCEPT !.a[i][k] = R(nr.id)], id |-> nr.id]

----------------------------------------------------------------------------
(* S: php-src *)

\* the refcount: slots pointing at s from the roots, the live arrays and the live references
SRC(H, s) ==
    Cardinality({x \in Vars : H.v[x] = s})
    + (IF H.cl = s THEN 1 ELSE 0) + (IF H.lp = s THEN 1 ELSE 0) + (IF H.st = s THEN 1 ELSE 0)
    + Cardinality({ik \in LiveA(H) \X Keys : H.a[ik[1]][ik[2]] = s})
    + Cardinality({j \in LiveR(H) : H.r[j] = s})

\* zend_array_dup / zend_array_dup_value, and zend_array_dup_ht_iterators for an iterated array
RECURSIVE SDupElems(_, _, _, _, _)
SDupElems(H, H0, src, dst, n) ==
    IF n > Len(KS) THEN H
    ELSE LET k == KS[n]
             e == H0.a[src][k]
             e1 == IF IsR(e) /\ SRC(H0, e) = 1 /\ H0.r[RId(e)] # A(src) THEN H0.r[RId(e)] ELSE e
         IN SDupElems([H EXCEPT !.a[dst][k] = e1], H0, src, dst, n + 1)
SDup(H, i) ==
    LET n == AllocA(H) IN [hp |-> CopyIters(SDupElems([H EXCEPT !.a[n] = EmptyArr], H, i, n, 1), i, n), id |-> n]

\* SEPARATE_ARRAY on the zval in a reference, a variable, an element
SSepRef(H, j) ==
    LET arr == AId(H.r[j]) IN
    IF SRC(H, A(arr)) > 1 THEN LET d == SDup(H, arr) IN [hp |-> [d.hp EXCEPT !.r[j] = A(d.id)], id |-> d.id]
    ELSE [hp |-> H, id |-> arr]
SSepVar(H, x) ==
    LET s == H.v[x] IN
    IF IsR(s) THEN SSepRef(H, RId(s))
    ELSE IF SRC(H, s) > 1 THEN LET d == SDup(H, AId(s)) IN [hp |-> [d.hp EXCEPT !.v[x] = A(d.id)], id |-> d.id]
    ELSE [hp |-> H, id |-> AId(s)]
SSepElem(H, i, k) ==
    LET s == H.a[i][k] IN
    IF IsR(s) THEN SSepRef(H, RId(s))
    ELSE IF SRC(H, s) > 1 THEN LET d == SDup(H, AId(s)) IN [hp |-> [d.hp EXCEPT !.a[i][k] = A(d.id)], id |-> d.id]
    ELSE [hp |-> H, id |-> AId(s)]
\* assignment to a variable or element: through the reference if it is one
SAssignVar(H, x, val) == IF IsR(H.v[x]) THEN [H EXCEPT !.r[RId(H.v[x])] = val] ELSE [H EXCEPT !.v[x] = val]
SAssignElem(H, i, k, val) ==
    IF IsR(H.a[i][k]) THEN [H EXCEPT !.r[RId(H.a[i][k])] = val] ELSE [H EXCEPT !.a[i][k] = val]
\* ZVAL_MAKE_REF
SMakeRefVar(H, x) ==
    IF IsR(H.v[x]) THEN [hp |-> H, id |-> RId(H.v[x])]
    ELSE LET j == AllocR(H) IN [hp |-> [H EXCEPT !.r[j] = OrNull(H.v[x]), !.v[x] = R(j)], id |-> j]
SMakeRefElem(H, i, k) ==
    IF IsR(H.a[i][k]) THEN [hp |-> H, id |-> RId(H.a[i][k])]
    ELSE LET j == AllocR(H) IN [hp |-> [H EXCEPT !.r[j] = H.a[i][k], !.a[i][k] = R(j)], id |-> j]

----------------------------------------------------------------------------
(* The trace *)

VARIABLES
    I, S,       \* the two heaps
    mode,       \* "main", "fn" (in a function or closure body), "loop" (in a foreach body, in main)
    lk,         \* the loop's kind: "val" or "ref"
    lv,         \* the loop's value variable
    ipos,       \* the runtime's loop position (by value: the next one; by reference: the current one)
    iKey, sKey, \* the key each side's loop visits (NONE outside loops)
    clOn,       \* a closure is alive ($f)
    clRef,      \* it captured by reference
    steps, op,  \* statements so far; the last one, as PHP
    bad         \* why the two sides stopped agreeing on control flow ("" while they agree)

vars == <<I, S, mode, lk, lv, ipos, iKey, sKey, clOn, clRef, steps, op, bad>>
\* op only labels the trace; steps stays in (TLC's parallel search is not strictly breadth-first)
View == <<I, S, mode, lk, lv, ipos, iKey, sKey, clOn, clRef, bad, steps>>

Init ==
    /\ I = Heap0 /\ S = Heap0
    /\ mode = "main" /\ lk = "" /\ lv = "a" /\ ipos = 1 /\ iKey = NONE /\ sKey = NONE
    /\ clOn = FALSE /\ clRef = FALSE /\ steps = 0 /\ op = "" /\ bad = ""

Scope == IF mode = "fn" THEN FnVars ELSE MainVars
Enabled(f) == f \in Features
V(x) == "$" \o x
N(n) == ToString(n)

(* Findings left open (wiki/tla-cow.md): their triggers are not explored, so the check goes on past them. *)
\* F1: php-src stores an array into itself through a reference alias; the runtime copies it first
SelfStore(H, x, y) == IsR(H.v[x]) /\ H.v[x] = H.v[y]
\* F3: unset($f) frees a closure and the references it captured; the runtime keeps their bindings
\* (closures, like every object, are not refcounted)
Allowed(f, cond) == f \notin Excluded \/ ~cond

Do(i2, s2, desc) ==
    /\ I' = Norm(i2) /\ S' = Norm(s2) /\ op' = desc /\ steps' = steps + 1

Stmt(i2, s2, desc) == Do(i2, s2, desc) /\ UNCHANGED <<mode, lk, lv, ipos, iKey, sKey, clOn, clRef, bad>>

\* ---- statements in the current scope ----

SetInt(x, n) == Stmt(IWriteVar(I, x, n), SAssignVar(S, x, n), V(x) \o " = " \o N(n) \o ";")

NewArr(x) ==
    LET ni == AllocA(I)
        ns == AllocA(S)
        zero == [k \in Keys |-> 0]
    IN Stmt(IWriteVar([I EXCEPT !.a[ni] = zero, !.h[ni] = 0], x, A(ni)),
            SAssignVar([S EXCEPT !.a[ns] = zero], x, A(ns)), V(x) \o " = [0, 0];")

Assign(x, y) ==
    /\ x # y /\ I.v[y] # NONE
    /\ Stmt(IWriteVar(I, x, Deref(I, I.v[y])), SAssignVar(S, x, Deref(S, S.v[y])), V(x) \o " = " \o V(y) \o ";")

AssignRef(x, y) ==
    /\ x # y
    /\ LET mi == IMakeRefVar(I, y)
           ms == SMakeRefVar(S, y)
       IN Stmt(IBindRefVar(mi.hp, x, mi.id), [ms.hp EXCEPT !.v[x] = R(ms.id)], V(x) \o " = &" \o V(y) \o ";")

WriteElem(x, k, n) ==
    /\ HasKey(I, x, k)
    /\ LET fi == IArrayForWriteVar(I, x)
           fs == SSepVar(S, x)
       IN Stmt(IPut(fi.hp, fi.id, k, n), SAssignElem(fs.hp, fs.id, k, n),
               V(x) \o "[" \o N(k) \o "] = " \o N(n) \o ";")

\* DimNode.write holds the value while it makes the container writable; php-src reads it after
WriteElemVar(x, k, y) ==
    /\ x # y /\ HasKey(I, x, k) /\ I.v[y] # NONE
    /\ Allowed("F1", SelfStore(I, x, y))
    /\ LET val == Deref(I, I.v[y])
           fi == IArrayForWriteVar(CountIn(I, val), x)
           i2 == IPut(fi.hp, fi.id, k, val)
           fs == SSepVar(S, x)
       IN Stmt(IF IsA(val) THEN RemoveHolder(i2, AId(val)) ELSE i2,
               SAssignElem(fs.hp, fs.id, k, Deref(fs.hp, fs.hp.v[y])),
               V(x) \o "[" \o N(k) \o "] = " \o V(y) \o ";")

\* $x = &$y[k]: FETCH_DIM_W $y, then ASSIGN_REF
RefToElem(x, y, k) ==
    /\ x # y /\ HasKey(I, y, k)
    /\ LET fi == IArrayForWriteVar(I, y)
           ri == IRefAt(fi.hp, fi.id, k)
           fs == SSepVar(S, y)
           rs == SMakeRefElem(fs.hp, fs.id, k)
       IN Stmt(IBindRefVar(ri.hp, x, ri.id), [rs.hp EXCEPT !.v[x] = R(rs.id)],
               V(x) \o " = &" \o V(y) \o "[" \o N(k) \o "];")

\* $x[k] = &$y: the runtime makes the reference first; php-src fetches the element first
ElemToRef(x, k, y) ==
    /\ x # y /\ HasKey(I, x, k)
    /\ LET mi == IMakeRefVar(I, y)
           fi == IArrayForWriteVar(mi.hp, x)
           fs == SSepVar(S, x)
           ms == SMakeRefVar(fs.hp, y)
       IN Stmt(IPutRef(fi.hp, fi.id, k, mi.id), [ms.hp EXCEPT !.a[fs.id][k] = R(ms.id)],
               V(x) \o "[" \o N(k) \o "] = &" \o V(y) \o ";")

Unset(x) ==
    /\ I.v[x] # NONE
    /\ Stmt(IRelease(I, x), [S EXCEPT !.v[x] = NONE], "unset(" \o V(x) \o ");")

UnsetElem(x, k) ==
    /\ HasKey(I, x, k)
    /\ LET fi == IArrayForWriteVar(I, x)
           fs == SSepVar(S, x)
       IN Stmt(IRemove(fi.hp, fi.id, k), [fs.hp EXCEPT !.a[fs.id][k] = NONE],
               "unset(" \o V(x) \o "[" \o N(k) \o "]);")

NestedWrite(x, k, k2, n) ==
    /\ ElemIsArrWithKey(I, x, k, k2)
    /\ LET fi == IArrayForWriteVar(I, x)
           ni == IArrayForWriteKey(fi.hp, fi.id, k)
           fs == SSepVar(S, x)
           ns == SSepElem(fs.hp, fs.id, k)
       IN Stmt(IPut(ni.hp, ni.id, k2, n), SAssignElem(ns.hp, ns.id, k2, n),
               V(x) \o "[" \o N(k) \o "][" \o N(k2) \o "] = " \o N(n) \o ";")

ReadElem(x, y, k) ==
    /\ x # y /\ HasKey(I, y, k)
    /\ Stmt(IWriteVar(I, x, Deref(I, I.a[ArrOf(I, y)][k])), SAssignVar(S, x, Deref(S, S.a[ArrOf(S, y)][k])),
            V(x) \o " = " \o V(y) \o "[" \o N(k) \o "];")

\* static $x; in the function (one static variable, shared by every call)
Static(x) ==
    /\ mode = "fn"
    /\ LET ni == INewRef(I, NUL)
           i1 == IF I.st = NONE THEN [Bind(ni.hp, ni.id) EXCEPT !.st = R(ni.id)] ELSE I
           js == AllocR(S)
           s1 == IF S.st = NONE THEN [S EXCEPT !.r[js] = NUL, !.st = R(js)] ELSE S
       IN Stmt(IBindRefVar(i1, x, RId(i1.st)), [s1 EXCEPT !.v[x] = s1.st], "static " \o V(x) \o ";")

ScopeStmt ==
    \E x \in Scope :
        \/ Enabled("int") /\ \E n \in WriteInts : SetInt(x, n)
        \/ Enabled("arr") /\ NewArr(x)
        \/ Enabled("assign") /\ \E y \in Scope : Assign(x, y)
        \/ Enabled("ref") /\ \E y \in Scope : AssignRef(x, y)
        \/ Enabled("elem") /\ \E k \in Keys, n \in WriteInts : WriteElem(x, k, n)
        \/ Enabled("elemvar") /\ \E k \in Keys, y \in Scope : WriteElemVar(x, k, y)
        \/ Enabled("elemref") /\ \E k \in Keys, y \in Scope : RefToElem(x, y, k) \/ ElemToRef(x, k, y)
        \/ Enabled("unset") /\ Unset(x)
        \/ Enabled("unsetelem") /\ \E k \in Keys : UnsetElem(x, k)
        \/ Enabled("nested") /\ \E k, k2 \in Keys, n \in WriteInts : NestedWrite(x, k, k2, n)
        \/ Enabled("read") /\ \E k \in Keys, y \in Scope : ReadElem(x, y, k)
        \/ Enabled("static") /\ Static(x)

----------------------------------------------------------------------------
(* Functions and closures: f($p) / f(&$p) / $f(), one level deep *)

Enter(i2, s2, desc) ==
    /\ Do(i2, s2, desc)
    /\ mode' = "fn"
    /\ UNCHANGED <<lk, lv, ipos, iKey, sKey, clOn, clRef, bad>>

\* FunctionRootNode.bindParameters: by value the argument array gains the parameter as a holder
CallVal(y) ==
    /\ I.v[y] # NONE
    /\ LET val == Deref(I, I.v[y])
       IN Enter([CountIn(I, val) EXCEPT !.v["p"] = val], [S EXCEPT !.v["p"] = Deref(S, S.v[y])],
                "f(" \o V(y) \o ") {")

\* the caller's ArgumentsNode makes the reference; the parameter binds it
CallRef(y) ==
    LET mi == IMakeRefVar(I, y)
        ms == SMakeRefVar(S, y)
    IN Enter([Bind(mi.hp, mi.id) EXCEPT !.v["p"] = R(mi.id)], [ms.hp EXCEPT !.v["p"] = R(ms.id)],
             "f(&" \o V(y) \o ") {")

\* FunctionRootNode.bindCaptures
CallClosure ==
    /\ clOn
    /\ LET c == I.cl
           i1 == IF ~clRef /\ IsA(c) THEN AddHolder(I, AId(c)) ELSE IF IsR(c) THEN Bind(I, RId(c)) ELSE I
       IN Enter([i1 EXCEPT !.v["p"] = c], [S EXCEPT !.v["p"] = S.cl], "$f() {")

\* `return $z;` assigned to $x, or `return;`: the value is taken, then FunctionRootNode.releaseLocals
Return(z, x) ==
    LET ival == IF z = "" THEN NONE ELSE Deref(I, I.v[z])
        sval == IF z = "" THEN NONE ELSE Deref(S, S.v[z])
        i1 == IRelease(IRelease(I, "p"), "q")
        s1 == [S EXCEPT !.v["p"] = NONE, !.v["q"] = NONE]
    IN /\ Do(IF x = "" THEN i1 ELSE IWriteVar(i1, x, ival), IF x = "" THEN s1 ELSE SAssignVar(s1, x, sval),
             IF x = "" THEN "} // return;" ELSE "} // return " \o V(z) \o "; into " \o V(x))
       /\ mode' = "main"
       /\ UNCHANGED <<lk, lv, ipos, iKey, sKey, clOn, clRef, bad>>

\* ClosureNode: $f = function () use ($y) / use (&$y) { ... }
MakeClosure(y, byRef) ==
    /\ ~clOn
    /\ ~byRef => I.v[y] # NONE
    /\ LET val == Deref(I, I.v[y])
           mi == IMakeRefVar(I, y)
           ms == SMakeRefVar(S, y)
           i2 == IF byRef THEN [Bind(mi.hp, mi.id) EXCEPT !.cl = R(mi.id)] ELSE [CountIn(I, val) EXCEPT !.cl = val]
           s2 == IF byRef THEN [ms.hp EXCEPT !.cl = R(ms.id)] ELSE [S EXCEPT !.cl = Deref(S, S.v[y])]
       IN /\ Do(i2, s2, "$f = function () use (" \o (IF byRef THEN "&" ELSE "") \o V(y) \o ") { ... };")
          /\ clOn' = TRUE /\ clRef' = byRef
          /\ UNCHANGED <<mode, lk, lv, ipos, iKey, sKey, bad>>

\* unset($f): php-src frees the closure and its captures; the runtime doesn't see it
DropClosure ==
    /\ clOn
    /\ Allowed("F3", clRef)
    /\ Do([I EXCEPT !.cl = NONE], [S EXCEPT !.cl = NONE], "unset($f);")
    /\ clOn' = FALSE /\ clRef' = FALSE
    /\ UNCHANGED <<mode, lk, lv, ipos, iKey, sKey, bad>>

----------------------------------------------------------------------------
(* foreach in main: $x is iterated, $y is the value variable *)

LoopStep(i2, s2, desc, exits, ip, ik, sk, why, kind, var) ==
    /\ Do(i2, s2, desc)
    /\ mode' = IF exits THEN "main" ELSE "loop"
    /\ lk' = IF exits THEN "" ELSE kind
    /\ lv' = var
    /\ ipos' = ip /\ iKey' = ik /\ sKey' = sk
    /\ bad' = why
    /\ UNCHANGED <<clOn, clRef>>

\* by value: ForeachNode.iterate holds the array; FE_RESET_R copies the zval into a temporary
IFetchVal(H, y, p) == IWriteVar(H, y, Deref(H, H.a[AId(H.lp)][KS[p]]))
SFetchVal(H, y, p) == [SAssignVar(H, y, Deref(H, H.a[AId(H.lp)][KS[p]])) EXCEPT !.it.pos = p + 1]

ForeachVal(x, y) ==
    /\ x # y /\ IsArr(I, x)
    /\ FirstFrom(I.a[ArrOf(I, x)], 1) <= Len(KS)
    /\ LET i1 == [AddHolder(I, ArrOf(I, x)) EXCEPT !.lp = A(ArrOf(I, x))]
           p == FirstFrom(i1.a[AId(i1.lp)], 1)
           i2 == IFetchVal(i1, y, p)
           s1 == [S EXCEPT !.lp = A(ArrOf(S, x)), !.it = NoIter]
           sp == FirstFrom(s1.a[AId(s1.lp)], 1)
       IN /\ LoopStep(i2, SFetchVal(s1, y, sp), "foreach (" \o V(x) \o " as " \o V(y) \o ") {", FALSE,
                      FirstFrom(i2.a[AId(i2.lp)], p + 1), KS[p], KS[sp], "", "val", y)

NextVal ==
    LET p == ipos
        iEnd == p > Len(KS)
        sp == FirstFrom(S.a[AId(S.lp)], S.it.pos)
        sEnd == sp > Len(KS)
        i2 == IF iEnd THEN [RemoveHolder(I, AId(I.lp)) EXCEPT !.lp = NONE] ELSE IFetchVal(I, lv, p)
        s2 == IF sEnd THEN [S EXCEPT !.lp = NONE] ELSE SFetchVal(S, lv, sp)
    IN LoopStep(i2, s2, "// next iteration", iEnd /\ sEnd,
                IF iEnd THEN 1 ELSE FirstFrom(i2.a[AId(i2.lp)], p + 1),
                IF iEnd THEN NONE ELSE KS[p], IF sEnd THEN NONE ELSE KS[sp],
                IF iEnd # sEnd THEN "loop ends on one side only" ELSE "", lk, lv)

\* by reference, before the fix of F2: ForeachNode.executeByRef made the variable's array writable once and
\* iterated that array, whatever the variable held later (lp is the array, ipos the current position)
IFetchRefOld(H, y, p) == LET ri == IRefAtPosition(H, AId(H.lp), KS[p]) IN IBindRefVar(ri.hp, y, ri.id)

\* by reference, fixed (RefIteration): the loop holds the variable's reference (lp) and follows its array as
\* php-src does; the iterator's state is in H.it, its copies are made by ICopy
IFetchRef(H, y) ==
    LET j == RId(H.lp)
        val == H.r[j]
        ht == AId(val)
        found == {c \in H.it.copies : c[1] = ht}
        h1 == IF ~IsA(val) \/ ht = H.it.ht THEN H
              ELSE IF found # {} THEN [H EXCEPT !.it.ht = ht, !.it.pos = (CHOOSE c \in found : TRUE)[2],
                                                !.it.copies = {}]
              ELSE LET f == IRefArrayForWrite([H EXCEPT !.it.copies = {}], j)
                   IN [f.hp EXCEPT !.it.ht = f.id, !.it.pos = 1]
        p == FirstFrom(h1.a[h1.it.ht], h1.it.pos)
        ri == IRefAtPosition([h1 EXCEPT !.it.pos = p + 1], h1.it.ht, KS[p])
    IN IF ~IsA(val) \/ p > Len(KS) THEN [hp |-> H, pos |-> 0]
       ELSE [hp |-> IBindRefVar(ri.hp, y, ri.id), pos |-> p]

IEndRef(H) == [Unbind(H, RId(H.lp)) EXCEPT !.lp = NONE, !.it = NoIter]

\* FE_FETCH_RW: follows the array in the reference the loop holds (zend_hash_iterator_pos_ex), then makes the
\* element a reference and binds the value variable to it. Returns the heap and the position, or 0 at the end.
SFetchRef(H, y) ==
    LET val == H.r[RId(H.lp)]
        ht == AId(val)
        found == {c \in H.it.copies : c[1] = ht}
        h1 == IF ~IsA(val) THEN H
              ELSE IF ht = H.it.ht THEN H
              ELSE IF found # {} THEN [H EXCEPT !.it.ht = ht, !.it.pos = (CHOOSE c \in found : TRUE)[2],
                                                !.it.copies = {}]
              ELSE IF SRC(H, val) > 1
                   THEN LET d == SDup([H EXCEPT !.it.copies = {}], ht)
                        IN [d.hp EXCEPT !.r[RId(H.lp)] = A(d.id), !.it.ht = d.id, !.it.pos = 1]
              ELSE [H EXCEPT !.it.ht = ht, !.it.pos = 1, !.it.copies = {}]
        p == FirstFrom(h1.a[h1.it.ht], h1.it.pos)
        mr == SMakeRefElem(h1, h1.it.ht, KS[p])
    IN IF ~IsA(val) \/ p > Len(KS) THEN [hp |-> H, pos |-> 0]
       ELSE [hp |-> [mr.hp EXCEPT !.v[y] = R(mr.id), !.it.pos = p + 1], pos |-> p]

SEndRef(H) == [H EXCEPT !.lp = NONE, !.it = NoIter]

\* the runtime's side of a by-reference loop's first or next step: [hp, pos], pos 0 at the end
IStepRef(H, y, first) ==
    IF "F2" \in Fixed THEN IFetchRef(H, y)
    ELSE LET p == FirstFrom(H.a[AId(H.lp)], IF first THEN 1 ELSE ipos + 1)
         IN IF p > Len(KS) THEN [hp |-> H, pos |-> 0] ELSE [hp |-> IFetchRefOld(H, y, p), pos |-> p]
IEndLoop(H) == IF lk = "val" THEN [RemoveHolder(H, AId(H.lp)) EXCEPT !.lp = NONE]
               ELSE IF "F2" \in Fixed THEN IEndRef(H) ELSE [H EXCEPT !.lp = NONE]

ForeachRef(x, y) ==
    /\ x # y /\ IsArr(I, x)
    /\ FirstFrom(I.a[ArrOf(I, x)], 1) <= Len(KS)
    /\ LET \* fixed: RefIteration.start binds the variable's reference and makes its array exclusive
           mi == IMakeRefVar(I, x)
           i0 == [Bind(mi.hp, mi.id) EXCEPT !.lp = R(mi.id)]
           fr == IRefArrayForWrite(i0, mi.id)
           \* before: the variable's array made writable once
           fo == IArrayForWriteVar(I, x)
           i1 == IF "F2" \in Fixed
                 THEN [fr.hp EXCEPT !.it = [on |-> TRUE, ht |-> fr.id, pos |-> 1, copies |-> {}]]
                 ELSE [fo.hp EXCEPT !.lp = A(fo.id)]
           fi == IStepRef(i1, y, TRUE)
           \* FE_RESET_RW: the variable becomes a reference, held by the loop, and its array is separated
           mr == SMakeRefVar(S, x)
           s1 == [mr.hp EXCEPT !.lp = R(mr.id)]
           sep == SSepRef(s1, mr.id)
           s2 == [sep.hp EXCEPT !.it = [on |-> TRUE, ht |-> sep.id, pos |-> 1, copies |-> {}]]
           f == SFetchRef(s2, y)
       IN /\ fi.pos > 0
          /\ LoopStep(fi.hp, f.hp, "foreach (" \o V(x) \o " as &" \o V(y) \o ") {", FALSE, fi.pos, KS[fi.pos],
                      IF f.pos = 0 THEN NONE ELSE KS[f.pos], IF f.pos = 0 THEN "loop ends on one side only" ELSE "",
                      "ref", y)

NextRef ==
    LET fi == IStepRef(I, lv, FALSE)
        iEnd == fi.pos = 0
        f == SFetchRef(S, lv)
        sEnd == f.pos = 0
        i2 == IF iEnd THEN IEndLoop(fi.hp) ELSE fi.hp
        s2 == IF sEnd THEN SEndRef(f.hp) ELSE f.hp
    IN LoopStep(i2, s2, "// next iteration", iEnd /\ sEnd, IF iEnd THEN 1 ELSE fi.pos,
                IF iEnd THEN NONE ELSE KS[fi.pos], IF sEnd THEN NONE ELSE KS[f.pos],
                IF iEnd # sEnd THEN "loop ends on one side only" ELSE "", lk, lv)

Break == LoopStep(IEndLoop(I), SEndRef(S), "break; }", TRUE, 1, NONE, NONE, "", "", lv)

----------------------------------------------------------------------------

Next ==
    /\ bad = "" /\ steps < MaxSteps
    /\ \/ mode \in {"main", "loop", "fn"} /\ ScopeStmt
       \/ mode = "main" /\ Enabled("call") /\ \E y \in MainVars : CallVal(y) \/ CallRef(y)
       \/ mode = "fn" /\ Enabled("call") /\ \E z \in FnVars \cup {""}, x \in MainVars \cup {""} :
              (z = "") = (x = "") /\ Return(z, x)
       \/ mode = "main" /\ Enabled("closure") /\ \E y \in MainVars, byRef \in BOOLEAN : MakeClosure(y, byRef)
       \/ mode = "main" /\ Enabled("closure") /\ (CallClosure \/ DropClosure)
       \/ mode = "main" /\ Enabled("foreach") /\ \E x, y \in MainVars : ForeachVal(x, y)
       \/ mode = "main" /\ Enabled("foreachref") /\ \E x, y \in MainVars : ForeachRef(x, y)
       \/ mode = "loop" /\ lk = "val" /\ NextVal
       \/ mode = "loop" /\ lk = "ref" /\ NextRef
       \/ mode = "loop" /\ Break

Spec == Init /\ [][Next]_vars

----------------------------------------------------------------------------
(* Properties and bounds *)

Agree ==
    \/ Overflow(I) \/ Overflow(S)
    \/ /\ bad = ""
       /\ \A x \in Vars : Obs(I, I.v[x]) = Obs(S, S.v[x])
       /\ clOn => Obs(I, I.cl) = Obs(S, S.cl)
       /\ Obs(I, I.st) = Obs(S, S.st)
       /\ iKey = sKey

\* F4: a reference's bindings still count slots of arrays nothing reaches any more (their container was freed;
\* only php-src's refcounts see that), so it stays shared where php-src copies its value
ImplBinders(H, j) ==
    Cardinality({x \in Vars : H.v[x] = R(j)})
    + (IF H.cl = R(j) THEN 1 ELSE 0) + (IF H.lp = R(j) THEN 1 ELSE 0) + (IF H.st = R(j) THEN 1 ELSE 0)
    + Cardinality({ik \in LiveA(H) \X Keys : H.h[ik[1]] > 0 /\ H.a[ik[1]][ik[2]] = R(j)})
OverBound(H) == \E j \in LiveR(H) : H.b[j] > ImplBinders(H, j)

Bounds ==
    /\ "F4" \in Excluded => ~OverBound(I)
    /\ ~Overflow(I) /\ ~Overflow(S)
    /\ \A i \in AIds : I.h[i] <= MaxCount
    /\ \A j \in RIds : I.b[j] <= MaxCount
=============================================================================
