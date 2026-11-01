"""The WooCommerce store's pages and actions, as a customer and the shop manager would go through them, against one
server (tests/gate2/woo.sh walk). Each response is saved masked (nonces, keys, tokens, dates) as OUT/NAME.body,
its status and Location as OUT/NAME.status, so two engines' walks compare file by file (woo.sh compare).

A customer: the home page, the shop, a category, a simple and a variable product, adding to the cart by the
product page's form and by the shop's AJAX button, the cart, the block checkout page, an order placed through the
classic checkout form (wc-ajax=checkout), its order-received page, an order placed through the Store API
(/wc/store/v1/cart/add-item, /checkout), my account. The shop manager (admin): logs in, my account and its orders,
wp-admin's orders list, an order's edit screen, the products list, WooCommerce's home and settings, and the REST API
(wc/v3, wc-analytics, wc-admin, the Store API's products). Then wp-cron.php, which runs what the orders scheduled.

Usage: python woo_walk.py BASE_URL OUT_DIR
"""
import http.client
import json
import os
import re
import sys
import urllib.parse

HOST = "localhost"
MASKS = [
    (r'(nonce|_wpnonce|"nonce"|Nonce)([^0-9a-f]{1,12})[0-9a-f]{10}', r'\1\2NONCE'),
    (r'(\x22|\x27)[0-9a-f]{10}(\x22|\x27)', r'\1NONCE\2'),
    (r'(%22|%5C%22)[0-9a-f]{10}(%22|%5C%22)', r'\1NONCE\2'),
    (r'\A[0-9a-f]{10}\Z', 'NONCE'),
    (r'"Nonce-Timestamp":[0-9]+', '"Nonce-Timestamp":TIME'),
    # related products are shuffled (wc_get_related_products)
    (r'"related_ids":\[[0-9,]*\]', '"related_ids":[IDS]'),
    # uniqid() in element ids (quantity_6ae7c2321636e)
    (r'\b([a-z_]+_)[0-9a-f]{13}\b', r'\1UNIQID'),
    (r'data-pw="[^"]*"', 'data-pw="PW"'),
    (r'wc_order_[A-Za-z0-9]{13}', 'wc_order_KEY'),
    (r'\bt_[0-9a-f]{30}\b', 't_SESSION'),
    (r'[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}', 'DATETIME'),
    (r'[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9:]{8} [+-][0-9]{4}', 'DATE'),
    (r'"[0-9]{10}:', '"TIME:'),
    (r'ver=[0-9a-f.-]+', 'ver=V'),
    (r'eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+', 'JWT'),
    (r'(<div class="ss">|name="ss" value=")[0-9]{2}', r'\1SS'),
    (r'(class="date-time-text format-i18n">|<span class="example">)[^<]*', 'TIME'),
]


def mask(text):
    for pattern, replacement in MASKS:
        text = re.sub(pattern, replacement, text)
    return text


