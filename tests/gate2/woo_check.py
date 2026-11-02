"""The database after one load run (tests/gate2/woo.sh load) against what k6 says it did: every order k6 placed
is in wp_wc_orders, processing, with one line item of the product and quantity k6 logged; no other order made
during the run; no order id or order key twice; each stocked product's stock (post meta and
wc_product_meta_lookup) is its seed stock less what the orders took, and its total_sales is what they took. Orders
created before the run's first request (the seed's own) count as the seed's: their items are in its stock. Prints a line of JSON: the counts and the problems.

Usage: python woo_check.py DB_CONTAINER K6_LOG SEED_STOCK
"""
import collections
import json
import re
import subprocess
import sys


def query(container, sql):
    out = subprocess.run(["docker", "exec", container, "mariadb", "-uroot", "-proot", "-P3307", "wordpress", "-N",
                          "-B", "-e", sql], check=True, capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines() if line]


def main():
    container, log, seed_stock = sys.argv[1], sys.argv[2], int(sys.argv[3])
    placed = {}
    start = None
    for line in open(log, encoding="utf-8", errors="replace"):
        stamp = re.match(r'time="([0-9-]+)T([0-9:]+)Z"', line)
        if stamp and start is None:
            start = stamp.group(1) + " " + stamp.group(2)
        match = re.search(r'msg="ORDER ([0-9]+) ([0-9]+) ([0-9]+)"', line)
        if match:
            placed.setdefault(int(match.group(1)), []).append((int(match.group(2)), int(match.group(3))))
    problems = []
    for order, items in placed.items():
        if len(items) > 1:
            problems.append(f"k6 got order id {order} {len(items)} times")
    orders, seeded = {}, set()
    for i, status, created in query(container, "select id, status, date_created_gmt from wp_wc_orders"
                                               " where type = 'shop_order'"):
        if start is not None and created < start and int(i) not in placed:
            seeded.add(int(i))
        else:
            orders[int(i)] = status
    keys = query(container, "select order_key, count(*) from wp_wc_order_operational_data group by order_key"
                            " having count(*) > 1")
    if keys:
        problems.append(f"order keys twice: {keys[:5]}")
    for order in placed:
        if order not in orders:
            problems.append(f"order {order} placed but not in wp_wc_orders")
        elif orders[order] != "wc-processing":
            problems.append(f"order {order} is {orders[order]}")
    for order in orders:
        if order not in placed:
            problems.append(f"order {order} ({orders[order]}) in wp_wc_orders but k6 did not get it")
    lines = collections.defaultdict(list)
    for order, product, qty in query(container, """
            select i.order_id, max(if(m.meta_key = '_product_id', m.meta_value, null)),
                   max(if(m.meta_key = '_qty', m.meta_value, null))
            from wp_woocommerce_order_items i join wp_woocommerce_order_itemmeta m using (order_item_id)
            where i.order_item_type = 'line_item' group by i.order_item_id"""):
        lines[int(order)].append((int(product), int(qty)))
    taken = collections.Counter()
    seed_taken = collections.Counter()
    for order in seeded:
        for product, qty in lines.get(order, []):
            seed_taken[product] += qty
    for order, items in placed.items():
        taken.update({product: qty for product, qty in items})
        if sorted(lines.get(order, [])) != sorted(items):
            problems.append(f"order {order}: line items {lines.get(order)}, k6 placed {items}")
    meta = {(int(p), k): v for p, k, v in query(container, "select post_id, meta_key, meta_value from wp_postmeta"
                                                            " where meta_key in ('_stock', 'total_sales')")}
    lookup = {int(p): q for p, q in query(container, "select product_id, stock_quantity from wp_wc_product_meta_lookup")}
    stocked = [p for (p, k), v in meta.items() if k == "_stock" and v not in ("", "NULL", None) and p in lookup]
    for product in stocked:
        want = seed_stock - taken[product] - seed_taken[product]
        if int(float(meta[(product, "_stock")])) != want:
            problems.append(f"product {product}: _stock {meta[(product, '_stock')]}, want {want}")
        if lookup[product] in ("NULL", None) or int(float(lookup[product])) != want:
            problems.append(f"product {product}: lookup stock {lookup[product]}, want {want}")
        sales = meta.get((product, "total_sales"), "0")
        if int(sales or 0) != taken[product] + seed_taken[product]:
            problems.append(f"product {product}: total_sales {sales}, want {taken[product] + seed_taken[product]}")
    print(json.dumps({"placed": len(placed), "orders": len(orders), "seed_orders": len(seeded), "items": sum(taken.values()),
                      "products": len(stocked), "problems": problems[:20], "problem_count": len(problems)}))


if __name__ == "__main__":
    main()
