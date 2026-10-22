import { test, expect, type Page } from '@playwright/test'

type Options = { stock?: number; role?: 'ADMIN' | 'STAFF'; archived?: boolean; conflictOnce?: boolean; abortAfterCommit?: boolean; readsFailAfterWrite?: boolean; historyCount?: number; holdWrite?: Promise<void> }

async function setup(page: Page, options: Options = {}) {
  const user = { id: 'inventory-admin', email: 'inventory@example.test', role: options.role ?? 'ADMIN', active: true }
  const product = { id: 'inventory-product', sku: 'INV-001', name: 'Inventory Product', unitPrice: '100.00', stockQuantity: options.stock ?? 0, lowStockThreshold: 5, active: !options.archived, supplierId: null, description: null, createdAt: '2026-01-01T00:00:00Z' }
  const history = Array.from({ length: options.historyCount ?? 0 }, (_, i) => ({ id: `history-${i}`, productId: product.id, type: 'RECEIPT', quantityDelta: 1, reason: null as string | null, reference: `HISTORY-${i}`, orderId: null, createdBy: user.id, createdAt: new Date(Date.UTC(2026, 0, 1, 0, 0, i)).toISOString() }))
  let posts = 0
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { accessToken: 'mock-token', expiresIn: 900, user } })
    if (path.endsWith('/auth/me')) return route.fulfill({ json: user })
    if (options.readsFailAfterWrite && posts > 0 && request.method() === 'GET' && path.startsWith('/api/v1/products/')) return route.abort('failed')
    if (path === '/api/v1/stock-movements' && request.method() === 'POST') {
      posts++
      if (options.holdWrite) await options.holdWrite
      if (options.conflictOnce && posts === 1) {
        product.stockQuantity = 1
        return route.fulfill({ status: 409, json: { code: 'INSUFFICIENT_STOCK', message: 'The adjustment would make stock negative. Review current availability.', fieldErrors: [] } })
      }
      const input = request.postDataJSON()
      product.stockQuantity += input.quantityDelta
      const movement = { id: `new-${posts}`, productId: product.id, type: input.type, quantityDelta: input.quantityDelta, reason: input.reason, reference: input.reference, orderId: null, createdBy: user.id, createdAt: new Date().toISOString() }
      history.push(movement)
      if (options.abortAfterCommit) return route.abort('failed')
      return route.fulfill({ status: 201, json: movement })
    }
    if (path.endsWith('/stock-movements')) {
      const pageIndex = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20)
      const sorted = [...history].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      if (url.searchParams.get('sort') !== 'createdAt,asc') sorted.reverse()
      return route.fulfill({ json: { content: sorted.slice(pageIndex * size, (pageIndex + 1) * size), page: pageIndex, size, totalElements: history.length, totalPages: Math.ceil(history.length / size) } })
    }
    if (path === '/api/v1/products') return route.fulfill({ json: { content: [product], page: 0, size: 20, totalElements: 1, totalPages: 1 } })
    if (path === '/api/v1/products/inventory-product') return route.fulfill({ json: product })
    if (path === '/api/v1/suppliers') return route.fulfill({ json: { content: [], page: 0, totalElements: 0, totalPages: 0 } })
    return route.fulfill({ status: 404, json: { message: 'Not found.', fieldErrors: [] } })
  })
  await page.goto('/login')
  await page.getByLabel('Email address').fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('link', { name: 'Open product catalogue' }).click()
  await page.getByRole('link', { name: 'Inventory Product' }).click()
  await expect(page.getByRole('heading', { name: 'Stock movement history' })).toBeVisible()
  return { posts: () => posts }
}

