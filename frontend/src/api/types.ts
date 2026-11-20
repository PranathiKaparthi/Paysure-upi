export type Role = 'USER' | 'ADMIN_DEMO'
export type ActiveStatus = 'ACTIVE' | 'INACTIVE'

export interface User {
  id: string
  email: string
  fullName: string
  role: Role
  createdAt: string
}

export interface TokenResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: User
}

export interface Vpa {
  id: string
  vpa: string
  accountId: string
  status: ActiveStatus
  createdAt: string
}

export interface Account {
  id: string
  displayName: string
  maskedAccountNumber: string
  ifsc: string
  currency: 'INR'
  status: ActiveStatus
  balancePaise: number
  reservedPaise: number
  availablePaise: number
  vpas: Vpa[]
  createdAt: string
}

export interface LedgerEntry {
  id: string
  journalId: string
  direction: 'DEBIT' | 'CREDIT'
  amountPaise: number
  balanceAfterPaise: number
  createdAt: string
}

export interface ResolvedVpa {
  vpa: string
  payeeName: string
  ifsc: string
}

/** RFC 7807 problem body returned by every backend error. */
export interface Problem {
  status: number
  code: string
  detail: string
  requestId?: string
  errors?: { field: string; message: string }[]
}

export type PaymentStatus = 'CREATED' | 'PROCESSING' | 'PENDING' | 'SUCCESS' | 'FAILED'
export type HoldStatus = 'NONE' | 'HELD' | 'CAPTURED' | 'RELEASED'
export type Scenario =
  | 'NORMAL_SUCCESS'
  | 'INSUFFICIENT_FUNDS'
  | 'BANK_TIMEOUT'
  | 'TIMEOUT_AFTER_COMMIT'
  | 'BANK_UNAVAILABLE'
  | 'NETWORK_FAILURE'
  | 'DELAYED_SUCCESS'
  | 'DELAYED_FAILURE'
  | 'DUPLICATE_CALLBACK'
  | 'OUT_OF_ORDER_CALLBACK'

export interface Simulation {
  scenario: Scenario
  delaySeconds?: number
  duplicateCallbacks?: number
}

export interface PaymentRequest {
  payerVpa: string
  payeeVpa: string
  amountPaise: number
  note?: string
  simulation?: Simulation
}

export interface Payment {
  id: string
  status: PaymentStatus
  statusMessage: string
  direction?: 'SENT' | 'RECEIVED'
  payerVpa: string
  payeeVpa: string
  amountPaise: number
  note?: string
  holdStatus: HoldStatus
  failureCode?: string
  failureReason?: string
  bankReference?: string
  collectRequestId?: string
  createdAt: string
  updatedAt: string
  completedAt?: string
}

export interface PaymentHistoryItem {
  fromStatus?: PaymentStatus
  toStatus: PaymentStatus
  source: 'API' | 'BANK_RESPONSE' | 'BANK_CALLBACK' | 'STATUS_CHECK' | 'RECONCILIATION'
  reason: string
  at: string
}

export interface PaymentAttempt {
  attemptNo: number
  bankReference: string
  requestedScenario?: Scenario
  responseKind?: 'SUCCESS' | 'DECLINED' | 'UNAVAILABLE' | 'ACCEPTED' | 'TIMEOUT' | 'NETWORK_ERROR'
  submittedAt?: string
  responseAt?: string
  callbacksReceived: number
  callbacksIgnored: number
  lastCallbackSeq: number
}

export interface BankSideView {
  bankReference: string
  scenario: Scenario
  committedStatus: 'PROCESSING' | 'SUCCESS' | 'FAILED'
  responseDelivered: string
  failureReason?: string
  amountPaise: number
  resolvesAt?: string
  committedAt?: string
  createdAt: string
  callbacks: {
    id: string
    sequence: number
    reportedStatus: string
    deliverAt: string
    deliveredAt?: string
    deliveryAttempts: number
  }[]
}

export interface PaymentDetail {
  payment: Payment
  history: PaymentHistoryItem[]
  attempts: PaymentAttempt[]
  simulatedBank?: BankSideView
}

export interface StatusCheck {
  effect: string
  bankStatus?: { bankReference: string; status: string; reason?: string }
  payment: Payment
}

export type CollectStatus = 'PENDING' | 'APPROVED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED'

