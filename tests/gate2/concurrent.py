"""N concurrent clients, each repeating Gate 2's visit (visits.py's pages, a cookie jar per visit) for SECONDS.

For measuring what requests in flight keep alive (wiki/gc.md, with the server's -Dphp.heapprobe): prints one line
per finished visit and a summary. Runs where visits.py runs (tests/gate2/native.sh's driver container).
Usage: python concurrent.py URL CLIENTS SECONDS
"""
import http.cookiejar
import sys
import threading
import time
import urllib.request

sys.path.insert(0, "/t")
from visits import PAGES, NoRedirect, fetch  # noqa: E402


def client(url, until, done, failures):
    while time.time() < until:
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                             NoRedirect)
        start = time.perf_counter()
        ok = True
        for _, path, form, expected in PAGES:
            status, _, _ = fetch(opener, url + path, form)
            ok = ok and status == expected
        done.append(time.perf_counter() - start)
        if not ok:
            failures.append(1)


def main():
    url, clients, seconds = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
    until = time.time() + seconds
    done, failures = [], []
    threads = [threading.Thread(target=client, args=(url, until, done, failures)) for _ in range(clients)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    done.sort()
    print(f"{clients} clients: {len(done)} visits, {len(failures)} failed, median {done[len(done) // 2]:.3f} s"
          if done else "no visits")


if __name__ == "__main__":
    main()