test('receipt updates stock and displays signed immutable history', async ({ page }) => {
  const api = await setup(page)
  await page.getByRole('button', { name: 'Receive stock', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Quantity received').fill('10')
  await dialog.getByLabel('Reference (optional)', { exact: true }).fill('DELIVERY-10')
  await expect(dialog.getByText(/Estimated resulting stock:/)).toContainText('10')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.locator('dl').getByText('10', { exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '+10', exact: true })).toBeVisible()
  await expect(page.getByText('Ref: DELIVERY-10')).toBeVisible()
  expect(api.posts()).toBe(1)
})

test('adjustments require a reason and display negative quantities', async ({ page }) => {
  const api = await setup(page, { stock: 9 })
  await page.getByRole('button', { name: 'Adjust stock', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Signed adjustment quantity').fill('-2')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  expect(api.posts()).toBe(0)
  await dialog.getByLabel('Reason (required)', { exact: true }).fill('Damaged items')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.locator('dl').getByText('7', { exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '-2', exact: true })).toBeVisible()
  await expect(page.getByText('Damaged items', { exact: true })).toBeVisible()
})

test('stock conflicts preserve inputs and refresh availability without retrying', async ({ page }) => {
  const api = await setup(page, { stock: 9, conflictOnce: true })
  await page.getByRole('button', { name: 'Adjust stock', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Signed adjustment quantity').fill('-2')
  await dialog.getByLabel('Reason (required)', { exact: true }).fill('Count correction')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog.getByRole('alert')).toContainText('make stock negative')
  await expect(dialog.getByLabel('Signed adjustment quantity')).toHaveValue('-2')
  await expect(dialog.getByLabel('Reason (required)', { exact: true })).toHaveValue('Count correction')
  await expect(dialog.getByText(/Current stock:/)).toContainText('1')
  expect(api.posts()).toBe(1)
  await dialog.getByLabel('Signed adjustment quantity').fill('-1')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog).toHaveCount(0)
  expect(api.posts()).toBe(2)
})

test('ambiguous committed write is not retried and history supports reconciliation', async ({ page }) => {
  const api = await setup(page, { abortAfterCommit: true })
  await page.getByRole('button', { name: 'Receive stock', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Quantity received').fill('3')
  await dialog.getByLabel('Reference (optional)', { exact: true }).fill('CHECK-THIS-REFERENCE')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog.getByRole('alert')).toContainText('result could not be confirmed')
  await expect(dialog.getByRole('button', { name: 'Record movement' })).toBeDisabled()
  expect(api.posts()).toBe(1)
  await dialog.getByRole('button', { name: 'Close and inspect history' }).click()
  await expect(page.getByText('Ref: CHECK-THIS-REFERENCE')).toBeVisible()
  await expect(page.locator('dl').getByText('3', { exact: true })).toBeVisible()
  expect(api.posts()).toBe(1)
})

for (const options of [{ role: 'STAFF' as const, stock: 1, historyCount: 1 }, { archived: true, stock: 1, historyCount: 1 }]) {
  test(`${options.role === 'STAFF' ? 'staff' : 'archived product'} can read history without manual stock controls`, async ({ page }) => {
    await setup(page, options)
    await expect(page.getByRole('cell', { name: '+1', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Receive stock', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Adjust stock', exact: true })).toHaveCount(0)
  })
}

test('movement history supports pagination and sorting', async ({ page }) => {
  await setup(page, { stock: 21, historyCount: 21 })
  await page.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(page.getByText('Ref: HISTORY-0', { exact: true })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: 'Page 2 of 2' })).toBeVisible()
  await page.getByLabel('History order', { exact: true }).selectOption('createdAt,asc')
  await expect(page.getByRole('status').filter({ hasText: 'Page 1 of 2' })).toBeVisible()
  await expect(page.getByText('Ref: HISTORY-0', { exact: true })).toBeVisible()
})

test('pending writes disable duplicate submission', async ({ page }) => {
  let release!: () => void
  const holdWrite = new Promise<void>(resolve => { release = resolve })
  const api = await setup(page, { holdWrite })
  await page.getByRole('button', { name: 'Receive stock', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Quantity received').fill('2')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog.getByRole('button', { name: 'Recording…', exact: true })).toBeDisabled()
  await expect.poll(api.posts).toBe(1)
  await dialog.locator('form').evaluate(form => (form as HTMLFormElement).requestSubmit())
  expect(api.posts()).toBe(1)
  release()
  await expect(dialog).toHaveCount(0)
  expect(api.posts()).toBe(1)
})

test('uncertain write instructions remain available when refresh requests also fail', async ({ page }) => {
  const api = await setup(page, { abortAfterCommit: true, readsFailAfterWrite: true })
  await page.getByRole('button', { name: 'Receive stock', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Quantity received').fill('2')
  await dialog.getByRole('button', { name: 'Record movement' }).click()
  await expect(dialog.getByRole('alert')).toContainText('result could not be confirmed')
  await expect(dialog.getByRole('button', { name: 'Record movement' })).toBeDisabled()
  await expect(dialog.getByLabel('Quantity received')).toHaveValue('2')
  await dialog.getByRole('button', { name: 'Close and inspect history' }).click()
  await expect(page.getByRole('button', { name: 'Retry history' })).toBeVisible()
  expect(api.posts()).toBe(1)
})
