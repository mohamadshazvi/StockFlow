import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, role = 'ADMIN', allowSelfDemotion = false) {
  const owner = { id: 'owner', email: 'owner@example.test', role, active: true }
  const accounts = [owner]
  const supplier = { id: 'supplier-1', name: 'Demo Supplier', email: 'contact@example.test', phone: '123', address: 'Demo address', active: true }
  const suppliers = [supplier]
  const product = { id: 'product-1', sku: 'DEMO-001', name: 'Demo Product', description: 'Original', unitPrice: '100.00', stockQuantity: 9, lowStockThreshold: 5, supplierId: null as string | null, active: true, createdAt: '2026-01-01T00:00:00Z' }
  function result(content: unknown[]) { return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 } }
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { user: owner, accessToken: 'mock-token', expiresIn: 900 } })
    if (path.endsWith('/auth/me')) return route.fulfill({ json: owner })
    if (path.endsWith('/stock-movements')) return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    if (path === '/api/v1/suppliers') {
      if (method === 'POST') { const saved = { ...supplier, ...request.postDataJSON(), id: 'new-supplier' }; suppliers.push(saved); return route.fulfill({ status: 201, json: saved }) }
      return route.fulfill({ json: result(suppliers.filter(s => url.searchParams.get('active') === 'all' || s.active === (url.searchParams.get('active') !== 'false'))) })
    }
    if (path.startsWith('/api/v1/suppliers/')) {
      const saved = suppliers.find(s => path.endsWith(`/${s.id}`))
      if (saved) { if (method === 'PATCH') Object.assign(saved, request.postDataJSON()); return route.fulfill({ json: saved }) }
    }
    if (path === '/api/v1/products') return route.fulfill({ json: result(url.searchParams.has('supplierId') && product.supplierId !== url.searchParams.get('supplierId') ? [] : [product]) })
    if (path === '/api/v1/products/product-1') {
      if (method === 'PATCH') Object.assign(product, request.postDataJSON())
      return route.fulfill({ json: product })
    }
    if (path === '/api/v1/users') {
      if (method === 'POST') { const input = request.postDataJSON(); const saved = { id: 'new-user', email: input.email, role: input.role, active: true }; accounts.push(saved); return route.fulfill({ status: 201, json: saved }) }
      return route.fulfill({ json: result(accounts) })
    }
    if (path.startsWith('/api/v1/users/')) {
      const input = request.postDataJSON(), saved = accounts.find(u => path.endsWith(`/${u.id}`))
      if (saved?.id === owner.id && !allowSelfDemotion && (input.role !== 'ADMIN' || !input.active)) return route.fulfill({ status: 409, json: { code: 'LAST_ADMIN_REQUIRED', message: 'At least one active administrator must remain.', fieldErrors: [] } })
      if (saved) { Object.assign(saved, input); return route.fulfill({ json: saved }) }
    }
    return route.fulfill({ status: 404, json: { message: 'Not found.', fieldErrors: [] } })
  })
  await page.goto('/login')
  await page.getByLabel('Email address').fill('owner@example.test')
  await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Welcome to StockFlow.' })).toBeVisible()
}

test('admin creates, edits and archives a supplier', async ({ page }) => {
  await setup(page)
  await page.getByRole('link', { name: 'Suppliers', exact: true }).click()
  await page.getByRole('link', { name: 'Add supplier' }).click()
  await page.getByLabel('Supplier name').fill('New Supplier')
  await page.getByLabel('Contact email').fill('new@example.test')
  await page.getByRole('button', { name: 'Save supplier' }).click()
  await expect(page.getByRole('heading', { name: 'New Supplier' })).toBeVisible()
  await page.getByRole('link', { name: 'Edit supplier' }).click()
  await page.getByLabel('Contact email').fill('')
  await page.getByRole('button', { name: 'Save supplier' }).click()
  await expect(page.getByText('Not provided').first()).toBeVisible()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: 'Archive supplier' }).click()
  await expect(page.getByText('Archived', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Archive supplier' })).toHaveCount(0)
})

test('product edit assigns supplier and preserves stock; archive is confirmed', async ({ page }) => {
  await setup(page)
  await page.getByRole('link', { name: 'Products', exact: true }).click()
  await page.getByRole('link', { name: 'Demo Product' }).click()
  await page.getByRole('link', { name: 'Edit product' }).click()
  await page.getByLabel(/^Unit price/).fill('125.00')
  await page.getByLabel('Assigned supplier').selectOption('supplier-1')
  await page.getByRole('button', { name: 'Save product' }).click()
  await expect(page.getByText('LKR 125.00')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Demo Supplier' })).toBeVisible()
  await expect(page.locator('dl').getByText('9', { exact: true })).toBeVisible()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: 'Archive product' }).click()
  await expect(page.getByText('Archived', { exact: true })).toBeVisible()
})

test('account dialog creates staff and shows last-admin conflict', async ({ page }) => {
  await setup(page)
  await page.getByRole('link', { name: 'Users', exact: true }).click()
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account email').fill('staff@example.test')
  await dialog.getByLabel('Initial password').fill('test-only-long-password')
  await dialog.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('row', { name: /staff@example.test/ })).toContainText('STAFF')
  await page.getByRole('button', { name: 'Edit owner@example.test', exact: true }).click()
  await page.getByRole('dialog').getByLabel('Role', { exact: true }).selectOption('STAFF')
  page.once('dialog', confirmation => confirmation.accept())
  await page.getByRole('button', { name: 'Save access' }).click()
  await expect(page.getByRole('alert')).toContainText('At least one active administrator')
  await expect(page.getByRole('dialog')).toBeVisible()
})

test('self-demotion refreshes the session and removes admin navigation', async ({ page }) => {
  await setup(page, 'ADMIN', true)
  await page.getByRole('link', { name: 'Users', exact: true }).click()
  await page.getByRole('button', { name: 'Edit owner@example.test', exact: true }).click()
  await page.getByRole('dialog').getByLabel('Role', { exact: true }).selectOption('STAFF')
  page.once('dialog', confirmation => confirmation.accept())
  await page.getByRole('button', { name: 'Save access' }).click()
  await expect(page).toHaveURL(/dashboard/)
  await expect(page.getByRole('link', { name: 'Users', exact: true })).toHaveCount(0)
})

test('staff supplier view hides creation controls', async ({ page }) => {
  await setup(page, 'STAFF')
  await page.getByRole('link', { name: 'Suppliers', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Demo Supplier' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Add supplier' })).toHaveCount(0)
  await page.getByRole('link', { name: 'Demo Supplier' }).click()
  await expect(page.getByRole('link', { name: 'Edit supplier' })).toHaveCount(0)
})
