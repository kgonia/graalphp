| engine | buy | conc. | rounds | orders/s | pages/s | p50 ms | p95 ms | p99 ms | errors | CPU-s/order | CPU-s/page | RssAnon MB | memory.current MB | memory.peak MB | DB cores | lock waits | db problems |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ab-after | 1 | 16 | 3 | 1.33 (1.32-1.52) | 10.6 | 1290 | 2521 | 2754 (2712-2892) | 0.00% / 0 php | 5.83 | 0.729 | 2967 | 2985 | 2988 | 0.55 | 97 | 0 |
| ab-before | 1 | 16 | 3 | 1.41 (1.29-1.71) | 11.3 | 1271 | 2431 | 2775 (2416-2973) | 0.00% / 0 php | 5.51 | 0.689 | 3013 | 3031 | 3039 | 0.54 | 61 | 0 |
