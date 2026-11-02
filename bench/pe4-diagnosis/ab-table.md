| engine | buy | conc. | rounds | orders/s | pages/s | p50 ms | p95 ms | p99 ms | errors | CPU-s/order | CPU-s/page | RssAnon MB | memory.current MB | memory.peak MB | DB cores | lock waits | db problems |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ab-after | 1 | 16 | 6 | 1.52 (1.32-1.80) | 12.1 | 1186 | 2339 | 2819 (2243-3127) | 0.00% / 0 php | 5.11 | 0.638 | 3361 | 3380 | 3384 | 0.56 | 92 | 0 |
| ab-before | 1 | 16 | 6 | 1.54 (1.29-1.71) | 12.3 | 1170 | 2231 | 2552 (2264-2973) | 0.00% / 0 php | 5.03 | 0.629 | 3935 | 3956 | 3984 | 0.53 | 82 | 0 |
