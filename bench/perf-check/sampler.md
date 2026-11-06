# Truffle CPU sampler, Gate 2 diagnosis servers (CPUs 6-13, not timed), base against main

Each run: 200 paced visits, the sampler from 170 s on (about visit 60), every request context's histogram summed
(`python -I bench/perf-check/samptop.py target/pc-x/out/base/cpu.txt target/pc-x/out/main/cpu.txt`). The two runs
were at different host loads (base's cold visit 25 s, main's 12 s): compare shares, not milliseconds.

```
self time total: A 90930 ms, B 62790 ms (0.691)
-- largest self-time increases B - A (ms), A, B
  +1260     220    1480  usort
   +120      90     210  strpos
    +70     200     270  serialize
    +70      10      80  wp-admin/includes/secrets-site-health.php
    +60       0      60  wp-includes/class-wp-plugin-dependencies.php
    +50     490     540  array_merge
    +50      20      70  array_diff_key
    +40      20      60  wp-includes/rest-api/endpoints/class-wp-rest-attachments-controller.php
    +40     240     280  class_exists
    +30      10      40  wp-includes/sodium_compat/lib/constants.php
    +30      50      80  wp-includes/theme.php
    +30      10      40  wp-includes/script-modules.php
    +30       0      30  ob_start
    +30      10      40  ob_get_clean
    +30      10      40  wp-includes/ai-client/class-wp-ai-client-ability-function-resolver.php
    +30       0      30  wp-includes/rest-api/endpoints/class-wp-rest-navigation-fallback-controller.php
    +30       0      30  wp-includes/interactivity-api/class-wp-interactivity-dangerous-html.php
    +30      20      50  wp-includes/class-wp-walker.php
    +30       0      30  wp-includes/rest-api/endpoints/class-wp-rest-users-controller.php
    +30      30      60  is_int
    +30       0      30  wp-includes/rest-api/search/class-wp-rest-post-search-handler.php
    +20      10      30  wp-includes/comment-template.php
    +20       0      20  wp-includes/php-ai-client/src/Providers/Http/Abstracts/AbstractClientDiscoveryStrategy.php
    +20      40      60  wp-includes/version.php
    +20      70      90  wp-includes/deprecated.php
    +20      10      30  wp-includes/class-wp-speculation-rules.php
    +20      10      30  wp-includes/Requests/src/Requests.php
    +20      10      30  wp-includes/Requests/src/Transport/Curl.php
    +20     310     330  str_replace
    +20      10      30  wp-includes/abilities/class-wp-abilities-content.php
    +20      10      30  wp-includes/class-wp-post-type.php
    +20       0      20  wp-includes/style-engine/class-wp-style-engine-css-rule.php
    +20      10      30  strncmp
    +20      10      30  is_link
    +20       0      20  extension_loaded
-- largest decreases
  -4600   12940    8340  wp-settings.php
  -4010   15120   11110  wp-includes/template-canvas.php
  -1910    4910    3000  defined
  -1080    5330    4250  wp-admin/admin-header.php
  -1060    2850    1790  wp-admin/edit.php
   -810    1410     600  array_keys
   -770    2280    1510  wp-admin/index.php
   -690    1380     690  is_array
   -680    2480    1800  wp-login.php
   -640    1100     460  array_unshift
   -580    1550     970  wp-admin/menu.php
   -530    1340     810  in_array
   -500    1130     630  define
   -470    1870    1400  mysqli_real_connect
   -400    1940    1540  wp-admin/admin-footer.php
-- top self in B
  11110   15120  wp-includes/template-canvas.php
   8340   12940  wp-settings.php
   4250    5330  wp-admin/admin-header.php
   3000    4910  defined
   1800    2480  wp-login.php
   1790    2850  wp-admin/edit.php
   1540    1940  wp-admin/admin-footer.php
   1510    2280  wp-admin/index.php
   1480     220  usort
   1400    1870  mysqli_real_connect
   1290    1670  wp-includes/template-loader.php
   1190    1420  password_verify
    970    1550  wp-admin/menu.php
    930    1270  wp-admin/admin.php
    810    1340  in_array
    770     910  wp-includes/default-filters.php
    690    1380  is_array
    640     920  wp-admin/menu-header.php
    630    1130  define
    600    1410  array_keys
    580     720  file_exists
    540     490  array_merge
    460    1100  array_unshift
    370     630  mysqli_query
    360     730  is_string
```
