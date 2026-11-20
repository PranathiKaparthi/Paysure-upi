import { expect, test } from '@playwright/test'
import { DEMO_PASSWORD, signIn } from './helpers'

test.beforeEach(async ({ page }) => {
  await signIn(page, 'labadmin@example.com', DEMO_PASSWORD)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Reliability Lab' }).click()
})

test('lost acknowledgement: PENDING, no ledger posting, then reconciliation proves SUCCESS without a second transfer', async ({ page }) => {
  await page.getByLabel('Failure scenario').selectOption({ label: 'Lost acknowledgement' })
  await page.getByRole('button', { name: 'Start simulation' }).click()

  const summary = page.getByRole('region', { name: 'Run summary' })
  await expect(summary).toContainText('PENDING')                                  // the outcome is unknown, not "failed"
  await expect(page.getByRole('region', { name: 'Ledger state' })).toContainText('No ledger posting')
  await expect(page.getByRole('region', { name: 'Simulated bank response' })).toContainText('Actually committed') // the bank DID debit
  await expect(page.getByRole('region', { name: 'Reconciliation result' })).toContainText('Not reconciled yet')

  await page.getByRole('button', { name: 'Run reconciliation', exact: true }).click()
  await expect(summary).toContainText('SUCCESS')
  const ledger = page.getByRole('region', { name: 'Ledger state' })
  await expect(ledger).toContainText('DEBIT')
  await expect(ledger).toContainText('CREDIT')
  await expect(page.getByRole('region', { name: 'What happened and why' })).toContainText('never a new transfer')
})

test('client retry storm: ten identical requests produce one logical payment', async ({ page }) => {
  await page.getByLabel('Failure scenario').selectOption({ label: 'Client retry storm' })
  await page.getByRole('button', { name: 'Start simulation' }).click()
  await expect(page.getByRole('region', { name: 'What happened and why' })).toContainText('10 identical requests produced 1 logical payment')
  await expect(page.getByRole('region', { name: 'Run summary' })).toContainText('SUCCESS')
})
