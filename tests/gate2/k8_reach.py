"""When does a K8 loop reach its level? first render index and loop seconds at which the rolling first decile (window 200) is within 5% / 10% / 25% of the final (last 1000) first decile.
Usage: python -I k8_reach.py LABEL=FILE.json ..."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))    # python -I leaves the script's directory out
from k8_traj import documents, decile
for arg in sys.argv[1:]:
    label, path = arg.split('=', 1)
    d = [r[0] / 1e6 for r in documents(path)[-1]['iterations']]
    final = decile(d[-1000:])
    cum = [0.0]
    for x in d:
        cum.append(cum[-1] + x / 1e3)
    out = []
    for tol in (1.25, 1.10, 1.05):
        at = None
        for i in range(0, len(d) - 200, 50):
            if decile(d[i:i + 200]) <= tol * final:
                at = i
                break
        out.append(f'within {round((tol-1)*100)}%: render {at}, {cum[at]:.0f} s' if at is not None else f'within {round((tol-1)*100)}%: never')
    print(f'{label}: final p10 {final:.2f} ms, {len(d)} renders, {cum[-1]:.0f} s; first 100 median {sorted(d[:100])[50]:.1f}; ' + '; '.join(out))
