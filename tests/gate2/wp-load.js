// k6 load scenario for tests/gate2/native.sh load: concurrent visitors of Gate 2's WordPress, without think time,
// so the run measures the server's throughput at that concurrency. Each iteration is a reader (READERS of them,
// default 0.8: a new cookie jar, the home page, a post, a page, a category, a search and the feed) or the admin
// (the virtual user's own jar, logged in once on its first admin iteration so that the sessions don't pile up in
// wp_usermeta: the dashboard and the posts list). Static files aren't fetched: nginx serves them alike for every
// backend.
//
// Env: BASE (http://172.30.0.10:8080), VUS, DURATION (e.g. 60s), READERS (0..1), SUMMARY (handleSummary's JSON).
// A page with a PHP warning or error is logged as "PHPERROR <url> <message>".
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://172.30.0.10:8080';
const READERS = parseFloat(__ENV.READERS || '0.8');
const HEADERS = { Host: 'localhost' };
const READER_PAGES = [['home', '/'], ['post', '/?p=1'], ['page', '/?page_id=2'], ['category', '/?cat=1'],
    ['search', '/?s=hello'], ['feed', '/?feed=rss2']];
const ADMIN_PAGES = [['dashboard', '/wp-admin/'], ['posts', '/wp-admin/edit.php']];
const NAMES = READER_PAGES.concat(ADMIN_PAGES).map((p) => p[0]).concat(['login']);

const pages = new Counter('pages');
const phpErrors = new Counter('php_errors');
// display_errors=1, html_errors=0 on every engine: a warning or error is a line of the page
const PHP_ERROR = /(^|\n)(PHP )?(Fatal error|Parse error|Warning|Notice|Deprecated|Uncaught [A-Za-z]+): .* on line [0-9]+/;
const thresholds = {};
// a threshold that always holds puts each page kind's own durations into the summary
for (const name of NAMES) {
    thresholds[`http_req_duration{name:${name}}`] = ['max>=0'];
}

export const options = {
    scenarios: {
        visitors: {
            executor: 'constant-vus',
            vus: parseInt(__ENV.VUS || '1', 10),
            duration: __ENV.DURATION || '60s',
            gracefulStop: '120s',
        },
    },
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

function counted(response) {
    pages.add(1);
    const body = String(response.body);
    if (PHP_ERROR.test(body)) {
        phpErrors.add(1);
        console.log(`PHPERROR ${response.request.url} ${PHP_ERROR.exec(body)[0].trim().substring(0, 300)}`);
    }
    return response;
}

function get(path, jar, name) {
    return counted(http.get(BASE + path, { headers: HEADERS, jar, redirects: 0, tags: { name } }));
}

let adminJar = null;

export default function () {
    if (Math.random() < READERS) {
        const jar = new http.CookieJar();
        for (const [name, path] of READER_PAGES) {
            check(get(path, jar, name), { [`${name} 200`]: (r) => r.status === 200 });
        }
        return;
    }
    if (adminJar === null) {
        adminJar = new http.CookieJar();
        const login = counted(http.post(BASE + '/wp-login.php', {
            log: 'admin', pwd: 'admin', testcookie: '1', 'wp-submit': 'Log In',
            redirect_to: 'http://localhost/wp-admin/',
        }, { headers: HEADERS, jar: adminJar, redirects: 0, tags: { name: 'login' },
            cookies: { wordpress_test_cookie: 'WP Cookie check' } }));
        check(login, { 'login 302': (r) => r.status === 302 });
    }
    for (const [name, path] of ADMIN_PAGES) {
        const page = get(path, adminJar, name);
        if (!check(page, { [`${name} 200`]: (r) => r.status === 200 && String(r.body).includes('</html>') })) {
            console.log(`ADMINFAIL ${name} ${page.status} ${page.headers.Location || ''} ${String(page.body).length}`);
        }
    }
}

export function handleSummary(data) {
    return { [__ENV.SUMMARY || '/out/summary.json']: JSON.stringify(data) };
}
