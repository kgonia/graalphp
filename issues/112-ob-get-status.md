# 112: `ob_get_status()` is missing

**State:** Fixed (branch `elementor-compat`)

**Symptom:** Elementor's image loading optimization fails while flushing its header buffer:
```
PHP Fatal error:  Uncaught Error: Call to undefined function Elementor\Modules\ImageLoadingOptimization\ob_get_status()
  in wp-content/plugins/elementor/modules/image-loading-optimization/module.php:91
```

**Fix:** `ob_get_status($full_status = false)` returns php-src's `php_output_handler_status` for the current buffer,
or for every level from the outermost. Each entry has `name`, `type`, `flags`, `level`, `chunk_size`, `buffer_size`
and `buffer_used`.
- `flags` is the buffer's abilities (`$flags & 0xf0`), plus USER (1) for a callback handler. STARTED (0x1000)
  is added once the handler has run, PROCESSED (0x4000) once it ran without failing, and DISABLED (0x2000) once it
  failed.
- `buffer_size` models php-src's allocation. It starts at `PHP_OUTPUT_HANDLER_INITBUF_SIZE(chunk_size)`, 16 KB
  or the chunk size rounded up past the next 4 KB. It grows as `php_output_handler_append` grows it. A failed
  handler hands its buffer on, so its size drops to 0.
- `ob_start()` now keeps its `chunk_size` (`OutputBuffer.chunkSize`). A negative size is 0.

Test: `tests/smoke/ob-get-status.php`.

Not covered: a buffer with a chunk size still isn't flushed when it fills (php-src's chunked buffering). The size
is recorded and reported only.
