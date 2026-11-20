# Redis: rate limiting and the payment-status cache (Phase 4)

PostgreSQL remains the **only** system of record for balances, payment status and idempotency keys. Redis holds disposable copies and counters.
If Redis disappears, payments keep working.

## Rate limiting

Fixed-window counters, enforced by `RateLimitInterceptor` after authentication (so the user id comes from the verified JWT):

| Policy | Applies to | Default |
|---|---|---|
| `payments-user` | `POST /api/payments`, per user | 30 / 60 s |
| `payments-ip` | `POST /api/payments`, per client IP | 120 / 60 s |
| `collect-user` | `POST /api/collect-requests`, per user | 30 / 60 s |
| `auth-ip` | `POST /api/auth/login` and `/register`, per client IP | 20 / 60 s |

A rejected request gets **429**, `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` and an RFC 7807 body (`code: RATE_LIMITED`).
All limits are configurable (`paysure.rate-limit.*`).

**Atomicity.** One Lua script does `INCR`, sets `PEXPIRE` on the first hit (and repairs a counter that somehow has no TTL) and reads `PTTL`:

```lua
local c = redis.call('INCR', KEYS[1])
if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
return {c, ttl}
```

so 40 concurrent callers against a limit of 5 admit exactly 5 and no key is ever left without an expiry (both tested on a real Redis).
Trade-off: a fixed window allows up to 2× the limit across a window boundary.

**Client IP.** `X-Forwarded-For` is ignored unless `paysure.rate-limit.trust-forwarded-for=true` (otherwise any client could forge its
address). The bundled nginx *overwrites* the header with the real peer address; enable trust only when clients reach the backend solely through
such a proxy.

## Payment-status cache

`GET /api/payments/{id}/status` is a cheap polling endpoint (`X-Cache: HIT|MISS`) backed by a read-through cache:

- hit → return the cached copy; miss (or Redis down) → read PostgreSQL and store it;
- **authorisation is re-checked on every hit** (the cached entry carries the payer/payee ids; strangers get 404);
- invalidated **after the transaction that changed the payment commits** (`afterCommit`), so a reader never sees a cached status that is older
  than a committed change, except in the race below;
- TTL: 30 s for unfinished payments, 5 min for final ones (which cannot change);
- a corrupt entry is dropped and reloaded from PostgreSQL.

*Known race:* a reader may load the old row, lose a race with a writer's commit-and-invalidate, then store the stale value. The 30 s TTL bounds
the staleness (final states cannot be stale). The detailed `GET /api/payments/{id}` always reads PostgreSQL.

## Behaviour when Redis is unavailable (tested with a real redis-server stopped and restarted)

- `RedisGuard` is a small circuit breaker: after an error Redis is skipped for 5 s so requests do not each wait for a connection timeout
  (Lettuce is configured to reject commands while disconnected, 300 ms connect timeout).
- Rate limiting falls back to a **per-instance in-memory** window (`degraded = true`): limits still apply, but are per instance.
- The status endpoint reads PostgreSQL (`X-Cache: MISS`).
- The `redis` actuator health indicator is disabled on purpose: an optional dependency must not fail readiness.
- After Redis returns and the cooldown passes, Redis is used again automatically. Metric: `redis_errors_total`.

Not implemented: the optional "read-side metrics cache" (the reliability score is computed on demand).
