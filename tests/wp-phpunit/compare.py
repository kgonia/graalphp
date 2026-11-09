"""Per-test-name comparison of PHPUnit runs (tests/wp-phpunit/run.sh), suite-independent.

A run is a directory (or several joined by +, later ones overriding) with outcomes.tsv (Phpunit9Outcomes.php: name, outcome, seconds, message) or else junit.xml
(any PHPUnit; it can't tell risky from pass nor incomplete from skip). A test's name is Class::method plus its data
set, as PHPUnit prints it. Outcomes: pass, failure, error, warning, risky, incomplete, skip, and run.sh's crash (the test ended its
process) and timeout (no progress, killed).

  compare.py summary RUN                      counts per outcome; the test a run died in, if it died
  compare.py diff REF OURS [--frozen FILE] [--freeze FILE] [--out REPORT] [--top N]
      every name whose outcome differs: REF pass and OURS not (the bugs), grouped by test class with their
      commonest messages; the reverse; the names one run lacks. --freeze FILE writes the differing names with
      both outcomes, "missing" for a reference test ours didn't run (the frozen fail-set); --frozen FILE then
      reports, by name, each difference the set doesn't have (pass -> not pass regressions first) and each name of
      the set that now agrees, and exits 1 if there is a new difference.
"""
import argparse
import collections
import os
import re
import sys
import xml.etree.ElementTree as ET

OUTCOMES = ["pass", "failure", "error", "warning", "risky", "incomplete", "skip", "crash", "timeout"]


def load(run):
    """{name: (outcome, message)} and the name of a test started but never ended. RUN1+RUN2 merges runs: a later
    run's outcome replaces an earlier one's (a rerun of some chunks)."""
    if "+" in run:
        tests, died = {}, None
        for part in run.split("+"):
            more, died = load(part)
            tests.update(more)
        return tests, died
    tests = {}
    tsv = os.path.join(run, "outcomes.tsv")
    started = None
    if os.path.exists(tsv):
        for line in open(tsv, encoding="utf-8", errors="replace"):
            line = line.rstrip("\n")
            if line.startswith("#start "):
                started = line[len("#start "):]
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                tests[parts[0]] = (parts[1], parts[3] if len(parts) > 3 else "")
                started = None
        return tests, started
    junit = os.path.join(run, "junit.xml")
    if not os.path.exists(junit):
        return tests, None
    for case in ET.parse(junit).iter("testcase"):
        cls = case.get("class") or case.get("classname", "")
        name = cls + "::" + case.get("name", "")
        outcome, message = "pass", ""
        for kind, label in (("error", "error"), ("failure", "failure"), ("warning", "warning"), ("skipped", "skip")):
            node = case.find(kind)
            if node is not None:
                outcome = label
                text = (node.text or node.get("message") or "").strip().split("\n")
                message = (node.get("type", "") + ": " + text[0]) if text else ""
                break
        tests[name] = (outcome, message)
    return tests, None


def counts(tests):
    c = collections.Counter(o for o, _ in tests.values())
    return " ".join(f"{o} {c[o]}" for o in OUTCOMES if c[o]) or "no tests"


def shape(message):
    """A message with what varies between its occurrences (numbers, paths, quoted values) taken out."""
    message = re.sub(r"(/[\w.\-]+)+", "%path", message)
    message = re.sub(r"'[^']{0,80}'|\"[^\"]{0,80}\"", "%s", message)
    message = re.sub(r"(?<![A-Za-z])\d+(?![A-Za-z])", "N", message)
    return message[:160]


def summary(run):
    tests, died = load(run)
    print(f"{run}: {len(tests)} tests: {counts(tests)}")
    if died:
        print(f"  died in {died}")


def cls(name):
    return name.split("::", 1)[0]


