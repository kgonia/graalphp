| run | orders/s | pages/s | p50 ms | p99 ms | CPU-s/page | RssAnon max MB | memory.peak MB | libgraal at most MB | CPU: requests / compiler / GC / JIT | compiler CPU-s | compilations, wall s | failed | libgraal heap after a GC, max MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| m-base-r1 | 0.81 | 10.5 | 1298 | 4099 | 0.730 | 3258 | 3308 | 853 | 63% / 21% / 14% / 2% | 1119 | 9654, 2012 | 1 timeout, 1 oopmap, 1 code too large | 560 |
| m-ct1-r1 | 0.83 | 10.9 | 1267 | 3576 | 0.707 | 1595 | 1639 | 162 | 75% / 8% / 16% / 2% | 420 | 3678, 676 | 0 | 97 |
| m-cap2g-r1 | 0.72 | 9.3 | 1481 | 4561 | 0.825 | 2723 | 2750 | 887 | 63% / 21% / 14% / 2% | 1125 | 9245, 2032 | 1 timeout, 1 oopmap, 1 code too large | 813 |
| m-cap1g-r1 | 0.94 | 12.3 | 1091 | 3743 | 0.627 | 3101 | 3183 | 885 | 62% / 22% / 14% / 2% | 1144 | 10437, 2028 | 1 OutOfMemoryError, 1 oopmap, 1 code too large | 1252 |
| m-cap1g-r2 | 1.14 | 14.6 | 954 | 2716 | 0.528 | 3122 | 3148 | 1026 | 61% / 22% / 16% / 1% | 1168 | 11444, 2024 | 1 OutOfMemoryError, 1 oopmap, 1 code too large | 1248 |
| m-cap2g-r2 | 0.84 | 10.8 | 1298 | 3844 | 0.717 | 2742 | 2774 | 1029 | 63% / 21% / 14% / 2% | 1106 | 10084, 2021 | 1 timeout, 1 oopmap, 1 code too large | 826 |
| m-ct1-r2 | 0.97 | 12.6 | 1115 | 2984 | 0.613 | 2038 | 2064 | 585 | 74% / 8% / 16% / 1% | 428 | 3938, 621 | 0 | 442 |
| m-base-r2 | 0.74 | 9.8 | 1420 | 4210 | 0.784 | 2797 | 2823 | 1035 | 63% / 21% / 14% / 2% | 1112 | 9333, 2016 | 1 timeout, 1 oopmap, 1 code too large | 560 |
| m-base-r3 | 1.17 | 15.3 | 924 | 2436 | 0.506 | 5102 | 5151 | 3596 | 60% / 23% / 16% / 1% | 1213 | 11549, 2038 | 1 oopmap, 1 timeout, 1 code too large | 1686 |
| m-ct1-r3 | 1.19 | 15.4 | 908 | 2523 | 0.499 | 2971 | 3022 | 1322 | 73% / 9% / 17% / 1% | 462 | 3953, 528 | 0 | 1427 |
