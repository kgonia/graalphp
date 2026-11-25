#!/usr/bin/env python3
"""Analyze BootstrapCensus JSON reports and cross-request wp_loaded divergences."""
import json
import glob
import os
import sys

def mb(b):
    return b / (1024 * 1024)

def pct(part, total):
    return (100.0 * part / total) if total else 0.0

def main(out_dir):
    files = sorted(glob.glob(os.path.join(out_dir, "*.json")))
    if not files:
        print(f"No JSON files found in {out_dir}")
        return 1

    reports = []
    for f in files:
        with open(f, "r", encoding="utf-8") as fh:
            reports.append((os.path.basename(f), json.load(fh)))

    print("=" * 100)
    print("1. GRAPH COMPOSITION AT BARRIER")
    print("=" * 100)
    print(f"{'Page':22s} {'Barrier':13s} {'TotalMB':>8s} {'ArrMB':>7s} {'StrMB':>7s} {'ObjMB':>7s} {'Cl+Ref':>7s} | {'Objs':>6s} {'Arrs':>6s} {'Strs':>7s} {'Clos':>5s} {'Refs':>5s}")
    for _, r in reports:
        g = r["graph"]
        tot = g["total_est_bytes"]
        clref = g["closures"]["est_bytes"] + g["refs"]["est_bytes"]
        print(
            f"{r['page']:22s} {r['barrier']:13s} {mb(tot):8.2f} "
            f"{mb(g['arrays']['est_bytes']):7.2f} {mb(g['strings']['est_bytes']):7.2f} "
            f"{mb(g['objects']['est_bytes']):7.2f} {mb(clref):7.2f} | "
            f"{g['objects']['count']:6d} {g['arrays']['count']:6d} {g['strings']['count']:7d} "
            f"{g['closures']['count']:5d} {g['refs']['count']:5d}"
        )

    print("\n" + "=" * 100)
    print("2. POST-BARRIER MUTATIONS & CROSS-CHECK")
    print("=" * 100)
    print(f"{'Page':22s} {'ObjMut(P+T/Tot)':>18s} {'Obj%':>6s} {'ArrCont(P+T)':>13s} {'ArrPtrOnly':>10s} {'ArrCoW':>7s} {'ArrTot':>7s} {'RefMut':>8s} {'Missed(O/AC/AP/R)':>18s}")
    for _, r in reports:
        m = r["mutations"]
        mo, ma, mr = m["objects"], m["arrays"], m["refs"]
        omut = mo["persistent"] + mo["transient"]
        print(
            f"{r['page']:22s} "
            f"{mo['persistent']:4d}+{mo['transient']:<4d}/{mo['total']:<6d} "
            f"{pct(omut, mo['total']):5.1f}% "
            f"{ma['content_persistent']:5d}+{ma['content_transient']:<5d} "
            f"{ma['pointer_only']:10d} {ma['cow_copied']:7d} {ma['total']:7d} "
            f"{mr['persistent']:3d}+{mr['transient']:<3d} "
            f"{mo['missed_writes']:3d}/{ma['missed_content']:3d}/{ma['missed_pointer']:3d}/{mr['missed_writes']:3d}"
        )

    print("\n" + "=" * 100)
    print("3. MUTATED vs UNTOUCHED BYTES (SHALLOW/OWNED ESTIMATE)")
    print("=" * 100)
    for _, r in reports:
        m = r["mutations"]
        mo, ma = m["objects"], m["arrays"]
        print(
            f"{r['page']:22s} | "
            f"Objects: untouched={mb(mo['untouched_bytes']):.2f}MB ({pct(mo['untouched'], mo['total']):.1f}%) "
            f"mutated={mb(mo['mutated_bytes']):.2f}MB ({mo['write_events']} writes) | "
            f"Arrays: untouched={mb(ma['untouched_bytes']):.2f}MB ({pct(ma['untouched'], ma['total']):.1f}%) "
            f"ptrOnly={mb(ma['pointer_only_bytes']):.2f}MB ({ma['pointer_write_events']} ops) "
            f"contentMut={mb(ma['content_mutated_bytes']):.2f}MB ({ma['content_write_events']} ops) "
            f"cowCopied={mb(ma['cow_copied_bytes']):.2f}MB ({ma['copy_events']} copies)"
        )

    print("\n" + "=" * 100)
    print("4. SYMBOL TABLE GROWTH AFTER BARRIER")
    print("=" * 100)
    for _, r in reports:
        sy = r["symbols"]
        print(
            f"{r['page']:22s} | inc: {sy['pre_included']} -> +{sy['post_included']}, "
            f"cls: {sy['pre_classes']} -> +{sy['post_classes']}, "
            f"fn: {sy['pre_functions']} -> +{sy['post_functions']}, "
            f"const: {sy['pre_constants']} -> +{sy['post_constants']}, "
            f"globals: {sy['pre_globals']} -> {sy['post_globals']}, "
            f"statics: {sy['pre_static_vars']} -> {sy['post_static_vars']}"
        )

    # Detailed breakdown for primary pages
    by_page = {r["page"]: r for _, r in reports}
    for key in ["home-anon-1", "home-auth", "post-auth", "dashboard-auth", "rest-anon-wploaded", "rest-anon-restinit"]:
        r = by_page.get(key)
        if not r:
            continue
        print("\n" + "-" * 100)
        print(f"DETAIL: {key} ({r['barrier']})")
        print("-" * 100)
        sp = r["graph"]["special"]
        print(f"  Special objects: lazy={sp['lazy_objects']}, hooked={sp['hooked_objects']}")
        print(f"  Internal-handle objects: {sp['internal_objects']}")
        print(f"  __destruct objects:      {sp['destructor_objects']}")
        print(f"  Array store breakdown:   {r['graph']['arrays']['kinds']} | bytes: { {k: round(mb(v),2) for k,v in r['graph']['arrays']['kind_bytes'].items()} }")

        print("  Top 12 roots by reachable MB (exclusive MB):")
        for rt in r["top_roots"][:12]:
            print(f"    {rt['root']:42s} reach={mb(rt['reachable_bytes']):6.2f}MB ({rt['reachable_nodes']:6d} nodes) excl={mb(rt['exclusive_bytes']):6.2f}MB")

        print("  Top 15 classes at barrier (mutated = persistent + transient):")
        for c in r["top_classes"][:15]:
            print(f"    {c['class']:45s} cnt={c['count']:5d} mut={c['mutated']:4d} (P={c['persistent']:4d}, T={c['transient']:4d}) shallow={mb(c['shallow_bytes']):5.2f}MB")

        print("  Top 15 MUTATED classes:")
        for c in r["top_mutated_classes"][:15]:
            print(f"    {c['class']:45s} mut={c['mutated']:4d}/{c['total']:<5d} (P={c['persistent']:4d}, T={c['transient']:4d})")

        print("  Top 15 mutated Class::$prop by write count:")
        for p in r["top_prop_writes"][:15]:
            print(f"    {p['prop']:55s} writes={p['writes']:7d} persistent_objs={p['persistent_objs']:5d}")

        print(f"  Mutated root PhpRefs ({len(r['mutations']['refs']['mutated_labels'])} sample): {r['mutations']['refs']['mutated_labels'][:20]}")

        t = r["pre_barrier_taint"]
        print(f"  Pre-barrier SQL count: {t['sql_count']}")
        for q in t["sql_queries"][:12]:
            print(f"    SQL: {q}")
        print(f"  Pre-barrier superglobal keys read ({len(t['superglobal_reads'])} unique):")
        for sg in t["superglobal_reads"][:20]:
            print(f"    {sg['key']:42s} x{sg['count']:4d}  sites={sg['sites'][:3]}")
        print(f"  Pre-barrier time/hrtime calls ({len(t['nondet_calls'])} unique sites):")
        for nd in t["nondet_calls"][:10]:
            print(f"    {nd['site']:65s} x{nd['count']}")

    # Cross-request divergence at wp_loaded
    print("\n" + "=" * 100)
    print("5. CROSS-REQUEST ROOT DIVERGENCE AT wp_loaded")
    print("=" * 100)
    pairs = [
        ("home-anon-1", "home-anon-2"),
        ("home-anon-1", "home-auth"),
        ("home-auth", "post-auth"),
        ("home-auth", "dashboard-auth"),
        ("home-anon-1", "rest-anon-wploaded"),
    ]
    for a_name, b_name in pairs:
        if a_name not in by_page or b_name not in by_page:
            continue
        fa = by_page[a_name]["root_fingerprints"]
        fb = by_page[b_name]["root_fingerprints"]
        all_keys = sorted(set(fa.keys()) | set(fb.keys()))
        diffs = []
        for k in all_keys:
            va = fa.get(k)
            vb = fb.get(k)
            if va != vb:
                diffs.append((k, va, vb))
        print(f"\n  Diff [{a_name}] vs [{b_name}]: {len(diffs)} / {len(all_keys)} roots differ")
        for k, va, vb in diffs[:35]:
            sa = (va.split("|", 1)[1] if va and "|" in va else str(va))
            sb = (vb.split("|", 1)[1] if vb and "|" in vb else str(vb))
            print(f"    {k:48s}  A={sa:30s}  B={sb:30s}")
        if len(diffs) > 35:
            print(f"    ... (+{len(diffs) - 35} more)")

    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "bench/bootstrap-census/out"))
