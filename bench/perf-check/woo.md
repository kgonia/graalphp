# WooCommerce load: runs.sh woo (300 s warm-up, 120 s of 16 users every visitor buying)

Rounds 1-3 base and main (the host loaded), 4-6 main and fix, 7-8 base and main (the host quiet).

| variant | rounds | orders/s | pages/s | p50 ms | p99 ms | CPU-s/page | memory.peak MB | compilations (split copies) | compile s, life / window | compiler CPU-s | code compiled MB | code cache used MB | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| base | 5 | 1.29 (0.74-2.08) | 10.3 (5.9-16.7) | 1343 (892-2151) | 3132 (1683-6172) | 0.75 (0.47-1.31) | 2574 (2363-2704) | 3419 (2687-4942) (727 (404-980)) | 1141 (1137-1172) / 363 (356-373) | - | 51 (42-73) | - | 0 |
| main | 8 | 1.78 (0.88-2.16) | 14.3 (7.0-17.3) | 1034 (866-1957) | 2328 (1604-6410) | 0.54 (0.45-1.11) | 2569 (2387-2816) | 4048 (2015-5231) (762 (235-1018)) | 1129 (1092-1161) / 357 (340-377) | - | 64 (36-83) | - | 0 |
| fix | 3 | 2.10 (2.08-2.20) | 16.8 (16.7-17.6) | 865 (856-877) | 1795 (1666-2050) | 0.46 (0.44-0.46) | 2578 (2470-2803) | 5024 (4894-5934) (769 (690-1172)) | 1118 (1096-1152) / 346 (345-351) | - | 79 (78-85) | - | 0 |

## Per run

- base-r1-1-16: 96 orders (0.74/s), 5.9 pages/s, p50 2151 p95 5019 p99 6172 ms, failed 0.0000, checks failed 0, php errors 0
- base-r2-1-16: 149 orders (1.17/s), 9.4 pages/s, p50 1553 p95 2838 p99 3132 ms, failed 0.0000, checks failed 0, php errors 0
- base-r3-1-16: 163 orders (1.29/s), 10.3 pages/s, p50 1343 p95 2657 p99 3592 ms, failed 0.0000, checks failed 0, php errors 0
- base-r7-1-16: 246 orders (1.98/s), 15.9 pages/s, p50 944 p95 1539 p99 1746 ms, failed 0.0000, checks failed 0, php errors 0
- base-r8-1-16: 256 orders (2.08/s), 16.7 pages/s, p50 892 p95 1493 p99 1683 ms, failed 0.0000, checks failed 0, php errors 0
- fix-r4-1-16: 272 orders (2.20/s), 17.6 pages/s, p50 856 p95 1443 p99 1666 ms, failed 0.0000, checks failed 0, php errors 0
- fix-r5-1-16: 256 orders (2.10/s), 16.8 pages/s, p50 865 p95 1511 p99 2050 ms, failed 0.0000, checks failed 0, php errors 0
- fix-r6-1-16: 259 orders (2.08/s), 16.7 pages/s, p50 877 p95 1517 p99 1795 ms, failed 0.0000, checks failed 0, php errors 0
- main-r1-1-16: 112 orders (0.88/s), 7.0 pages/s, p50 1957 p95 4513 p99 6410 ms, failed 0.0000, checks failed 0, php errors 0
- main-r2-1-16: 131 orders (1.02/s), 8.2 pages/s, p50 1730 p95 3131 p99 3466 ms, failed 0.0000, checks failed 0, php errors 0
- main-r3-1-16: 169 orders (1.33/s), 10.6 pages/s, p50 1355 p95 2334 p99 2636 ms, failed 0.0000, checks failed 0, php errors 0
- main-r4-1-16: 272 orders (2.16/s), 17.3 pages/s, p50 866 p95 1425 p99 1604 ms, failed 0.0000, checks failed 0, php errors 0
- main-r5-1-16: 232 orders (1.86/s), 14.9 pages/s, p50 969 p95 1707 p99 2021 ms, failed 0.0000, checks failed 0, php errors 0
- main-r6-1-16: 255 orders (2.03/s), 16.3 pages/s, p50 923 p95 1520 p99 1722 ms, failed 0.0000, checks failed 0, php errors 0
- main-r7-1-16: 224 orders (1.76/s), 14.1 pages/s, p50 1063 p95 1797 p99 1985 ms, failed 0.0000, checks failed 0, php errors 0
- main-r8-1-16: 224 orders (1.81/s), 14.5 pages/s, p50 1005 p95 1755 p99 3115 ms, failed 0.0000, checks failed 0, php errors 0

