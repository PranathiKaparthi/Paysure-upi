import { ApiError } from '../api/client'

export function errorText(err: unknown): string {
  return err instanceof ApiError ? err.describe() : 'Something went wrong.'
}

export function formatTime(iso?: string): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'medium' })
}

/** A fresh Idempotency-Key for a new logical payment (never reused for a different payment). */
export function newIdempotencyKey(): string {
  return `web-${crypto.randomUUID()}`
}
