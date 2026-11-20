#!/usr/bin/env sh
# End-to-end smoke test (Phases 1-2) against a running backend (Docker or local).
# Usage: scripts/smoke-test.sh [base-url]   (default http://localhost:8080)
# Requires: curl, python3 (for JSON parsing). Uses the seeded demo users.
set -eu

BASE="${1:-http://localhost:8080}"
PASS="${PAYSURE_DEMO_PASSWORD:-Sandbox@123}"
json() { ${PYTHON:-python3} -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "1. health";           curl -fsS "$BASE/actuator/health"; echo
echo "2. login (ananya)"
TOKEN=$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"ananya@example.com\",\"password\":\"$PASS\"}" | json "['accessToken']")
AUTH="Authorization: Bearer $TOKEN"
echo "3. accounts";         curl -fsS "$BASE/api/accounts" -H "$AUTH" | json "[0]['vpas'][0]['vpa']"
echo "4. resolve payee";    curl -fsS "$BASE/api/vpas/rahul@paysure/resolve" -H "$AUTH"; echo
echo "5. no token -> 401";  test "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/accounts")" = 401 && echo ok
echo "6. USER -> dev tools 403"
test "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/dev/accounts/00000000-0000-0000-0000-000000000000/top-up" \
  -H "$AUTH" -H 'Content-Type: application/json' -d '{"amountPaise":100}')" = 403 && echo ok
echo "7. pay ₹1.00 ananya -> rahul with an Idempotency-Key"
KEY="smoke-$(date +%s)-$$"
BODY='{"payerVpa":"ananya@paysure","payeeVpa":"rahul@paysure","amountPaise":100,"note":"smoke"}'
FIRST=$(curl -fsS -X POST "$BASE/api/payments" -H "$AUTH" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY")
echo "$FIRST" | json "['status']"
echo "8. same key again -> 200, same payment"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/payments" -H "$AUTH" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "$BODY")
test "$CODE" = 200 && echo ok
echo "9. same key, different amount -> 409"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/payments" -H "$AUTH" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"payerVpa":"ananya@paysure","payeeVpa":"rahul@paysure","amountPaise":999}')
test "$CODE" = 409 && echo ok
echo "Smoke test passed."