| variant | runs | memory.peak | max memory.current | max anon | max RssAnon | max file | peak - RssAnon | anon near its max, s | anon p90 | anon, last minute | libgraal largest heap MB (collections) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| base | 5 | 2574 (2363-2704) | 2571 (2360-2700) | 2560 (2350-2689) | 2560 (2350-2676) | 6 (3-10) | 28 (14-44) | 3.3 (2.3-8.5) | 1682 (1622-1706) | 1495 (1470-1597) | 1038 (908-1048) (7098 (5445-8704)) |
| main | 8 | 2569 (2387-2816) | 2566 (2376-2815) | 2555 (2306-2803) | 2512 (2288-2739) | 9 (4-135) | 84 (20-182) | 3.7 (1.7-14.2) | 1765 (1747-1964) | 1642 (1483-1707) | 928 (887-1272) (8300 (5181-9284)) |
| fix | 3 | 2578 (2470-2803) | 2572 (2461-2758) | 2561 (2451-2748) | 2547 (2284-2646) | 8 (8-9) | 157 (31-187) | 1.5 (0.8-5.0) | 1777 (1776-1851) | 1682 (1667-1734) | 1039 (866-1050) (9567 (9294-9580)) |

| run | memory.peak | max anon | at s | anon 2 s later | anon p90 | anon, last minute | libgraal largest heap MB | at s |
|---|---|---|---|---|---|---|---|---|
| base-r1 | 2700 | 2689 | 154.3 | 2389 | 1667 | 1470 | 1038 | 140.3 |
| base-r2 | 2704 | 2688 | 149.3 | 2210 | 1622 | 1480 | 1048 | 138.9 |
| base-r3 | 2521 | 2502 | 151.6 | 2215 | 1682 | 1495 | 908 | 145.8 |
| base-r7 | 2363 | 2350 | 89.0 | 2278 | 1705 | 1568 | 925 | 90.5 |
| base-r8 | 2574 | 2560 | 100.1 | 2308 | 1706 | 1597 | 1038 | 94.1 |
| main-r1 | 2796 | 2781 | 187.5 | 2738 | 1747 | 1483 | 909 | 184.0 |
| main-r2 | 2387 | 2366 | 178.8 | 2001 | 1749 | 1510 | 1077 | 177.6 |
| main-r3 | 2531 | 2518 | 191.7 | 2368 | 1964 | 1512 | 887 | 178.8 |
| main-r4 | 2816 | 2803 | 99.7 | 2536 | 1765 | 1681 | 908 | 92.4 |
| main-r5 | 2606 | 2592 | 103.8 | 2481 | 1765 | 1665 | 1040 | 100.9 |
| main-r6 | 2467 | 2447 | 78.1 | 2396 | 1765 | 1707 | 887 | 78.5 |
| main-r7 | 2763 | 2612 | 116.0 | 2343 | 1784 | 1646 | 1272 | 113.5 |
| main-r8 | 2439 | 2306 | 105.7 | 2237 | 1822 | 1639 | 946 | 103.3 |
| fix-r4 | 2578 | 2561 | 102.6 | 1985 | 1851 | 1734 | 866 | 85.3 |
| fix-r5 | 2803 | 2748 | 123.3 | 2230 | 1777 | 1682 | 1039 | 121.6 |
| fix-r6 | 2470 | 2451 | 73.8 | 1960 | 1776 | 1667 | 1050 | 72.5 |

CPU-s per page by thread class (woo: the server life in CPU-s), the median over rounds (range)

| variant | window | rounds | total | requests | Truffle compiler | HotSpot compiler | GC and VM | other | not seen | tier 1 done (s) | tier 2 done (s) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| base | server life | 5 | 3346.9 (3313.8-3509.4) | 2145.4 (2065.2-2336.6) | 665.5 (641.7-703.2) | 244.1 (156.0-302.1) | 285.4 (206.6-387.7) | 0.2 (0.2-0.3) | 2.3 (1.5-3.1) | 3123 (2455-4354) (757 (675-833)) | 296 (232-588) (381 (339-462)) |
| main | server life | 8 | 3333.8 (3295.2-3397.8) | 2118.7 (2048.8-2187.9) | 672.3 (655.4-705.0) | 237.5 (130.9-271.6) | 301.1 (271.9-424.8) | 0.2 (0.2-0.3) | 2.2 (1.1-3.4) | 3636 (1832-4578) (747 (698-819)) | 412 (183-653) (381 (293-460)) |
| fix | server life | 3 | 3298.7 (3262.7-3332.7) | 2118.7 (2056.2-2120.9) | 671.6 (654.0-682.8) | 186.8 (179.1-209.3) | 341.6 (312.8-353.1) | 0.2 (0.2-0.2) | 2.5 (1.5-2.6) | 4434 (4225-5249) (672 (670-717)) | 669 (590-685) (448 (379-480)) |
| base | measured 120 s | 5 | 0.747 (0.468-1.314) | 0.461 (0.282-0.857) | 0.169 (0.107-0.262) | 0.009 (0.003-0.069) | 0.103 (0.075-0.126) | 0.000 (0.000-0.000) | 0.000 (-0.000-0.000) | 1210 (1085-1442) (193 (149-230)) | 158 (113-283) (169 (143-206)) |
| main | measured 120 s | 8 | 0.545 (0.448-1.109) | 0.333 (0.271-0.713) | 0.122 (0.102-0.236) | 0.007 (0.003-0.026) | 0.084 (0.072-0.134) | 0.000 (0.000-0.000) | 0.000 (0.000-0.000) | 1431 (874-1622) (190 (144-253)) | 212 (87-329) (153 (118-204)) |
| fix | measured 120 s | 3 | 0.463 (0.440-0.465) | 0.279 (0.266-0.280) | 0.104 (0.099-0.109) | 0.003 (0.003-0.005) | 0.073 (0.072-0.073) | 0.000 (0.000-0.000) | 0.000 (0.000-0.000) | 1681 (1464-2201) (165 (141-185)) | 304 (295-338) (179 (162-210)) |