export interface CollectRequest {
  id: string
  status: CollectStatus
  myRole: 'REQUESTER' | 'PAYER'
  requesterVpa: string
  payerVpa: string
  amountPaise: number
  note?: string
  paymentId?: string
  expiresAt: string
  decidedAt?: string
  createdAt: string
}

// ---- Phase 3: Reliability Lab, reconciliation, reliability score --------------------------------------

export interface LabScenario {
  code: string
  title: string
  category: string
  bankScenario: Scenario
  defaultDelaySeconds: number
  defaultDuplicates: number
  defaultRequests: number
  statusApiDown: boolean
  whatHappens: string
  expectedOutcome: string
  recovery: string
}

export interface LabBalances {
  payerBalancePaise: number
  payerReservedPaise: number
  payeeBalancePaise: number
}

export interface TimelineEvent {
  at: string
  actor: 'CLIENT' | 'PAYSURE' | 'BANK' | 'RECONCILER'
  title: string
  detail?: string
}

export type ReconOutcome =
  | 'RESOLVED'
  | 'STILL_PENDING'
  | 'UNKNOWN'
  | 'ESCALATED'
  | 'CONFLICT'
  | 'CONSISTENT'
  | 'LEDGER_MISMATCH'

export interface ReconItem {
  id: string
  runId: string
  paymentId?: string
  accountId?: string
  kind: 'IN_FLIGHT' | 'POST_SETTLEMENT' | 'LEDGER'
  statusBefore?: string
  statusAfter?: string
  bankStatus?: string
  outcome: ReconOutcome
  detail: string
  recoveryMs?: number
  createdAt: string
}

export type LabAction = 'RECONCILE' | 'FAST_FORWARD' | 'RESTORE_STATUS_API'

export interface LabView {
  id: string
  scenario: LabScenario
  createdAt: string
  amountPaise: number
  delaySeconds: number
  duplicateCallbacks: number
  requestsSent: number
  distinctPayments: number
  payerVpa: string
  payeeVpa: string
  before: LabBalances
  afterSubmit: LabBalances
  now: LabBalances
  statusAfterSubmit: PaymentStatus
  payment: {
    id: string
    status: PaymentStatus
    holdStatus: HoldStatus
    failureCode?: string
    amountPaise: number
    bankReference?: string
    needsManualReview: boolean
    manualReviewReason?: string
  }
  simulatedBank?: BankSideView
  statusApiUp: boolean
  ledger: { direction: 'DEBIT' | 'CREDIT'; account: 'PAYER' | 'PAYEE'; amountPaise: number }[]
  timeline: TimelineEvent[]
  reconciliation: ReconItem[]
  scoreBefore?: number
  scoreNow?: number
  explanation: string[]
  availableActions: LabAction[]
  finished: boolean
}

export interface LabRunListItem {
  id: string
  scenarioCode: string
  scenarioTitle: string
  createdAt: string
  paymentId?: string
  paymentStatus?: PaymentStatus
  amountPaise: number
}

export interface ScoreComponent {
  key: string
  label: string
  weight: number
  available: boolean
  value?: number
  valueText: string
  score?: number
  explanation: string
}

export interface ReliabilityScore {
  windowStart: string
  computedAt: string
  score?: number
  components: ScoreComponent[]
  methodology: string
  disclaimer: string
}

export interface ScoreSnapshot {
  id: string
  capturedAt: string
  trigger: string
  windowMinutes: number
  score: number
}

export interface ReconRun {
  id: string
  trigger: 'SCHEDULED' | 'MANUAL' | 'LAB'
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  startedAt: string
  finishedAt?: string
  durationMs?: number
  checked: number
  resolved: number
  resolvedSuccess: number
  resolvedFailed: number
  stillPending: number
  unknown: number
  conflicts: number
  ledgerInconsistencies: number
  escalated: number
  postSettlementChecked: number
  maxRecoveryMs?: number
  avgRecoveryMs?: number
  error?: string
}

export interface ReconReport {
  since: string
  hours: number
  runs: number
  checked: number
  resolved: number
  resolvedSuccess: number
  resolvedFailed: number
  unresolvedUnknown: number
  escalated: number
  conflictingPayments: number
  ledgerInconsistencies: number
  postSettlementChecked: number
  avgRecoveryMs?: number
  maxRecoveryMs?: number
  stillPendingNow: number
  manualReviewCases: number
}

