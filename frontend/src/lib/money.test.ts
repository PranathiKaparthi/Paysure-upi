import { describe, expect, it } from 'vitest'
import { formatPaise, parseRupeesToPaise } from './money'

describe('formatPaise', () => {
  it('formats paise as Indian rupees with grouping', () => {
    expect(formatPaise(25050)).toBe('₹250.50')
    expect(formatPaise(1000000)).toBe('₹10,000.00')
    expect(formatPaise(1234567890)).toBe('₹1,23,45,678.90')
    expect(formatPaise(5)).toBe('₹0.05')
    expect(formatPaise(-150)).toBe('-₹1.50')
  })
})

describe('parseRupeesToPaise', () => {
  it('parses without floating point error', () => {
    expect(parseRupeesToPaise('250.50')).toBe(25050)
    expect(parseRupeesToPaise('250.5')).toBe(25050)
    expect(parseRupeesToPaise('0.29')).toBe(29) // 0.29 * 100 = 28.999999999999996 in floats
    expect(parseRupeesToPaise('1,250')).toBe(125000)
    expect(parseRupeesToPaise(' 10 ')).toBe(1000)
  })

  it.each(['', '0', '0.00', '-5', '1.234', 'abc', '1e3', '12.', '.5'])('rejects %s', (input) => {
    expect(parseRupeesToPaise(input)).toBeNull()
  })
})