## Host load before each server (hostload.ps1)

- 2026-11-04 01:23:53 woo base-r1: outside 14-21 64%, on 14-21 57%; top: java#1 336%, node#3 230%, node#1 196%, MsMpEng 90%, System 50%, WmiPrvSE#2 46%
- 2026-11-04 01:31:52 woo main-r1: outside 14-21 65%, on 14-21 66%; top: node#3 231%, node#1 221%, dwm 120%, MsMpEng 84%, WmiPrvSE#2 45%, System 22%
- 2026-11-04 01:39:34 woo main-r2: outside 14-21 76%, on 14-21 73%; top: node#1 263%, node#3 263%, dwm 147%, MsMpEng 124%, Docker Desktop#4 104%, java#1 104%
- 2026-11-04 01:47:19 woo base-r2: outside 14-21 64%, on 14-21 42%; top: node#3 257%, node#1 216%, dwm 130%, Docker Desktop#4 115%, MsMpEng 69%, WmiPrvSE#2 52%
- 2026-11-04 01:55:01 woo base-r3: outside 14-21 67%, on 14-21 32%; top: java#1 264%, java#2 258%, node#3 235%, node#1 222%, dwm 130%, Docker Desktop#4 130%
- 2026-11-04 02:02:50 woo main-r3: outside 14-21 78%, on 14-21 64%; top: node#1 240%, node#3 234%, MsMpEng 130%, dwm 121%, SearchIndexer 103%, Docker Desktop#4 71%
- 2026-11-04 07:04:52 woo fix-r4: outside 14-21 55%, on 14-21 13%; top: vmmemWSL 818%, WmiPrvSE#2 41%, herdr 6%, msedge#18 6%, svchost#84 3%, System 3%
- 2026-11-04 07:21:59 woo fix-r4: outside 14-21 7%, on 14-21 10%; top: WmiPrvSE#2 33%, vmmemWSL 8%, herdr 4%, aw-watcher-window 4%, System 4%, svchost#84 4%
- 2026-11-04 07:44:13 woo main-r4: outside 14-21 10%, on 14-21 9%; top: MsMpEng 111%, WmiPrvSE#2 33%, System 12%, vmmemWSL 12%, herdr 8%, dwm 8%
- 2026-11-04 08:08:39 woo main-r5: outside 14-21 5%, on 14-21 0%; top: WmiPrvSE#2 33%, herdr 8%, powershell#20 8%, claude#2 4%, System 4%, dwm 4%
- 2026-11-04 08:16:38 woo fix-r5: outside 14-21 38%, on 14-21 3%; top: vmmemWSL 611%, WmiPrvSE#2 40%, herdr-app 29%, Docker Desktop#4 18%, herdr 14%, claude 14%
- 2026-11-04 08:24:59 woo fix-r6: outside 14-21 13%, on 14-21 2%; top: LenovoVantage-(DeviceSettingsSystemAddin) 44%, WmiPrvSE#2 32%, System 16%, dwm 8%, claude#4 4%, nvcontainer#3 0%
- 2026-11-04 08:34:18 woo main-r6: outside 14-21 15%, on 14-21 0%; top: java#1 667%, MsMpEng 64%, SearchIndexer 44%, WmiPrvSE#2 32%, vmmemWSL 28%, WUDFHost 8%
- 2026-11-04 10:03:03 woo base-r7: outside 14-21 21%, on 14-21 3%; top: WmiPrvSE#2 35%, chrome#46 23%, svchost#84 19%, MsMpEng 19%, System 15%, ms-teams#1 7%
- 2026-11-04 10:23:23 woo main-r7: outside 14-21 25%, on 14-21 10%; top: sd-cli 101%, svchost#84 76%, WmiPrvSE#2 32%, System 28%, MsMpEng 16%, herdr 8%
- 2026-11-04 10:39:16 woo main-r8: outside 14-21 23%, on 14-21 6%; top: MsMpEng 48%, WmiPrvSE#2 37%, chrome#2 26%, chrome#61 22%, LenovoVantage-(SmartPerformanceUserAddin) 22%, vmmemWSL 18%
- 2026-11-04 10:49:56 woo base-r8: outside 14-21 9%, on 14-21 6%; top: WmiPrvSE#2 32%, MsMpEng 32%, ms-teams 20%, vmmemWSL 12%, System 12%, herdr 8%
