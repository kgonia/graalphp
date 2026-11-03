# One confirming round on main (4214f6b): mA = main, mB = main and 59d9b30 (runs.sh g2 1 200 mB mA; runs.sh ab 1 mA mB)

## Gate 2

rounds: jvm-java.mA 1, jvm-java.mB 1
failed visits: jvm-java.mA 0, jvm-java.mB 0

| | start → first response, s | start → cold home page, s | cold visit: wait, s | cold visit: CPU-s | warm wait per visit, s (last 50) | warm CPU-s per visit (last 50) |
|---|---|---|---|---|---|---|
| jvm-java.mA | 1.03 | 9.41 | 15.19 | 85.5 | 1.445 | 5.80 |
| jvm-java.mB | 1.33 | 9.52 | 12.40 | 69.4 | 1.348 | 2.73 |

| MB | RssAnon after start | after the cold visit | warm | memory.peak after start | after the cold visit | warm |
|---|---|---|---|---|---|---|
| jvm-java.mA | 116 | 1147 | 1018 | 120 | 1197 | 1415 |
| jvm-java.mB | 113 | 774 | 947 | 117 | 781 | 1482 |

Median wait per visit (s), the cold visit is visit 1:

| visits | jvm-java.mA | jvm-java.mB |
|---|---|---|
| 1 | 15.193 | 12.401 |
| 2 | 4.172 | 3.516 |
| 3 | 3.667 | 2.459 |
| 4–5 | 2.712 | 1.797 |
| 6–10 | 1.632 | 1.191 |
| 11–20 | 1.458 | 0.888 |
| 21–30 | 1.416 | 1.231 |
| 31–50 | 1.556 | 0.949 |
| 51–75 | 1.134 | 0.959 |
| 76–100 | 1.082 | 1.540 |
| 101–125 | 1.293 | 1.474 |
| 126–150 | 1.646 | 1.391 |
| 151–200 | 1.450 | 1.351 |

Median CPU-s per visit (pause included), warm visits:

| visits | jvm-java.mA | jvm-java.mB |
|---|---|---|
| 2 | 29.53 | 24.49 |
| 3 | 24.51 | 18.33 |
| 4–5 | 18.55 | 12.92 |
| 6–10 | 9.16 | 8.00 |
| 11–20 | 7.34 | 5.36 |
| 21–30 | 7.32 | 6.71 |
| 31–50 | 7.79 | 5.29 |
| 51–75 | 5.88 | 5.64 |
| 76–100 | 5.29 | 8.13 |
| 101–125 | 6.39 | 7.20 |
| 126–150 | 8.00 | 7.96 |
| 151–200 | 6.04 | 2.36 |

jvm-java.mA doesn't catch up with jvm-java.mB within 201 visits

Median page time over the last 50 visits (ms):

| page | jvm-java.mA | jvm-java.mB |
|---|---|---|
| home | 197.2 | 171.5 |
| post | 207.9 | 185.5 |
| login-form | 73.5 | 64.4 |
| login | 551.4 | 523.9 |
| dashboard | 182.6 | 168.4 |
| posts | 195.6 | 174.9 |

| variant | rounds | compilations (split copies) | compile s | compiler CPU-s | code compiled MB | code cache used MB |
|---|---|---|---|---|---|---|
| mA | 1 | 5639 (2242) | 1035 | 1072 | 78 | 96 |
| mB | 1 | 4416 (995) | 869 | 604 | 65 | 109 |

| variant | runs | memory.peak | max memory.current | max anon | max RssAnon | max file | peak - RssAnon | anon near its max, s | anon p90 | anon, last minute | libgraal largest heap MB (collections) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| mA | 1 | 1415 | 1414 | 1407 | 1361 | 0 | 55 | 1.5 | 1158 | 1076 | 606 (7469) |
| mB | 1 | 1482 | 1479 | 1471 | 1430 | 0 | 53 | 1.0 | 1141 | 1113 | 609 (6855) |

## WooCommerce

| variant | rounds | orders/s | pages/s | p50 ms | p99 ms | CPU-s/page | memory.peak MB | compilations (split copies) | compile s, life / window | compiler CPU-s | code compiled MB | code cache used MB | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| mA | 1 | 0.98 | 7.9 | 1722 | 6125 | 0.99 | 2577 | 2262 (276) | 1093 / 377 | 609 | 39 | 63 | 0 |
| mB | 1 | 1.39 | 11.1 | 1248 | 3870 | 0.70 | 2361 | 3107 (600) | 1129 / 363 | 614 | 47 | 73 | 0 |

| variant | runs | memory.peak | max memory.current | max anon | max RssAnon | max file | peak - RssAnon | anon near its max, s | anon p90 | anon, last minute | libgraal largest heap MB (collections) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| mA | 1 | 2577 | 2542 | 2531 | 2388 | 3 | 190 | 0.6 | 1593 | 1477 | 887 (5940) |
| mB | 1 | 2361 | 2352 | 2341 | 2340 | 4 | 21 | 4.5 | 1595 | 1498 | 977 (6480) |

## Host load before each run

2026-11-03 19:59:46 g2 mB-r1: outside 14-21 27%, on 14-21 43%; top: node#3 289%, node#1 213%, MsMpEng 78%, WmiPrvSE#1 41%, claude#4 37%, SpotifyLauncher 11%
2026-11-03 20:08:42 g2 mA-r1: outside 14-21 67%, on 14-21 60%; top: node#3 237%, node#1 234%, java#1 168%, Docker Desktop#4 141%, LenovoVantage-(DeviceSettingsSystemAddin) 68%, System 59%
2026-11-03 20:18:03 woo mA-r1: outside 14-21 84%, on 14-21 78%; top: node#1 248%, node#3 233%, java#3 169%, Docker Desktop#4 148%, java#4 135%, java#2 79%
2026-11-03 20:26:07 woo mB-r1: outside 14-21 47%, on 14-21 80%; top: MsMpEng 329%, node#1 253%, node#3 208%, vmmemWSL 108%, SearchIndexer 84%, Docker Desktop#4 78%