class Session:
    """One browser: its cookies (every cookie sent everywhere, as WordPress's paths don't matter here)."""

    def __init__(self, base, out):
        self.base = urllib.parse.urlparse(base)
        self.out = out
        self.cookies = {}
        self.headers = {}

    def request(self, name, method, path, body=None, headers=None, follow=False):
        """(status, headers, body text) of one request, saved as NAME; follow: one redirect, saved as NAME-next."""
        connection = http.client.HTTPConnection(self.base.hostname, self.base.port, timeout=300)
        send = {"Host": HOST, "User-Agent": "woo-walk"}
        if self.cookies:
            send["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if isinstance(body, dict):
            body = urllib.parse.urlencode(body, doseq=True)
            send["Content-Type"] = "application/x-www-form-urlencoded"
        elif isinstance(body, (list, tuple)):
            body = json.dumps(body[0])
            send["Content-Type"] = "application/json"
        send.update(self.headers)
        send.update(headers or {})
        connection.request(method, path, body=body, headers=send)
        response = connection.getresponse()
        data = response.read().decode("utf-8", "replace")
        received = response.getheaders()
        connection.close()
        for key, value in received:
            if key.lower() == "set-cookie":
                cookie = value.split(";", 1)[0]
                cookie_name, _, cookie_value = cookie.partition("=")
                if "expires=Thu, 01-Jan-1970" in value or "Max-Age=0" in value or cookie_value in ("", "deleted"):
                    self.cookies.pop(cookie_name, None)
                else:
                    self.cookies[cookie_name] = cookie_value
        location = next((v for k, v in received if k.lower() == "location"), None)
        with open(os.path.join(self.out, name + ".body"), "w", encoding="utf-8", newline="\n") as f:
            f.write(mask(data))
        with open(os.path.join(self.out, name + ".status"), "w", encoding="utf-8", newline="\n") as f:
            f.write(f"{response.status}\n")
            if location:
                f.write(mask(location) + "\n")
        print(f"{name}: {response.status} {len(data)} bytes{' -> ' + location if location else ''}")
        result = (response.status, dict((k.lower(), v) for k, v in received), data)
        if follow and location:
            return self.request(name + "-next", "GET", local(location))
        return result


def local(url):
    """A URL the site gave (http://localhost/...) as a path on the server under test."""
    parsed = urllib.parse.urlparse(url)
    return parsed.path + ("?" + parsed.query if parsed.query else "")


ADDRESS = {"first_name": "Ada", "last_name": "Lovelace", "company": "", "address_1": "1 Analytical Way",
           "address_2": "", "city": "San Francisco", "state": "CA", "postcode": "94110", "country": "US"}


def customer(s):
    s.request("home", "GET", "/")
    s.request("shop", "GET", "/shop/")
    s.request("category", "GET", "/product-category/clothing/")
    s.request("product-simple", "GET", "/product/beanie/")
    s.request("product-variable", "GET", "/product/hoodie/")
    s.request("add-form", "POST", "/product/beanie/", {"add-to-cart": "14", "quantity": "2"})
    s.request("add-ajax", "POST", "/?wc-ajax=add_to_cart", {"product_id": "16", "quantity": "1"})
    s.request("cart", "GET", "/cart/")
    s.request("checkout-block", "GET", "/checkout/")
    _, _, page = s.request("checkout-classic-form", "GET", "/classic-checkout/")
    nonce = re.search(r'name="woocommerce-process-checkout-nonce" value="([0-9a-f]+)"', page)
    form = {f"billing_{k}": v for k, v in ADDRESS.items()}
    form.update({"billing_phone": "5551234", "billing_email": "ada@example.com", "ship_to_different_address": "",
                 "order_comments": "classic", "shipping_method[0]": "flat_rate:1", "payment_method": "cod",
                 "terms": "on", "terms-field": "1", "_wp_http_referer": "/?wc-ajax=update_order_review",
                 "woocommerce-process-checkout-nonce": nonce.group(1) if nonce else ""})
    _, _, placed = s.request("checkout-classic-place", "POST", "/?wc-ajax=checkout", form)
    try:
        redirect = json.loads(placed).get("redirect")
    except ValueError:
        redirect = None
    if redirect:
        s.request("order-received-classic", "GET", local(redirect))
    # the Store API, as the block checkout drives it
    _, headers, _ = s.request("store-cart", "GET", "/wp-json/wc/store/v1/cart")
    store = {"Nonce": headers.get("nonce", ""), "Cart-Token": headers.get("cart-token", "")}
    s.request("store-add-item", "POST", "/wp-json/wc/store/v1/cart/add-item", [{"id": 13, "quantity": 3}], store)
    s.request("store-cart-after", "GET", "/wp-json/wc/store/v1/cart", headers=store)
    order = {"billing_address": dict(ADDRESS, email="grace@example.com", phone="5559876"),
             "shipping_address": ADDRESS, "payment_method": "cod", "customer_note": "store api"}
    _, _, placed = s.request("store-checkout", "POST", "/wp-json/wc/store/v1/checkout", [order], store)
    try:
        redirect = json.loads(placed).get("payment_result", {}).get("redirect_url")
    except ValueError:
        redirect = None
    if redirect:
        s.request("order-received-store", "GET", local(redirect))
    s.request("my-account-guest", "GET", "/my-account/")


def manager(s):
    s.request("login-form", "GET", "/wp-login.php")
    s.cookies["wordpress_test_cookie"] = "WP%20Cookie%20check"
    s.request("login", "POST", "/wp-login.php", {"log": "admin", "pwd": "admin", "testcookie": "1",
                                                  "wp-submit": "Log In", "redirect_to": "http://localhost/wp-admin/"})
    s.request("my-account", "GET", "/my-account/")
    s.request("my-account-orders", "GET", "/my-account/orders/")
    _, _, orders = s.request("admin-orders", "GET", "/wp-admin/admin.php?page=wc-orders")
    found = re.search(r'page=wc-orders&(?:amp;)?action=edit&(?:amp;)?id=([0-9]+)', orders)
    s.request("admin-order-edit", "GET", f"/wp-admin/admin.php?page=wc-orders&action=edit&id={found.group(1) if found else 0}")
    s.request("admin-products", "GET", "/wp-admin/edit.php?post_type=product")
    s.request("admin-wc-home", "GET", "/wp-admin/admin.php?page=wc-admin")
    s.request("admin-wc-settings", "GET", "/wp-admin/admin.php?page=wc-settings")
    _, _, nonce = s.request("rest-nonce", "GET", "/wp-admin/admin-ajax.php?action=rest-nonce")
    rest = {"X-WP-Nonce": nonce.strip()}
    s.request("rest-orders", "GET", "/wp-json/wc/v3/orders", headers=rest)
    s.request("rest-products", "GET", "/wp-json/wc/v3/products?per_page=5&orderby=id&order=asc", headers=rest)
    s.request("rest-analytics-revenue", "GET", "/wp-json/wc-analytics/reports/revenue/stats?interval=day"
              "&after=2000-01-01T00:00:00&before=2100-01-01T00:00:00", headers=rest)
    s.request("rest-analytics-orders", "GET", "/wp-json/wc-analytics/reports/orders?per_page=5", headers=rest)
    s.request("rest-admin-options", "GET", "/wp-json/wc-admin/options?options=woocommerce_default_country", headers=rest)
    s.request("store-products", "GET", "/wp-json/wc/store/v1/products?per_page=3&orderby=title&order=asc")


def main():
    base, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        os.remove(os.path.join(out, f))
    customer(Session(base, out))
    manager(Session(base, out))
    Session(base, out).request("wp-cron", "GET", "/wp-cron.php?doing_wp_cron=1")


if __name__ == "__main__":
    main()
