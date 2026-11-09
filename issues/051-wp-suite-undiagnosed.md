# 051: WordPress PHPUnit suite: differences not diagnosed yet

**State:** Open. This is the remainder of the 2026-11-08 run (`tests/wp-phpunit`, report
`target/wp-phpunit/report-wordpress.txt`): tests that pass on PHP 8.5 and not here, and are not explained by
034 or 038–050. Each needs a reduced test, then its own issue.

| Tests | Where | Message |
|---:|---|---|
| 11 | `Tests_Admin_ExportWp` | `Error: Object of class WP_User could not be converted to string` |
| 18 | `Tests_Error_Protection_*` | `Undefined array key ...` |
| 8 | `Tests_Admin_Includes_Misc_WpDocLinkParse_Test` | `Failed asserting that two arrays are identical` |
| 6 | `Tests_Admin_wpCommunityEvents` | size mismatch, `Undefined array key` |
| 5 | `Tests_oEmbed_Discovery` | strings differ (wp_head output) |
| 5 | `Tests_XMLRPC_wp_newPost` and others | strings differ |
| 5 | `WP_REST_Plugins_Controller_Test` | "The filesystem is currently unavailable", error codes |
| 4 | `WP_Test_REST_Widgets_Controller` | arrays differ |
| 4 | `Tests_REST_API` | `false is identical to N` |
| 3 | `Tests_Comment_CheckComment` | `true is false` |
| 3 | `Tests_Formatting_Date` | "Local time from local time" |
| 3 | `Tests_Post_UpdatePostCache` | the cached post lacks a property |
| 2 | `Tests_Date_DateI18n` | `Failed to parse time string (-N:N) ... The timezone could not be found in the database` |
| 2+2 | `Test_WP_Customize_Nav_Menu_Item_Setting`, `Tests_Post_GetPostTypeLabels` | object lacks a property |

**Hangs and crashes:**
- Two process-isolated tests hang: `UninstallPlugin::test_uninstall_plugin_loads_uninstall_file` and
  `GetMediaStates::test_header_image_state_with_random_header_images`. After one is killed, the next process gets
  "Error establishing a database connection".
- `Test_oEmbed_Controller::test_rest_pre_serve_request` ends the PHPUnit process (exit 0) in the middle of the test.
- The `template.php` chunk exits with 255 and prints nothing, after all of its 87 tests have run.

**956 tests didn't run here at all.** They come from three causes:
- the tests after the link hangs ([042](042-var-export-recursion.md));
- data providers that failed (`DateTimeZone::getTransitions`, `IMAGETYPE_JPEG`; [049](049-missing-extensions-wp-suite.md));
- suites skipped as a whole.

Each fix should shrink `tests/wp-phpunit/wordpress.failset.tsv`. `compare.py --frozen` shows a test that newly
differs.
