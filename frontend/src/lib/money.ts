/**
 * Money helpers. The backend speaks integral paise only; the UI never does arithmetic on
 * floating-point rupees. Parsing is string-based so "0.1 + 0.2"-style errors cannot occur.
 */

const PAISE_PER_RUPEE = 100n

/** Formats integral paise as Indian rupees, e.g. 1000000 -> "₹10,000.00". */
export function formatPaise(paise: number | bigint): string {
  const value = BigInt(paise)
  const negative = value < 0n
  const abs = negative ? -value : value
  const rupees = abs / PAISE_PER_RUPEE
  const fraction = (abs % PAISE_PER_RUPEE).toString().padStart(2, '0')
  const grouped = new Intl.NumberFormat('en-IN').format(rupees)
  return `${negative ? '-' : ''}₹${grouped}.${fraction}`
}

/**
 * Parses a user-typed rupee amount ("250", "250.5", "250.50", "1,250.00") into integral paise.
 * Returns null for anything that is not a plain positive amount with at most two decimals.
 */
export function parseRupeesToPaise(input: string): number | null {
  const cleaned = input.trim().replace(/,/g, '')
  const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(cleaned)
  if (!match) return null
  const rupees = BigInt(match[1])
  const fraction = BigInt((match[2] ?? '').padEnd(2, '0'))
  const paise = rupees * PAISE_PER_RUPEE + fraction
  if (paise <= 0n) return null
  return Number(paise)
}
