#!/usr/bin/env python3
"""Checks that every metric used by the Grafana dashboard exists in a live Prometheus scrape of the backend.

Usage: python scripts/check-dashboard-metrics.py [scrape-url] [dashboard.json]
Default scrape URL: http://localhost:8080/actuator/prometheus

Exit code 0 = all metrics found; 1 = something the dashboard queries is not exposed (the panel would be empty).
"""
import json
import re
import sys
import urllib.request

url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/actuator/prometheus"
path = sys.argv[2] if len(sys.argv) > 2 else "monitoring/grafana/dashboards/paysure.json"

scrape = urllib.request.urlopen(url, timeout=10).read().decode()
exposed = {m.group(1) for line in scrape.splitlines() if (m := re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)[{ ]", line))}

PROMQL_WORDS = {"histogram_quantile", "label_replace", "label_join"}
dashboard = json.load(open(path, encoding="utf-8"))
missing = []
used = set()
for panel in dashboard["panels"]:
    for target in panel.get("targets", []):
        expr = re.sub(r'"[^"]*"', '""', target["expr"])          # drop label values
        for token in re.findall(r"[A-Za-z_:][A-Za-z0-9_:]*", expr):
            if "_" in token and token not in PROMQL_WORDS:
                used.add(token)
                if token not in exposed:
                    missing.append((panel["title"], token))

print(f"{len(used)} distinct metrics used by {len([p for p in dashboard['panels'] if p['type'] != 'row'])} panels; {len(exposed)} exposed by the backend")
for title, token in missing:
    print(f"MISSING  {token}   (panel: {title})")
if missing:
    sys.exit(1)
print("OK: every metric the dashboard queries is exposed.")
