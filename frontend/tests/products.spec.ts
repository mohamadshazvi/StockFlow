import { expect, test, type Page } from '@playwright/test'

// Browser interaction tests use mocked API contracts. Real persistence/security are
// covered separately by PostgreSQL-backed Spring integration tests.
type Product = { id: string; sku: string; name: string; description: string | null; unitPrice: string; stockQuantity: number; lowStockThreshold: number; supplierId: null; active: boolean; createdAt: string; updatedAt: string }
const product: Product = { id: 'd9e49324-bbf4-4d91-a726-659927f737d2', sku: 'RICE-001', name: 'White Rice 1kg', description: 'Demo product', unitPrice: '450.00', stockQuantity: 0, lowStockThreshold: 5, supplierId: null, active: true, createdAt: '2026-10-17T00:00:00Z', updatedAt: '2026-10-17T00:00:00Z' }

async function mockApi(page: Page, role: 'ADMIN' | 'STAFF' = 'ADMIN', initial: Product[] = []) {
  const products = [...initial]
  const user = { id: 'a547143e-2f01-4a69-9b71-f634c74f85f1', email: 'browser-test@example.test', role, active: true }
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { accessToken: 'mock-token', tokenType: 'Bearer', expiresIn: 900, user } })
    if (path.endsWith('/auth/me')) return route.fulfill({ json: user })
    if (path === '/api/v1/suppliers') return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    if (path.endsWith('/stock-movements')) return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    if (path === '/api/v1/products' && route.request().method() === 'POST') {
      const input = route.request().postDataJSON()
      if (products.some(p => p.sku === input.sku.toUpperCase())) return route.fulfill({ status: 409, json: { code: 'DUPLICATE_SKU', message: 'A product with this SKU already exists.', fieldErrors: [] } })
      const created = { ...product, ...input, sku: input.sku.toUpperCase(), unitPrice: input.unitPrice.includes('.') ? input.unitPrice : `${input.unitPrice}.00` }
      products.push(created)
      return route.fulfill({ status: 201, json: created })
    }
    if (path === '/api/v1/products') {
      const search = (url.searchParams.get('search') ?? '').toLowerCase()
      const index = Number(url.searchParams.get('page') ?? 0)
      const size = Number(url.searchParams.get('size') ?? 20)
      const filtered = products.filter(p => `${p.name} ${p.sku}`.toLowerCase().includes(search))
      return route.fulfill({ json: { content: filtered.slice(index * size, (index + 1) * size), page: index, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size) } })
    }
    const found = products.find(p => path === `/api/v1/products/${p.id}`)
    return found ? route.fulfill({ json: found }) : route.fulfill({ status: 404, json: { code: 'PRODUCT_NOT_FOUND', message: 'Product not found.', fieldErrors: [] } })
  })
}

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email address').fill('browser-test@example.test')
  await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Welcome to StockFlow.' })).toBeVisible()
  await page.getByRole('link', { name: 'Open product catalogue' }).click()
}

async function fillProduct(page: Page) {
  await page.getByLabel(/^SKU/).fill('RICE-001')
  await page.getByLabel(/^Product name/).fill('White Rice 1kg')
  await page.getByLabel(/^Unit price/).fill('450.00')
  await page.getByLabel(/^Low-stock threshold/).fill('5')
}

test('admin creates a product and reads it in detail and list', async ({ page }) => {
  await mockApi(page)
  await login(page)
  await expect(page.getByRole('heading', { name: 'Your catalogue starts here' })).toBeVisible()
  await page.getByRole('link', { name: 'Add product' }).click()
  await fillProduct(page)
  await page.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('heading', { name: 'White Rice 1kg' })).toBeVisible()
  await expect(page.getByText('Product created successfully.')).toBeVisible()
  await expect(page.getByText('LKR 450.00')).toBeVisible()
  await page.getByRole('link', { name: '← Products' }).click()
  await expect(page.getByRole('link', { name: 'White Rice 1kg' })).toBeVisible()
})

test('staff reads products and cannot open the create form', async ({ page }) => {
  await mockApi(page, 'STAFF', [product])
  await login(page)
  await expect(page.getByRole('link', { name: 'Add product' })).toHaveCount(0)
  await page.getByRole('link', { name: 'White Rice 1kg' }).click()
  await expect(page.getByRole('heading', { name: 'White Rice 1kg' })).toBeVisible()
  // SPA navigation retains the in-memory session.
  await page.evaluate(() => { window.history.pushState({}, '', '/products/new'); window.dispatchEvent(new PopStateEvent('popstate')) })
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible()
})

test('shows validation and preserves inputs after a duplicate conflict', async ({ page }) => {
  await mockApi(page, 'ADMIN', [product])
  await login(page)
  await page.getByRole('link', { name: 'Add product' }).click()
  await page.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('alert')).toContainText('Check the highlighted fields.')
  await fillProduct(page)
  await page.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('alert')).toContainText('SKU already exists')
  await expect(page.getByLabel(/^Product name/)).toHaveValue('White Rice 1kg')
  await expect(page.getByLabel(/^SKU/)).toHaveAttribute('aria-invalid', 'true')
})

test('search, pagination and return navigation preserve list state', async ({ page }) => {
  const many = Array.from({ length: 21 }, (_, i) => ({ ...product, id: `id-${i}`, sku: `ITEM-${i}`, name: `Item ${i}` }))
  await mockApi(page, 'ADMIN', many)
  await login(page)
  await page.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(page).toHaveURL(/page=1/)
  await page.getByRole('link', { name: 'Item 20', exact: true }).click()
  await page.getByRole('link', { name: '← Products' }).click()
  await expect(page).toHaveURL(/page=1/)
  await page.getByLabel('Search by name or SKU').fill('does-not-exist')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'No matching products' })).toBeVisible()
  await page.getByRole('button', { name: 'Reset search and page' }).click()
  await expect(page.getByRole('link', { name: 'Item 0', exact: true })).toBeVisible()
})

test('a protected request returning 401 clears the session', async ({ page }) => {
  await mockApi(page)
  await page.route('**/api/v1/products?*', route => new URL(route.request().url()).searchParams.has('lowStock') && new URL(route.request().url()).searchParams.get('size') === '5' ? route.fallback() : route.fulfill({ status: 401, json: { message: 'Please sign in again.' } }))
  await login(page)
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
  await expect(page).toHaveURL(/login/)
})

test('catalogue fits a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 })
  await mockApi(page, 'ADMIN', [product])
  await login(page)
  await expect(page.getByRole('link', { name: 'White Rice 1kg' })).toBeVisible()
  const overflows = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(overflows).toBe(false)
})