def diff(args):
    ref, ref_died = load(args.ref)
    ours, ours_died = load(args.ours)
    lines = []
    say = lines.append
    say(f"reference {args.ref}: {len(ref)} tests: {counts(ref)}" + (f"; died in {ref_died}" if ref_died else ""))
    say(f"ours      {args.ours}: {len(ours)} tests: {counts(ours)}" + (f"; died in {ours_died}" if ours_died else ""))
    both = [n for n in ref if n in ours]
    differ = {n: (ref[n][0], ours[n][0]) for n in both if ref[n][0] != ours[n][0]}
    matrix = collections.Counter(differ.values())
    say(f"in both: {len(both)}, same outcome {len(both) - len(differ)}, different {len(differ)}")
    say(f"only in reference: {sum(1 for n in ref if n not in ours)}, only in ours: {sum(1 for n in ours if n not in ref)}")
    say("")
    say("reference -> ours, by outcome:")
    for (a, b), n in matrix.most_common():
        say(f"  {a:>10} -> {b:<10} {n}")

    bugs = [n for n in both if ref[n][0] == "pass" and ours[n][0] != "pass"]
    say("")
    say(f"== pass on the reference, not on ours: {len(bugs)} tests in {len({cls(n) for n in bugs})} classes")
    say("   top messages:")
    for msg, n in collections.Counter(shape(ours[t][1]) for t in bugs).most_common(args.top):
        say(f"   {n:6}  {msg}")
    by_class = collections.defaultdict(list)
    for n in bugs:
        by_class[cls(n)].append(n)
    for c, names in sorted(by_class.items(), key=lambda kv: -len(kv[1])):
        msgs = collections.Counter(shape(ours[t][1]) for t in names).most_common(2)
        say(f"  {len(names):5}  {c}: " + " | ".join(f"{m} (x{k})" for m, k in msgs))
    say("")
    say("   by name:")
    for n in bugs:
        say(f"   {n}\t{ours[n][0]}\t{ours[n][1]}")

    reverse = [n for n in both if ours[n][0] == "pass" and ref[n][0] != "pass"]
    say("")
    say(f"== pass on ours, not on the reference: {len(reverse)}")
    for n in reverse:
        say(f"   {n}\t{ref[n][0]}\t{ref[n][1]}")
    other = [n for n in differ if n not in bugs and n not in reverse]
    say("")
    say(f"== other differences (neither passes): {len(other)}")
    for n in other:
        say(f"   {n}\t{ref[n][0]} -> {ours[n][0]}\t{ours[n][1]}")
    missing = [n for n in ref if n not in ours]
    say("")
    say(f"== in the reference, not run on ours: {len(missing)}"
        + (f" (ours died in {ours_died})" if ours_died else ""))
    for c, k in collections.Counter(cls(n) for n in missing).most_common():
        say(f"   {k:5}  {c}")
    extra = [n for n in ours if n not in ref]
    say("")
    say(f"== run on ours, not in the reference: {len(extra)}")
    for n in extra:
        say(f"   {n}\t{ours[n][0]}")

    # the gate's view: every reference name whose outcome on ours differs, "missing" for one ours didn't run
    gate = dict(differ)
    gate.update({n: (ref[n][0], "missing") for n in missing})
    status = 0
    if args.frozen:
        frozen = {}
        for line in open(args.frozen, encoding="utf-8"):
            if line.strip() and not line.startswith("#"):
                name, a, b = line.rstrip("\n").split("\t")[:3]
                frozen[name] = (a, b)
        new = [n for n in gate if gate[n] != frozen.get(n)]
        fixed = [n for n in frozen if n in ref and n not in gate]
        # regressions first: what passes on the reference
        new.sort(key=lambda n: (gate[n][0] != "pass", n))
        say("")
        say(f"== against the frozen fail-set {args.frozen} ({len(frozen)} names): {len(new)} new differences "
            f"({sum(1 for n in new if gate[n][0] == 'pass')} pass -> not pass), {len(fixed)} names now agree")
        for n in new:
            was = frozen.get(n)
            say(f"   NEW {n}\t{gate[n][0]} -> {gate[n][1]}" + (f"\t(frozen: {was[0]} -> {was[1]})" if was else "")
                + f"\t{ours[n][1] if n in ours else ''}")
        for n in fixed:
            say(f"   AGREES {n}\t{ref[n][0]}")
        status = 1 if new else 0
    if args.freeze:
        with open(args.freeze, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"# tests whose outcome differs, reference {args.ref} -> ours {args.ours}: name, reference, ours"
                    " (missing: not run)\n")
            for n in sorted(gate):
                f.write(f"{n}\t{gate[n][0]}\t{gate[n][1]}\n")
        say(f"froze {len(gate)} names in {args.freeze}")

    text = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        # the head of the report: everything before the per-name lists
        head = text.split("   by name:")[0]
        sys.stdout.write(head + f"(full report: {args.out})\n")
    else:
        sys.stdout.write(text)
    return status


def main():
    # names and messages are UTF-8 whatever the console's code page
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("summary")
    s.add_argument("run")
    d = sub.add_parser("diff")
    d.add_argument("ref")
    d.add_argument("ours")
    d.add_argument("--frozen")
    d.add_argument("--freeze")
    d.add_argument("--out")
    d.add_argument("--top", type=int, default=15)
    args = p.parse_args()
    if args.cmd == "summary":
        summary(args.run)
        return 0
    return diff(args)


if __name__ == "__main__":
    sys.exit(main())
