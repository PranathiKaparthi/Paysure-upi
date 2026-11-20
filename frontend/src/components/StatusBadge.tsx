const STYLES: Record<string, string> = {
  ACTIVE: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  SUCCESS: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  PENDING: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  PROCESSING: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  CREATED: 'bg-slate-100 text-slate-700 ring-slate-500/20',
  FAILED: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  INACTIVE: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  APPROVED: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  DECLINED: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  CANCELLED: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  RESOLVED: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  CONSISTENT: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  STILL_PENDING: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  UNKNOWN: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  ESCALATED: 'bg-orange-50 text-orange-800 ring-orange-600/30',
  CONFLICT: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  LEDGER_MISMATCH: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  COMPLETED: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  DELIVERING: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  RETRYING: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  DEAD: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  PUBLISHED: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  LOW: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  MEDIUM: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  HIGH: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  OPEN: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  ACKNOWLEDGED: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  DISMISSED: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  DEGRADED: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  UP: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  DOWN: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  EXPIRED: 'bg-slate-100 text-slate-500 ring-slate-500/20',
}

export function StatusBadge({ status }: { status: string }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset ${
        STYLES[status] ?? STYLES.CREATED
      }`}
    >
      {status}
    </span>
  )
}
