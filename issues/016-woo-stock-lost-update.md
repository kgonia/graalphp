# 016: A WooCommerce `_stock` decrement is lost now and then under load

**State:** Won't fix: WooCommerce's own race on MariaDB, the same on php-fpm (2026-11-03)

**Symptom:** about one load run in eight (`woo.sh load`, 16 users, every visitor buying) ends with one product's
`_stock` (and `wc_product_meta_lookup.stock_quantity`) one higher than its orders say; the orders, their items and
`total_sales` are right. Seen on every build, old bases too (`wiki/pe-round4.md`), and on php-fpm 16
(`wiki/woocommerce.md`; `bench/woocommerce/runs.txt`: `fpm16-r1-1-8` product 14 `_stock 99972, want 99971`,
`fpm16-r3-1-16` product 14, and fpm8 runs).

**Cause:** an InnoDB deadlock between two checkouts, whose victim is the stock decrement, which WooCommerce doesn't
check:
- Checkout A reserves stock (`Automattic\WooCommerce\Checkout\Helpers\ReserveStock`) in a transaction:
  `INSERT INTO wp_wc_reserved_stock ... SELECT ... WHERE (SELECT ... FROM wp_postmeta WHERE meta_key = '_stock'
  AND post_id = P FOR UPDATE) - (...) >= 1`, which locks `wp_postmeta` rows through the `meta_key` index.
- Checkout B, whose order is placed, reduces stock (`wc_reduce_stock_levels` → `update_product_stock`) with an
  autocommit `UPDATE wp_postmeta SET meta_value = meta_value -1.000000 WHERE post_id = P AND meta_key='_stock'`,
  which locks by the primary key and then the `meta_key` index.
- The two lock the same rows in opposite orders; InnoDB detects the deadlock and rolls one back. When it rolls
  back B's UPDATE, `$wpdb->query()` returns false, `wpdb` logs "Deadlock found", and `update_product_stock`
  returns the new stock it computed anyway (its own comment: "If the lock is not acquired, change is lost.").

**Evidence** (the round-4 load runs, `wordpress-pe4impl/target/p4i/load`, 32 server lives, 4 with the loss):

| run | lost decrement | InnoDB's latest deadlock in that run | rolled back | server log |
|---|---|---|---|---|
| `base_t1.base-r4-1-16` | product 13 | 23:25:15, reserve INSERT vs `UPDATE ... post_id = 13` | the UPDATE | `Deadlock found ... UPDATE wp_postmeta SET meta_value = meta_value -1.000000 WHERE post_id = 13` |
| `base_t3.base-r4-1-16` | product 12 | 20:22:33, `UPDATE ... post_id = 12` vs reserve INSERT | the UPDATE | the same, post 12 |
| `t2_t1.t1-r4-1-16` | product 14 | 22:17:36, `UPDATE ... post_id = 14` vs reserve INSERT | the UPDATE | the same, post 14 |
| `t3_t2.t2-r1-1-16` | product 13 | 20:37:32, reserve INSERT vs `UPDATE ... post_id = 13` | the UPDATE | the same, post 13 |

Every server life's log has exactly as many "Deadlock found" errors on the stock UPDATE as its runs lost
decrements (4 and 4, same products); the two other deadlocks logged (reserve INSERT as the victim) lost nothing.
Our mysqli reported error 1213 as PHP's does, so `wpdb` logged it.

**Not ours:** the SQL and its order are WooCommerce's, the locking is MariaDB 11.4's (REPEATABLE READ), and php-fpm
loses decrements the same way. The JVM sees it less often per order only because it places fewer orders per
second. `woo_check.py` could count these deadlocks from the server log, to tell this race from a real lost write.