export interface ManualReviewCase {
  paymentId: string
  status: string
  payerVpa: string
  payeeVpa: string
  amountPaise: number
  reason: string
  reconAttempts: number
  createdAt: string
  lastReconciledAt?: string
}

// ---- Phase 4: webhooks and the event pipeline ----------------------------------------------------------

export type DeliveryStatus = 'PENDING' | 'DELIVERING' | 'RETRYING' | 'SUCCESS' | 'DEAD'

export interface WebhookEndpoint {
  id: string
  url: string
  eventTypes: string[]
  description?: string
  enabled: boolean
  secretVersion: number
  createdAt: string
}

/** The signing secret is returned ONLY when an endpoint is created or its secret rotated. */
export interface CreatedWebhookEndpoint {
  endpoint: WebhookEndpoint
  secret: string
}

export interface WebhookConfig {
  eventTypes: string[]
  sandboxReceiverEnabled: boolean
  sandboxReceiverUrl: string
  signature: string
  replayToleranceSeconds: number
  maxAttempts: number
}

export interface WebhookDelivery {
  id: string
  endpointId: string
  endpointUrl: string
  eventId: string
  eventType: string
  status: DeliveryStatus
  attempts: number
  maxAttempts: number
  nextAttemptAt: string
  lastStatusCode?: number
  lastError?: string
  lastAttemptAt?: string
  deliveredAt?: string
  createdAt: string
}

export interface WebhookDeliveryDetail {
  delivery: WebhookDelivery
  payload: string
  attempts: { attemptNo: number; startedAt: string; durationMs: number; statusCode?: number; error?: string; outcome: string }[]
}

export interface OutboxStatus {
  pending: number
  published: number
  dead: number
  inbox: number
  recent: {
    seq: number
    id: string
    eventType: string
    topic: string
    status: 'PENDING' | 'PUBLISHED' | 'DEAD'
    attempts: number
    lastError?: string
    createdAt: string
    publishedAt?: string
  }[]
}

export interface WebhookReceipt {
  endpointId: string
  eventId: string
  eventType: string
  calls: number
  firstSeen: string
  processedAt?: string
  lastResult: string
}

// ---- Phase 5: risk (educational simulation) and monitoring ---------------------------------------------

export interface RiskRule {
  code: string
  label: string
  points: number
  detail: string
}

export interface RiskAssessment {
  score: number
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  action: 'ALLOW' | 'REVIEW' | 'BLOCK'
  rules: RiskRule[]
  explanation: string[]
  suggestedAction: string
  wouldAlert: boolean
  wouldBlock: boolean
  mode: 'ALERT' | 'ENFORCE'
  disclaimer: string
}

export interface RiskAlert {
  id: string
  userId: string
  paymentId?: string
  payerVpa: string
  payeeVpa: string
  amountPaise: number
  score: number
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  action: 'ALLOW' | 'REVIEW' | 'BLOCK'
  blocked: boolean
  rules: RiskRule[]
  explanation: string[]
  suggestedAction: string
  status: 'OPEN' | 'ACKNOWLEDGED' | 'DISMISSED'
  createdAt: string
  reviewedAt?: string
}

export interface RiskConfig {
  enabled: boolean
  mode: 'ALERT' | 'ENFORCE'
  alertScore: number
  highScore: number
  blockScore: number
  rules: { code: string; points: number; threshold?: number; windowSeconds?: number; thresholdPaise?: number }[]
  disclaimer: string
}

export interface MonitoringSummary {
  generatedAt: string
  payments: {
    windowHours: number
    total: number
    success: number
    failed: number
    unfinished: number
    successRate?: number
    p50Ms?: number
    p95Ms?: number
    pendingNow: number
    oldestPendingSeconds: number
  }
  countersSinceProcessStart: Record<string, number>
  pipeline: {
    outboxPending: number
    outboxDead: number
    oldestPendingEventSeconds: number
    lastPublishedAt?: string
    webhookDeliveriesOpen: number
    webhookDeliveriesDead: number
  }
  risk: { open: number; high24h: number; medium24h: number; blocked24h: number }
  dependencies: { name: string; status: 'UP' | 'DEGRADED' | 'DOWN'; detail: string }[]
}

export interface MonitoringSeries {
  payments: { t: string; created: number; success: number; failed: number; unfinished: number }[]
  webhooks: { t: string; delivered: number; failed: number }[]
  reconciliation: { t: string; resolved: number; unresolved: number; discrepancies: number }[]
}
