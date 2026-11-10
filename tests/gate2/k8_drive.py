"""One user's requests for tests/gate2/native.sh k8 (wiki/beat-php.md), from a container on the client's cores in
the servers' network, with the host's cgroups (-v /sys/fs/cgroup:/hostcg:ro) for the server's CPU time.

Per page two blocks, one request after another on one connection: PLAIN requests of the page as a visitor gets it
(the response time as the client sees it), then REQUESTS requests with k8=N (tests/gate2/k8-plugin.php: the
request's phases and N renders of the page inside it). Per block the server's container's CPU seconds.

OUT: {"blocks": [{"page", "kind": "plain"|"k8", "seconds", "cpu_s", "requests": [{"wall_ms", "status", "bytes",
and for k8 the plugin's "phases", "iterations", "peak", "queries"}]}], "peak_mb": the cgroup's memory.peak}

K8_EXTRA (environment): more of the k8 requests' query string, e.g. "&k8prof=200" (the plugin's Excimer profile).

Usage: python k8_drive.py HOST:PORT ID_FILE OUT PLAIN REQUESTS N PAGE...
"""
import http.client
import json
import os
import sys
import time


def cpu(cgroup):
    for line in open(cgroup + "/cpu.stat"):
        name, value = line.split()
        if name == "usage_usec":
            return int(value) / 1e6
    raise ValueError("no usage_usec")


class Client:
    def __init__(self, address):
        self.address = address
        self.connection = None

    def get(self, path):
        """The response's time in ms, its status and body; a connection the server closed is opened again."""
        for attempt in (1, 2):
            if self.connection is None:
                self.connection = http.client.HTTPConnection(self.address, timeout=600)
            start = time.perf_counter()
            try:
                self.connection.request("GET", path, headers={"Host": "localhost"})
                response = self.connection.getresponse()
                body = response.read()
                return (time.perf_counter() - start) * 1000, response.status, body
            except (http.client.HTTPException, OSError):
                self.connection.close()
                self.connection = None
                if attempt == 2:
                    raise


def main():
    address, id_file, out = sys.argv[1:4]
    plain, requests, n = (int(a) for a in sys.argv[4:7])
    pages = sys.argv[7:]
    extra = os.environ.get("K8_EXTRA", "")
    cgroup = "/hostcg/docker/" + open(id_file).read().split()[0]
    client = Client(address)
    blocks = []
    for page in pages:
        for kind, count in (("plain", plain), ("k8", requests)):
            if count == 0:
                continue
            path = page if kind == "plain" else page + ("&" if "?" in page else "?") + f"k8={n}" + extra
            rows = []
            cpu_start, start = cpu(cgroup), time.time()
            for _ in range(count):
                wall, status, body = client.get(path)
                row = {"wall_ms": round(wall, 3), "status": status, "bytes": len(body)}
                if kind == "k8":
                    try:
                        row.update(json.loads(body))
                    except ValueError:
                        row["error"] = body[:300].decode("utf-8", "replace")
                rows.append(row)
            blocks.append({"page": page, "kind": kind, "seconds": round(time.time() - start, 3),
                           "cpu_s": round(cpu(cgroup) - cpu_start, 3), "requests": rows})
            print(f"{page} {kind}: {count} requests in {blocks[-1]['seconds']:.1f} s, CPU {blocks[-1]['cpu_s']:.1f} s",
                  flush=True)
    peak = int(open(cgroup + "/memory.peak").read()) / 2**20
    json.dump({"blocks": blocks, "peak_mb": round(peak, 1), "n": n}, open(out, "w"))


if __name__ == "__main__":
    main()
