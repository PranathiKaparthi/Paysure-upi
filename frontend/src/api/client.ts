import type {
  Account,
  CollectRequest,
  MonitoringSeries,
  MonitoringSummary,
  RiskAlert,
  RiskAssessment,
  RiskConfig,
  CreatedWebhookEndpoint,
  OutboxStatus,
  WebhookConfig,
  WebhookDelivery,
  WebhookDeliveryDetail,
  WebhookEndpoint,
  WebhookReceipt,
  LabAction,
  LabRunListItem,
  LabScenario,
  LabView,
  ManualReviewCase,
  ReconItem,
  ReconReport,
  ReconRun,
  ReliabilityScore,
  ScoreSnapshot,
  LedgerEntry,
  Payment,
  PaymentDetail,
  PaymentRequest,
  PaymentStatus,
  Problem,
  ResolvedVpa,
  StatusCheck,
  TokenResponse,
  User,
  Vpa,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail)
    this.status = problem.status
    this.code = problem.code
    this.problem = problem
  }

  /** Human-readable message including field-level validation errors. */
  describe(): string {
    if (this.problem.errors?.length) {
      return this.problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ')
    }
    return this.message
  }
}

type TokenSupplier = () => string | null
type UnauthorizedHandler = () => void

let tokenSupplier: TokenSupplier = () => null
let onUnauthorized: UnauthorizedHandler = () => {}

/** Wired by AuthProvider so the client can attach the bearer token and react to expiry. */
export function configureClient(supplier: TokenSupplier, unauthorized: UnauthorizedHandler) {
  tokenSupplier = supplier
  onUnauthorized = unauthorized
}

const BASE = import.meta.env.VITE_API_BASE ?? ''

async function request<T>(method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>): Promise<T> {
  return (await requestWithHeaders<T>(method, path, body, extraHeaders)).data
}

async function requestWithHeaders<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<{ data: T; headers: Headers; status: number }> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders }
  const token = tokenSupplier()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: 'Cannot reach the PaySure backend.' })
  }

  if (response.status === 401 && token) onUnauthorized()

  const text = await response.text()
  const data: unknown = text ? JSON.parse(text) : undefined
  if (!response.ok) {
    const problem = (data ?? {}) as Partial<Problem>
    throw new ApiError({
      status: response.status,
      code: problem.code ?? `HTTP_${response.status}`,
      detail: problem.detail ?? response.statusText ?? 'Request failed',
      requestId: problem.requestId,
      errors: problem.errors,
    })
  }
  return { data: data as T, headers: response.headers, status: response.status }
}

