// Starts the full backend WITHOUT Docker for local E2E runs: embedded PostgreSQL, in-process Kafka, real redis-server.
// (See backend/src/test/java/com/paysure/TestPaySureApplication.java.) Playwright waits for /actuator/health.
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const backend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'backend')
const wrapper = path.join(backend, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
// One browser/IP signs in and registers many throw-away users per minute; relax ONLY the per-IP auth limit for E2E.
const env = { ...process.env, PAYSURE_RATE_LIMIT_AUTH_PER_IP_LIMIT: process.env.PAYSURE_RATE_LIMIT_AUTH_PER_IP_LIMIT ?? '1000' }
const child = spawn(`"${wrapper}" -B -q spring-boot:test-run`, { cwd: backend, stdio: 'inherit', shell: true, env })
const stop = () => child.kill()
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
child.on('exit', (code) => process.exit(code ?? 0))
