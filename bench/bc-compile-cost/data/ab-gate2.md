rounds: jvm-java.tree 3, jvm-java.base 3, jvm-java.fix 3
failed visits: jvm-java.tree 0, jvm-java.base 0, jvm-java.fix 0

| | start → first response, s | start → cold home page, s | cold visit: wait, s | cold visit: CPU-s | warm wait per visit, s (last 50) | warm CPU-s per visit (last 50) |
|---|---|---|---|---|---|---|
| jvm-java.tree | 0.97 (0.90–1.05) | 4.54 (4.32–6.87) | 5.72 (5.23–8.49) | 31.2 (28.4–45.1) | 0.600 (0.586–0.651) | 1.63 (1.49–1.78) |
| jvm-java.base | 0.95 (0.90–1.76) | 7.27 (6.69–14.46) | 11.41 (10.89–21.08) | 63.9 (63.0–113.5) | 0.690 (0.664–0.694) | 4.57 (4.31–4.76) |
| jvm-java.fix | 0.96 (0.95–1.03) | 6.95 (6.87–7.17) | 10.93 (10.53–10.93) | 60.9 (59.7–61.2) | 0.594 (0.582–0.677) | 1.28 (1.24–1.54) |

| MB | RssAnon after start | after the cold visit | warm | memory.peak after start | after the cold visit | warm |
|---|---|---|---|---|---|---|
| jvm-java.tree | 115 (113–116) | 861 (804–895) | 936 (928–1011) | 119 (117–120) | 904 (834–963) | 1280 (1253–1331) |
| jvm-java.base | 113 (113–115) | 1046 (808–1061) | 1037 (1028–1085) | 117 (117–119) | 1060 (887–1071) | 1867 (1774–2004) |
| jvm-java.fix | 114 (113–115) | 1069 (947–1197) | 1039 (998–1040) | 118 (117–119) | 1076 (1056–1204) | 1471 (1343–1560) |

Median wait per visit (s), the cold visit is visit 1:

| visits | jvm-java.tree | jvm-java.base | jvm-java.fix |
|---|---|---|---|
| 1 | 5.723 | 11.405 | 10.929 |
| 2 | 1.564 | 3.705 | 3.356 |
| 3 | 1.229 | 2.279 | 1.875 |
| 4–5 | 1.163 | 2.040 | 1.477 |
| 6–10 | 0.882 | 0.913 | 0.974 |
| 11–20 | 0.821 | 0.869 | 0.925 |
| 21–30 | 0.758 | 0.783 | 0.846 |
| 31–50 | 0.719 | 0.735 | 0.795 |
| 51–75 | 0.653 | 0.779 | 0.733 |
| 76–100 | 0.720 | 0.711 | 0.705 |
| 101–125 | 0.698 | 0.690 | 0.720 |
| 126–150 | 0.649 | 0.655 | 0.688 |
| 151–200 | 0.598 | 0.690 | 0.590 |

Median CPU-s per visit (pause included), warm visits:

| visits | jvm-java.tree | jvm-java.base | jvm-java.fix |
|---|---|---|---|
| 2 | 11.13 | 27.08 | 24.45 |
| 3 | 10.09 | 17.68 | 14.47 |
| 4–5 | 8.34 | 14.71 | 10.76 |
| 6–10 | 5.63 | 6.22 | 6.96 |
| 11–20 | 4.44 | 5.18 | 5.53 |
| 21–30 | 4.37 | 5.08 | 4.95 |
| 31–50 | 4.33 | 4.50 | 4.40 |
| 51–75 | 4.38 | 5.05 | 4.29 |
| 76–100 | 4.93 | 4.55 | 4.10 |
| 101–125 | 3.28 | 4.06 | 3.76 |
| 126–150 | 1.72 | 3.77 | 4.34 |
| 151–200 | 1.38 | 4.63 | 0.97 |

jvm-java.tree has served k visits sooner than jvm-java.base (start included, pauses left out) from k = 1
jvm-java.tree has served k visits sooner than jvm-java.fix (start included, pauses left out) from k = 1

Median page time over the last 50 visits (ms):

| page | jvm-java.tree | jvm-java.base | jvm-java.fix |
|---|---|---|---|
| home | 66.8 | 82.8 | 70.3 |
| post | 75.5 | 90.3 | 78.3 |
| login-form | 28.7 | 33.8 | 29.9 |
| login | 281.6 | 302.9 | 272.3 |
| dashboard | 69.9 | 81.0 | 70.4 |
| posts | 72.6 | 88.3 | 74.7 |