export const api = {
  register: (email: string, password: string, fullName: string) =>
    request<User>('POST', '/api/auth/register', { email, password, fullName }),
  login: (email: string, password: string) => request<TokenResponse>('POST', '/api/auth/login', { email, password }),
  me: () => request<User>('GET', '/api/auth/me'),

  listAccounts: () => request<Account[]>('GET', '/api/accounts'),
  openAccount: (displayName: string, vpaHandle?: string) =>
    request<Account>('POST', '/api/accounts', { displayName, vpaHandle: vpaHandle || undefined }),
  ledger: (accountId: string, limit = 20) =>
    request<LedgerEntry[]>('GET', `/api/accounts/${encodeURIComponent(accountId)}/ledger?limit=${limit}`),
  linkVpa: (accountId: string, handle: string) =>
    request<Vpa>('POST', `/api/accounts/${encodeURIComponent(accountId)}/vpas`, { handle }),
  deactivateAccount: (accountId: string) =>
    request<Account>('POST', `/api/accounts/${encodeURIComponent(accountId)}/deactivate`),
  activateAccount: (accountId: string) =>
    request<Account>('POST', `/api/accounts/${encodeURIComponent(accountId)}/activate`),
  resolveVpa: (vpa: string) => request<ResolvedVpa>('GET', `/api/vpas/${encodeURIComponent(vpa)}/resolve`),

  /**
   * Sends a payment. The caller supplies the Idempotency-Key and must REUSE it when retrying the same
   * payment after a network error, so a retry can never create a second transfer.
   */
  createPayment: async (req: PaymentRequest, idempotencyKey: string) => {
    const res = await requestWithHeaders<Payment>('POST', '/api/payments', req, { 'Idempotency-Key': idempotencyKey })
    return { payment: res.data, replayed: res.headers.get('Idempotent-Replayed') === 'true' }
  },
  listPayments: (status?: PaymentStatus, limit = 100) =>
    request<Payment[]>('GET', `/api/payments?limit=${limit}${status ? `&status=${status}` : ''}`),
  payment: (id: string) => request<PaymentDetail>('GET', `/api/payments/${encodeURIComponent(id)}`),
  checkStatus: (id: string) => request<StatusCheck>('POST', `/api/payments/${encodeURIComponent(id)}/check-status`),

  createCollect: (body: { requesterVpa: string; payerVpa: string; amountPaise: number; note?: string; expiresInMinutes?: number }) =>
    request<CollectRequest>('POST', '/api/collect-requests', body),
  listCollect: (direction: 'incoming' | 'outgoing') =>
    request<CollectRequest[]>('GET', `/api/collect-requests?direction=${direction}`),
  approveCollect: (id: string) =>
    request<{ request: CollectRequest; payment: Payment }>('POST', `/api/collect-requests/${encodeURIComponent(id)}/approve`),
  declineCollect: (id: string) => request<CollectRequest>('POST', `/api/collect-requests/${encodeURIComponent(id)}/decline`),
  cancelCollect: (id: string) => request<CollectRequest>('POST', `/api/collect-requests/${encodeURIComponent(id)}/cancel`),

  labScenarios: () => request<LabScenario[]>('GET', '/api/admin/lab/scenarios'),
  labStart: (body: { scenario: string; amountPaise?: number; delaySeconds?: number; duplicateCallbacks?: number; payeeVpa?: string }) =>
    request<LabView>('POST', '/api/admin/lab/runs', body),
  labRuns: () => request<LabRunListItem[]>('GET', '/api/admin/lab/runs?limit=8'),
  labRun: (id: string) => request<LabView>('GET', `/api/admin/lab/runs/${encodeURIComponent(id)}`),
  labAct: (id: string, action: LabAction, repeat = 1) =>
    request<LabView>('POST', `/api/admin/lab/runs/${encodeURIComponent(id)}/actions`, { action, repeat }),

  reliability: (windowMinutes = 60) => request<ReliabilityScore>('GET', `/api/admin/reliability?windowMinutes=${windowMinutes}`),
  reliabilityHistory: () => request<ScoreSnapshot[]>('GET', '/api/admin/reliability/history?limit=60'),

  reconRun: () => request<ReconRun>('POST', '/api/admin/reconciliation/run?minAgeSeconds=0'),
  reconReport: (hours = 24) => request<ReconReport>('GET', `/api/admin/reconciliation/report?hours=${hours}`),
  reconRuns: () => request<ReconRun[]>('GET', '/api/admin/reconciliation/runs?limit=15'),
  reconItems: (runId: string) => request<ReconItem[]>('GET', `/api/admin/reconciliation/runs/${encodeURIComponent(runId)}/items`),
  reconManualReview: () => request<ManualReviewCase[]>('GET', '/api/admin/reconciliation/manual-review'),

  webhookConfig: () => request<WebhookConfig>('GET', '/api/webhooks/config'),
  webhookEndpoints: () => request<WebhookEndpoint[]>('GET', '/api/webhooks/endpoints'),
  createWebhookEndpoint: (body: { url: string; eventTypes?: string[]; description?: string }) =>
    request<CreatedWebhookEndpoint>('POST', '/api/webhooks/endpoints', body),
  setWebhookEnabled: (id: string, enabled: boolean) =>
    request<WebhookEndpoint>('POST', `/api/webhooks/endpoints/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`),
  rotateWebhookSecret: (id: string) =>
    request<CreatedWebhookEndpoint>('POST', `/api/webhooks/endpoints/${encodeURIComponent(id)}/rotate-secret`),
  webhookDeliveries: () => request<WebhookDelivery[]>('GET', '/api/webhooks/deliveries?limit=50'),
  webhookDelivery: (id: string) => request<WebhookDeliveryDetail>('GET', `/api/webhooks/deliveries/${encodeURIComponent(id)}`),
  retryWebhookDelivery: (id: string) =>
    request<WebhookDelivery>('POST', `/api/webhooks/deliveries/${encodeURIComponent(id)}/retry`),
  adminOutbox: () => request<OutboxStatus>('GET', '/api/admin/events/outbox'),
  adminWebhookReceipts: () => request<WebhookReceipt[]>('GET', '/api/admin/webhooks/receipts'),

  riskConfig: () => request<RiskConfig>('GET', '/api/risk/config'),
  riskAssess: (body: { payerVpa: string; payeeVpa: string; amountPaise: number }) =>
    request<RiskAssessment>('POST', '/api/risk/assess', body),
  riskAlerts: (paymentId?: string) =>
    request<RiskAlert[]>('GET', `/api/risk/alerts?limit=100${paymentId ? `&paymentId=${encodeURIComponent(paymentId)}` : ''}`),
  adminRiskAlerts: (status?: string) =>
    request<RiskAlert[]>('GET', `/api/admin/risk/alerts?limit=100${status ? `&status=${status}` : ''}`),
  reviewRiskAlert: (id: string, status: 'ACKNOWLEDGED' | 'DISMISSED') =>
    request<RiskAlert>('POST', `/api/admin/risk/alerts/${encodeURIComponent(id)}/review`, { status }),
  monitoringSummary: () => request<MonitoringSummary>('GET', '/api/admin/monitoring/summary'),
  monitoringSeries: () => request<MonitoringSeries>('GET', '/api/admin/monitoring/series'),

  devBankTick: (skipAheadSeconds = 0) =>
    request<{ transfersResolved: number; callbacksDelivered: number; callbacksFailed: number }>(
      'POST',
      `/api/dev/bank/tick?skipAheadSeconds=${skipAheadSeconds}`,
    ),

  devTopUp: (accountId: string, amountPaise: number, reason?: string) =>
    request<Account>('POST', `/api/dev/accounts/${encodeURIComponent(accountId)}/top-up`, { amountPaise, reason }),
}
