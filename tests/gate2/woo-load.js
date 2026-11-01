// k6 load scenario for tests/gate2/woo.sh load: concurrent customers of the WooCommerce store, each iteration a new
// visitor with a cookie jar of its own. A browser's requests, without the static files: the home page, the shop, a
// product; a buyer then adds it to the cart with the product page's form, opens the cart and the (block) checkout
// page, reads the Store API nonce from it as the checkout block does, places the order through the Store API
// (cash on delivery) and opens the order-received page. A browser only looks at a category and a second product.
// No think time: the virtual users keep the server busy, so the run measures its throughput at that concurrency.
//
// Env: BASE (http://172.31.77.13:8080), VUS, DURATION (e.g. 60s), BUY (share of visitors who buy, 0..1),
// SUMMARY (where handleSummary writes its JSON). Each order is logged as "ORDER <id> <product> <qty>" for the
// database check that follows the run.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://172.31.77.13:8080';
const BUY = parseFloat(__ENV.BUY || '0.2');
const HEADERS = { Host: 'localhost' };
// the store's stocked simple products: id and slug
const PRODUCTS = [[12, 'hoodie-with-logo'], [13, 't-shirt'], [14, 'beanie'], [15, 'belt'], [16, 'cap'],
    [17, 'sunglasses'], [18, 'hoodie-with-pocket'], [19, 'hoodie-with-zipper'], [20, 'long-sleeve-tee'],
    [21, 'polo'], [30, 't-shirt-with-logo'], [31, 'beanie-with-logo']];
const CATEGORIES = ['clothing', 'tshirts', 'hoodies', 'accessories'];

const orders = new Counter('orders');
const failedOrders = new Counter('orders_failed');
const pages = new Counter('pages');
const orderTime = new Trend('order_flow_duration', true);

export const options = {
    scenarios: {
        customers: {
            executor: 'constant-vus',
            vus: parseInt(__ENV.VUS || '1', 10),
            duration: __ENV.DURATION || '60s',
            gracefulStop: '120s',
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
    discardResponseBodies: false,
};

function get(path, jar, name) {
    const response = http.get(BASE + path, { headers: HEADERS, jar, redirects: 0, tags: { name } });
    pages.add(1);
    return response;
}

function local(url) {
    const match = /^https?:\/\/[^/]+(\/.*)$/.exec(url);
    return match ? match[1] : url;
}

export default function () {
    const jar = new http.CookieJar();
    const product = PRODUCTS[Math.floor(Math.random() * PRODUCTS.length)];
    check(get('/', jar, 'home'), { 'home 200': (r) => r.status === 200 });
    check(get('/shop/', jar, 'shop'), { 'shop 200': (r) => r.status === 200 });
    const productPage = get(`/product/${product[1]}/`, jar, 'product');
    check(productPage, { 'product 200': (r) => r.status === 200 && r.body.includes('add-to-cart') });
    if (Math.random() >= BUY) {
        const category = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
        check(get(`/product-category/${category}/`, jar, 'category'), { 'category 200': (r) => r.status === 200 });
        const other = PRODUCTS[Math.floor(Math.random() * PRODUCTS.length)];
        check(get(`/product/${other[1]}/`, jar, 'product'), { 'product 200': (r) => r.status === 200 });
        return;
    }
    const started = Date.now();
    const added = http.post(BASE + `/product/${product[1]}/`, { 'add-to-cart': String(product[0]), quantity: '1' },
        { headers: HEADERS, jar, redirects: 0, tags: { name: 'add-to-cart' } });
    pages.add(1);
    check(added, { 'add-to-cart 200': (r) => r.status === 200 && r.body.includes('has been added to your cart') });
    check(get('/cart/', jar, 'cart'), { 'cart 200': (r) => r.status === 200 });
    const checkout = get('/checkout/', jar, 'checkout');
    const nonce = /storeApiNonce: '([0-9a-f]+)'/.exec(checkout.body || '');
    check(checkout, { 'checkout 200': (r) => r.status === 200 && nonce !== null });
    const address = { first_name: 'Ada', last_name: 'Lovelace', company: '', address_1: '1 Analytical Way',
        address_2: '', city: 'San Francisco', state: 'CA', postcode: '94110', country: 'US' };
    const body = JSON.stringify({ billing_address: Object.assign({ email: 'ada@example.com', phone: '5551234' }, address),
        shipping_address: address, payment_method: 'cod' });
    const placed = http.post(BASE + '/wp-json/wc/store/v1/checkout', body, { jar, redirects: 0,
        headers: Object.assign({ 'Content-Type': 'application/json', Nonce: nonce ? nonce[1] : '' }, HEADERS),
        tags: { name: 'place-order' } });
    pages.add(1);
    let result = null;
    try {
        result = placed.json();
    } catch (e) {
        result = null;
    }
    const ok = placed.status === 200 && result !== null && result.status === 'processing';
    check(placed, { 'order placed': () => ok });
    if (!ok) {
        failedOrders.add(1);
        console.log(`FAILED ${placed.status} ${String(placed.body).substring(0, 300)}`);
        return;
    }
    orders.add(1);
    console.log(`ORDER ${result.order_id} ${product[0]} 1`);
    const received = get(local(result.payment_result.redirect_url), jar, 'order-received');
    check(received, { 'order-received 200': (r) => r.status === 200 && r.body.includes('Thank you') });
    orderTime.add(Date.now() - started);
}

export function handleSummary(data) {
    const out = {};
    out[__ENV.SUMMARY || '/out/summary.json'] = JSON.stringify(data, null, 1);
    return out;
}
