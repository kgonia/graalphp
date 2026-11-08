# 049: Extensions and built-ins that WordPress's test suite needs and the runtime lacks

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. Each row was checked on 2026-11-08 with
`function_exists`, `class_exists` or `defined`. The reference is the harness's PHP 8.5 image, which has gd and exif.

| What | Missing | Tests (pass on PHP, not here) |
|---|---|---:|
| gd | `getimagesize`, `imagecreatetruecolor`, `imagejpeg` and the rest, `IMAGETYPE_*` constants | ~300: 116 "imagejpeg is required", 46 `getimagesize`, 21 GD editor, 88 `IMAGETYPE_GIF` in `wpSetUpBeforeClass` (whole classes skipped, e.g. `Tests_Media` 192), data providers |
| Output constants | `PHP_OUTPUT_HANDLER_CLEAN` (`_STDFLAGS` exists) | part of the `Undefined constant` 124 (`Tests_Template` 42) |
| mbstring | `8bit` encoding ([034](034-mb-8bit-encoding.md)); `mb_convert_encoding`, `mb_stripos` | 116, 11, 5 |
| xml / dom / libxml | `xml_parser_create`, `DOMDocument`, `SimpleXMLElement`, `XMLReader`, `XMLWriter`, `libxml_*` | 17 + 21 + 12 + 7 + 4, feeds; PHPUnit 9's XML configuration (the harness loads it on PHP) |
| zlib | `gzinflate`, `gzdeflate` | 32 |
| fileinfo | `finfo_open`, `finfo` | 18 |
| exif | `exif_read_data` | 14 |
| network | `inet_ntop`, `inet_pton` | 10 |
| hash | `hash_file` | 2 |
| password | `PASSWORD_ARGON2I`, `PASSWORD_ARGON2ID` | 2 |
| date | `DateTimeZone::getTransitions()` | data providers fail, so their tests don't run |
| CLI and files | `getopt`, `chdir`, `get_include_path`, `set_include_path`, `stream_resolve_include_path` | PHPUnit start-up (the harness shims them) |
| SPL | `AppendIterator`, `RecursiveFilterIterator` | PHPUnit start-up (shimmed) |

Not in this table: built-ins that exist but behave differently. Those have their own issues (038–048).

**Fix:** to do, one built-in or extension at a time, each as php-src does. By test count, gd and `IMAGETYPE_*`
come first. The constants alone unblock whole classes whose `wpSetUpBeforeClass` uses them. Before writing
an extension, decide how to implement it: a Java port, a library (gd: Java2D/ImageIO cannot match libgd's
output byte for byte), or native code through FFM.
