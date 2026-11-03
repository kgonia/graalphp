| run | orders/s | pages/s | p50 ms | p99 ms | CPU-s/page | RssAnon max MB | memory.peak MB | libgraal at most MB | CPU: requests / compiler / GC / JIT | compiler CPU-s | compilations, wall s | failed | libgraal heap after a GC, max MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| m-base-r1 | 0.68 | 9.2 | 1436 | 5359 | 0.831 | 2725 | 2764 | 865 | 63% / 21% / 14% / 2% | 1108 | 9043, 2009 | 1 timeout, 1 oopmap, 1 code too large | 569 |
| m-t1-r1 | 0.63 | 8.3 | 1691 | 4851 | 0.925 | 2059 | 2072 | 388 | 65% / 20% / 14% / 2% | 1065 | 8803, 2017 | 0 | 91 |
| m-t1-r2 | 0.88 | 11.4 | 1234 | 3278 | 0.680 | 1906 | 1948 | 364 | 63% / 21% / 14% / 2% | 1099 | 9752, 2040 | 0 | 104 |
| m-base-r2 | 0.51 | 7.0 | 1950 | 6492 | 1.093 | 2348 | 2365 | 985 | 64% / 21% / 13% / 2% | 1105 | 6708, 1981 | 1 timeout | 467 |
