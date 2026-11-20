import { expect, test } from '@playwright/test'
import { DEMO_PASSWORD, PASSWORD, newPerson, signIn } from './helpers'

test('the monitoring page shows dependency health and charts to demo admins', async ({ page }) => {
  await signIn(page, 'labadmin@example.com', DEMO_PASSWORD)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Monitoring' }).click()
  await expect(page.getByRole('heading', { name: 'System monitoring' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Dependencies' })).toContainText('PostgreSQL')
  await expect(page.getByRole('region', { name: 'Payments, last 24 hours' })).toContainText('Success rate')
  await expect(page.getByRole('region', { name: 'Payments chart' }).locator('svg').first()).toBeVisible()
})

test('ordinary users cannot open the monitoring page', async ({ page, request }) => {
  const user = await newPerson(request, 'nomon')
  await signIn(page, user.email, PASSWORD)
  await page.goto('/monitoring')
  await expect(page.getByText(/for demo administrators/)).toBeVisible()
  await expect(page.getByRole('heading', { name: 'System monitoring' })).toHaveCount(0)
})
