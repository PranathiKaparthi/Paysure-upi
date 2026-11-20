#!/usr/bin/env python3
"""PaySure acceptance demo: executes acceptance criteria A-H against a RUNNING backend and prints PASS/FAIL per check.

    python scripts/acceptance_demo.py [base-url]        (default http://localhost:8080)

Standard library only. Every check uses freshly registered users so earlier runs cannot influence it. It only prints
what it actually observed; it exits non-zero if any check fails. (Criteria I = test suites, J = Docker reproducibility
are verified by running the test suites / docker compose, not by this script.)

Environment: PAYSURE_DEMO_PASSWORD (default Sandbox@123) for the seeded labadmin@example.com demo administrator.
Note: sign-in and registration are rate limited per IP (20 / minute by default); this script uses about 10.
"""
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
DEMO_PASSWORD = os.environ.get("PAYSURE_DEMO_PASSWORD", "Sandbox@123")
PASSWORD = "Passw0rd!x"
results = []


def call(method, path, body=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except ValueError:
            return e.code, raw.decode(errors="replace")


def must(status_body, *ok):
    status, body = status_body
    if status not in ok:
        raise RuntimeError(f"unexpected HTTP {status}: {body}")
    return body


def person(prefix):
    handle = (prefix + uuid.uuid4().hex)[:20].lower()
    email = handle + "@example.com"
    must(call("POST", "/api/auth/register", {"email": email, "password": PASSWORD, "fullName": prefix.title() + " Tester"}), 201)
    token = must(call("POST", "/api/auth/login", {"email": email, "password": PASSWORD}), 200)["accessToken"]
    acc = must(call("POST", "/api/accounts", {"displayName": "Savings", "vpaHandle": handle}, token), 201)
    return {"token": token, "account": acc["id"], "vpa": handle + "@paysure"}


def balance(p):
    return must(call("GET", f"/api/accounts/{p['account']}/balance", token=p["token"]), 200)["balancePaise"]


def pay(p, to_vpa, paise, key=None):
    key = key or "acc-" + uuid.uuid4().hex
    return call("POST", "/api/payments", {"payerVpa": p["vpa"], "payeeVpa": to_vpa, "amountPaise": paise, "note": "acceptance"},
                p["token"], {"Idempotency-Key": key})


def check(code, title, fn):
    try:
        detail = fn()
        results.append((code, title, True, detail or ""))
        print(f"PASS  {code}  {title}" + (f"  [{detail}]" if detail else ""))
    except Exception as e:  # noqa: BLE001 - report every failure, keep going
        results.append((code, title, False, str(e)))
        print(f"FAIL  {code}  {title}  -> {e}")


def expect(cond, msg):
    if not cond:
        raise AssertionError(msg)


def wait_for(fn, seconds, what):
    end = time.time() + seconds
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(1)
    raise AssertionError(f"timed out after {seconds}s waiting for {what}")


def main():
    status, health = call("GET", "/actuator/health")
    if status != 200:
        print(f"Backend not healthy at {BASE} (HTTP {status}). Start it first (docker compose up -d --build).")
        return 2
    print(f"PaySure acceptance demo against {BASE}\n")
    admin = must(call("POST", "/api/auth/login", {"email": "labadmin@example.com", "password": DEMO_PASSWORD}), 200)["accessToken"]
    scenarios = {s["title"]: s["code"] for s in must(call("GET", "/api/admin/lab/scenarios", token=admin), 200)}

    def lab(title, amount=50_000):
        run = must(call("POST", "/api/admin/lab/runs", {"scenario": scenarios[title], "amountPaise": amount}, admin), 200, 201)
        return run

    def lab_act(run, action):
        return must(call("POST", f"/api/admin/lab/runs/{run['id']}/actions", {"action": action, "repeat": 1}, admin), 200)

    alice, bob = person("alice"), person("bob")

    # ---- A: a normal transfer -------------------------------------------------------------------------------
    def a():
        before = (balance(alice), balance(bob))
        status, body = pay(alice, bob["vpa"], 25_000)
        expect(status in (200, 201), f"HTTP {status}")
        expect(body["status"] == "SUCCESS", f"status {body['status']}")
        after = (balance(alice), balance(bob))
        expect(after == (before[0] - 25_000, before[1] + 25_000), f"balances {before} -> {after}")
        return f"payer -{25_000} paise, payee +{25_000} paise, status SUCCESS"
    check("A", "normal transfer moves money exactly once", a)

    # ---- B: ten identical concurrent requests -> one transfer -------------------------------------------------
    def b():
        key = "acc-b-" + uuid.uuid4().hex
        before = balance(alice)
        out = []
        lock = threading.Lock()

        def worker():
            r = pay(alice, bob["vpa"], 10_000, key)
            with lock:
                out.append(r)
        threads = [threading.Thread(target=worker) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        codes = sorted(s for s, _ in out)
        expect(all(s in (200, 201) for s in codes), f"HTTP codes {codes}")
        ids = {body["id"] for _, body in out}
        expect(len(ids) == 1, f"{len(ids)} distinct payments created")
        expect(balance(alice) == before - 10_000, "payer charged more than once")
        return f"10 requests -> 1 payment ({codes.count(201)}x201, {codes.count(200)}x200 replays); charged once"
    check("B", "10 identical concurrent requests create one transfer", b)

    # ---- C/D: lost acknowledgement -> PENDING, then reconciliation -> SUCCESS without repeating ---------------
    state = {}

    def c():
        run = lab("Lost acknowledgement")
        expect(run["payment"]["status"] == "PENDING", f"status {run['payment']['status']}")
        expect(run["ledger"] == [], "ledger posted before the outcome was known")
        expect(run["now"]["payerReservedPaise"] > 0, "funds were not held while PENDING")
        expect(run["simulatedBank"]["committedStatus"] == "SUCCESS", "bank did not commit (scenario precondition)")
        state["run"] = run
        return "PENDING; funds held; nothing posted to the ledger; the bank had in fact committed"
    check("C", "lost acknowledgement leaves the payment PENDING (not failed, not double-charged)", c)

    def d():
        run = state["run"]
        expect(run["payment"]["status"] == "PENDING", "no PENDING run to reconcile (C failed)")
        done = lab_act(run, "RECONCILE")
        expect(done["payment"]["status"] == "SUCCESS", f"status {done['payment']['status']}")
        expect(sorted(l["direction"] for l in done["ledger"]) == ["CREDIT", "DEBIT"], f"ledger {done['ledger']}")
        expect(done["simulatedBank"]["committedStatus"] == "SUCCESS", "bank state changed")
        # Running recovery again must be a no-op.
        again = lab_act(done, "RECONCILE")
        expect(len(again["ledger"]) == 2 and again["payment"]["status"] == "SUCCESS", "second reconciliation changed the ledger")
        expect(done["now"]["payerBalancePaise"] == done["before"]["payerBalancePaise"] - done["amountPaise"], "payer not debited exactly once")
        return "reconciliation queried the bank (never re-sent): PENDING -> SUCCESS, one balanced journal, re-run is a no-op"
    check("D", "reconciliation resolves PENDING to SUCCESS without repeating the transfer", d)

    # ---- E: duplicate callbacks -> no extra posting ------------------------------------------------------------
    def e():
        run = lab("Duplicate callbacks")
        expect(run["payment"]["status"] in ("SUCCESS", "PENDING", "PROCESSING"), f"status {run['payment']['status']}")
        final = wait_for(lambda: (lambda r: r if r["payment"]["status"] == "SUCCESS" else None)(
            must(call("GET", f"/api/admin/lab/runs/{run['id']}", token=admin), 200)) or None, 60, "SUCCESS after callbacks") \
            if run["payment"]["status"] != "SUCCESS" else run
        expect(len(final["ledger"]) == 2, f"{len(final['ledger'])} ledger entries (expected 2)")
        expect(final["now"]["payerBalancePaise"] == final["before"]["payerBalancePaise"] - final["amountPaise"], "payer not debited exactly once")
        return f"{final['duplicateCallbacks']} duplicate callbacks -> still exactly 1 debit + 1 credit"
    check("E", "duplicate bank callbacks post to the ledger only once", e)

    # ---- F: failed webhook retried; delivered once at business level --------------------------------------------
    def f():
        cfg = must(call("GET", "/api/webhooks/config", token=bob["token"]), 200)
        expect(cfg.get("sandboxReceiverEnabled"), "sandbox receiver disabled (set PAYSURE_WEBHOOK_SANDBOX_RECEIVER=true)")
        created = must(call("POST", "/api/webhooks/endpoints",
                            {"url": cfg["sandboxReceiverUrl"] + "?fail=2", "eventTypes": ["payment.completed"]}, bob["token"]), 200, 201)
        expect(created["secret"].startswith("whsec_"), "no signing secret returned once")
        sender = person("carol")
        status, body = pay(sender, bob["vpa"], 5_000)
        expect(status in (200, 201) and body["status"] == "SUCCESS", f"payment {status} {body}")

        def delivered():
            ds = must(call("GET", "/api/webhooks/deliveries?limit=50", token=bob["token"]), 200)
            hit = [d for d in ds if d["eventType"] == "payment.completed" and d["status"] == "SUCCESS"]
            return hit[0] if hit else None
        d = wait_for(delivered, 90, "webhook delivery SUCCESS")
        expect(d["attempts"] == 3, f"{d['attempts']} attempts (expected 2 failures + 1 success)")
        detail = must(call("GET", f"/api/webhooks/deliveries/{d['id']}", token=bob["token"]), 200)
        codes = [a.get("statusCode") for a in detail["attempts"]]
        expect(codes == [500, 500, 200], f"attempt status codes {codes}")
        receipts = must(call("GET", "/api/admin/webhooks/receipts", token=admin), 200)
        mine = [r for r in receipts if r["eventId"] == d["eventId"]]
        expect(len(mine) == 1 and mine[0]["calls"] == 3 and mine[0].get("processedAt"), f"receipts {mine}")
        return "attempts 500, 500, 200 with backoff; the receiver was called 3x but processed the event exactly once"
    check("F", "a failing webhook endpoint is retried and the event is processed once", f)

    # ---- G: transparent risk explanation ------------------------------------------------------------------------
    def g():
        stranger = person("dave")
        a = must(call("POST", "/api/risk/assess", {"payerVpa": alice["vpa"], "payeeVpa": stranger["vpa"], "amountPaise": 6_000_000}, alice["token"]), 200)
        expect(a["score"] >= 40, f"score {a['score']}")
        codes = {r["code"] for r in a["rules"] if r.get("triggered", True)}
        expect({"NEW_RECIPIENT", "LARGE_AMOUNT"} <= codes, f"rules {codes}")
        expect(len(a["explanation"]) >= 3 and a["disclaimer"], "explanation or disclaimer missing")
        return f"score {a['score']}/100 ({a['level']}) from {sorted(codes)}; mode {a['mode']}; disclaimer present"
    check("G", "risk score is explained rule by rule (educational, ALERT by default)", g)

    # ---- H: dashboard shows real metrics ------------------------------------------------------------------------
    def h():
        s = must(call("GET", "/api/admin/monitoring/summary", token=admin), 200)
        expect(s["payments"]["total"] >= 4, f"payments.total {s['payments']['total']}")
        deps = {d["name"]: d["status"] for d in s["dependencies"]}
        expect(deps.get("PostgreSQL") == "UP", f"dependencies {deps}")
        with urllib.request.urlopen(BASE + "/actuator/prometheus", timeout=30) as r:
            text = r.read().decode()
        for family in ("duplicate_requests_prevented_total", "reconciliation_resolved_total", "webhook_deliveries_total"):
            expect(family in text, f"metric {family} missing from /actuator/prometheus")
        return f"{s['payments']['total']} payments in 24h, dependencies {deps}, Prometheus metrics exposed"
    check("H", "monitoring reports real data and exposes Prometheus metrics", h)

    failed = [r for r in results if not r[2]]
    print(f"\n{len(results) - len(failed)}/{len(results)} acceptance checks passed.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
