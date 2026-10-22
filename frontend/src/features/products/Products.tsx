import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth'
import { ApiError } from '../../lib/api'
import { SupplierPicker } from '../suppliers/Suppliers'
import { InventoryPanel } from '../inventory/Inventory'
import './products.css'

type Product = {
  id: string; sku: string; name: string; description: string | null; unitPrice: string
  stockQuantity: number; lowStockThreshold: number; supplierId: string | null
  active: boolean; createdAt: string; updatedAt: string
}
type ProductPage = { content: Product[]; page: number; size: number; totalElements: number; totalPages: number }
type CreateProduct = { sku: string; name: string; description: string | null; unitPrice: string; lowStockThreshold: number; supplierId: string | null }

function price(value: string) {
  const [whole, fraction = '00'] = value.split('.')
  return `LKR ${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`
}

function StockStatus({ product }: { product: Product }) {
  return <span className={`badge ${product.active && product.stockQuantity > product.lowStockThreshold ? 'ready' : ''}`}>{!product.active ? 'Archived' : product.stockQuantity <= product.lowStockThreshold ? 'Low stock' : 'In stock'}</span>
}

function ErrorPanel({ error, retry }: { error: Error; retry?: () => void }) {
  const message = error instanceof ApiError ? error.message : 'Cannot reach the server. Check your connection and try again.'
  return <section className="panel error" role="alert"><h2>{error instanceof ApiError && error.status === 404 ? 'Product not found' : 'Unable to load products'}</h2><p>{message}</p>{retry && <button className="secondary" onClick={retry}>Try again</button>}</section>
}

export function ProductList() {
  const { request, session } = useAuth()
  const [params, setParams] = useSearchParams()
  const search = params.get('search') ?? ''
  const rawPage = params.get('page') ?? '0'
  const page = /^\d+$/.test(rawPage) && Number.isSafeInteger(Number(rawPage)) ? Number(rawPage) : 0
  const sort = params.get('sort') ?? 'name,asc'
  const active = params.get('active') ?? 'true', lowStock = params.get('lowStock') ?? 'false', supplierId = params.get('supplierId') ?? ''
  const query = useQuery({
    queryKey: ['products', { search, page, sort, active, lowStock, supplierId }],
    queryFn: ({ signal }) => request<ProductPage>(`/products?${new URLSearchParams({ search, page: String(page), size: '20', sort, active, lowStock, ...(supplierId ? { supplierId } : {}) })}`, { signal }),
    retry: false,
  })
  function change(values: Record<string, string>) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next)
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    change({ search: String(new FormData(event.currentTarget).get('search') ?? '').trim(), page: '0' })
  }
  return <main className="catalogue">
    <p className="eyebrow">CATALOGUE</p>
    <div className="page-heading"><div><h1>Products</h1><p className="muted">Your catalogue, prices, and current stock.</p></div>{session?.user.role === 'ADMIN' && <Link className="button-link" to="/products/new">Add product</Link>}</div>
    <section className="panel">
      <div className="catalogue-toolbar"><form className="search-form" onSubmit={submit}><label htmlFor="product-search">Search by name or SKU</label><div className="search-controls"><input key={search} id="product-search" name="search" type="search" maxLength={160} defaultValue={search} /><button>Search</button></div></form><div><label htmlFor="product-sort">Sort by</label><select id="product-sort" value={sort} onChange={event => change({ sort: event.target.value, page: '0' })}><option value="name,asc">Name (A–Z)</option><option value="name,desc">Name (Z–A)</option><option value="createdAt,desc">Newest first</option><option value="unitPrice,asc">Price (low to high)</option><option value="sku,asc">SKU (A–Z)</option></select></div></div>
      {query.isPending && <p role="status">Loading products…</p>}
      <div className="catalogue-toolbar"><div><label htmlFor="product-status">Catalogue status</label><select id="product-status" value={active} onChange={event => change({ active: event.target.value, page: '0' })}><option value="true">Active</option><option value="false">Archived</option><option value="all">All products</option></select></div><label className="check"><input type="checkbox" checked={lowStock === 'true'} onChange={event => change({ lowStock: String(event.target.checked), page: '0' })} />Low-stock active products only</label><details><summary>Filter by supplier</summary><SupplierPicker value={supplierId} change={value => change({ supplierId: value, page: '0' })} /></details></div>
      {query.isError && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
      {query.data && !query.isError && <>
        {query.data.content.length === 0 ? <div className="empty-state"><h2>{search ? 'No matching products' : page > 0 ? 'No products on this page' : 'Your catalogue starts here'}</h2><p>{search ? 'Try a different name or SKU.' : session?.user.role === 'ADMIN' ? 'Add your first product or change your filters.' : 'Ask an administrator to add products, or change your filters.'}</p>{(search || page > 0 || active !== 'true' || lowStock !== 'false' || supplierId) && <button className="secondary" onClick={() => change({ search: '', page: '0', active: 'true', lowStock: 'false', supplierId: '' })}>Reset search and page</button>}</div> : <div className="table-scroll" tabIndex={0} role="region" aria-label="Products table, scroll horizontally for more columns"><table><caption className="sr-only">Product catalogue</caption><thead><tr><th scope="col">SKU</th><th scope="col">Product</th><th scope="col" className="numeric">Unit price</th><th scope="col" className="numeric">Stock</th><th scope="col">Status</th></tr></thead><tbody>{query.data.content.map(product => <tr key={product.id}><td className="sku">{product.sku}</td><th scope="row"><Link to={`/products/${product.id}`} state={{ listSearch: params.toString() }}>{product.name}</Link></th><td className="numeric">{price(product.unitPrice)}</td><td className="numeric">{product.stockQuantity}</td><td><StockStatus product={product} /></td></tr>)}</tbody></table></div>}
        <div className="pagination"><p className="muted" role="status">{query.data.totalElements} products · Page {query.data.totalPages === 0 ? 0 : query.data.page + 1} of {query.data.totalPages}</p><div><button className="secondary" disabled={page === 0 || query.isFetching} onClick={() => change({ page: String(page - 1) })}>Previous</button><button className="secondary" disabled={page + 1 >= query.data.totalPages || query.isFetching} onClick={() => change({ page: String(page + 1) })}>Next</button></div></div>
      </>}
    </section>
  </main>
}

function ProductForm({ existing }: { existing?: Product }) {
  const { request, session } = useAuth()
  const navigate = useNavigate()
  const cache = useQueryClient()
  const initial = { sku: existing?.sku ?? '', name: existing?.name ?? '', description: existing?.description ?? '', unitPrice: existing?.unitPrice ?? '', lowStockThreshold: String(existing?.lowStockThreshold ?? 0), supplierId: existing?.supplierId ?? '' }
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState('')
  const summary = useRef<HTMLDivElement>(null)
  const dirty = JSON.stringify(values) !== JSON.stringify(initial)
  const mutation = useMutation({
    mutationFn: (body: CreateProduct) => request<Product>(existing ? `/products/${existing.id}` : '/products', { method: existing ? 'PATCH' : 'POST', body: JSON.stringify(body) }),
    onSuccess: async product => { await cache.invalidateQueries({ queryKey: ['products'] }); cache.setQueryData(['product', product.id], product); navigate(`/products/${product.id}`, { replace: true, state: { created: !existing } }) },
    onError: error => {
      const fields: Record<string, string> = {}
      if (error instanceof ApiError) {
        for (const field of error.fieldErrors) fields[field.field] = field.message
        if (error.code === 'DUPLICATE_SKU') fields.sku = 'This SKU is already in use.'
      }
      setErrors(fields)
      setMessage(error instanceof ApiError ? error.message : 'The result could not be confirmed. Check the product list for this SKU before retrying.')
      window.setTimeout(() => summary.current?.focus(), 0)
    },
  })
  useEffect(() => {
    if (!dirty || mutation.isSuccess) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const warnForLink = (event: MouseEvent) => {
      const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null
      if (!(anchor instanceof HTMLAnchorElement) || anchor.target === '_blank') return
      const destination = new URL(anchor.href)
      if (destination.origin === window.location.origin && destination.pathname !== window.location.pathname
          && !window.confirm('Discard your unsaved product?')) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', warn)
    document.addEventListener('click', warnForLink, true)
    return () => {
      window.removeEventListener('beforeunload', warn)
      document.removeEventListener('click', warnForLink, true)
    }
  }, [dirty, mutation.isSuccess])
  if (session?.user.role !== 'ADMIN') return <main><h1>Access denied</h1><p>Only administrators can create products.</p><Link to="/products">Back to products</Link></main>

  function submit(event: FormEvent) {
    event.preventDefault()
    if (mutation.isPending) return
    const validation: Record<string, string> = {}
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(values.sku.trim())) validation.sku = 'Use 1–64 letters, digits, dots, underscores or hyphens.'
    if (!values.name.trim()) validation.name = 'Enter a product name.'
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(values.unitPrice.trim())) validation.unitPrice = 'Enter a nonnegative price with up to 2 decimal places.'
    if (!/^\d+$/.test(values.lowStockThreshold) || Number(values.lowStockThreshold) > 2147483647) validation.lowStockThreshold = 'Enter a whole number from 0 to 2147483647.'
    setErrors(validation); setMessage('')
    if (Object.keys(validation).length) { setMessage('Check the highlighted fields.'); window.setTimeout(() => summary.current?.focus(), 0); return }
    mutation.mutate({ sku: values.sku.trim(), name: values.name.trim(), description: values.description.trim() || null, unitPrice: values.unitPrice.trim(), lowStockThreshold: Number(values.lowStockThreshold), supplierId: values.supplierId || null })
  }
  function cancel() { if (!dirty || window.confirm('Discard your unsaved product?')) navigate('/products') }
  function input(field: keyof typeof values) {
    return { id: field, value: values[field], disabled: mutation.isPending, 'aria-invalid': Boolean(errors[field]), 'aria-describedby': errors[field] ? `${field}-error` : undefined, onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setValues(previous => ({ ...previous, [field]: event.target.value })) }
  }
  const fieldError = (field: string) => errors[field] && <p id={`${field}-error`} className="field-error">{errors[field]}</p>
  return <main className="catalogue"><button className="secondary" onClick={cancel} disabled={mutation.isPending}>← Products</button><h1>{existing ? 'Edit product' : 'Add product'}</h1><p className="intro">{existing ? 'Update catalogue details without changing stock.' : 'Create a catalogue item. Stock starts at zero and will be managed through inventory movements.'}</p><section className="panel product-form">
    {message && <div className="error" role="alert" tabIndex={-1} ref={summary}><strong>{message}</strong>{Object.keys(errors).length > 0 && <ul>{Object.entries(errors).map(([field, text]) => <li key={field}><a href={`#${field}`}>{text}</a></li>)}</ul>}</div>}
    <form onSubmit={submit} noValidate>
      <label htmlFor="sku">SKU <span className="muted">(required, unique)</span></label><input {...input('sku')} maxLength={64} required autoComplete="off" placeholder="RICE-001" />{fieldError('sku')}
      <label htmlFor="name">Product name <span className="muted">(required)</span></label><input {...input('name')} maxLength={160} required />{fieldError('name')}
      <label htmlFor="description">Description <span className="muted">(optional)</span></label><textarea {...input('description')} rows={4} maxLength={2000} />{fieldError('description')}
      <label htmlFor="unitPrice">Unit price (LKR) <span className="muted">(required)</span></label><input {...input('unitPrice')} inputMode="decimal" maxLength={13} placeholder="450.00" required />{fieldError('unitPrice')}
      <label htmlFor="lowStockThreshold">Low-stock threshold <span className="muted">(required)</span></label><input {...input('lowStockThreshold')} inputMode="numeric" maxLength={10} required />{fieldError('lowStockThreshold')}
      <SupplierPicker value={values.supplierId} change={supplierId => setValues(previous => ({ ...previous, supplierId }))} disabled={mutation.isPending} /><div className="form-actions"><button disabled={mutation.isPending}>{mutation.isPending ? 'Saving…' : existing ? 'Save product' : 'Create product'}</button><button type="button" className="secondary" onClick={cancel} disabled={mutation.isPending}>Cancel</button></div>
    </form>
  </section></main>
}

export function ProductCreate() { return <ProductForm /> }
export function ProductEdit() {
  const { id } = useParams(), { request } = useAuth()
  const query = useQuery({ queryKey: ['product', id], queryFn: ({ signal }) => request<Product>(`/products/${id}`, { signal }), retry: false })
  return query.data ? <ProductForm key={query.data.id} existing={query.data} /> : <main>{query.isPending && <p role="status">Loading product…</p>}{query.error && <ErrorPanel error={query.error} />}</main>
}

export function ProductDetail() {
  const { id } = useParams()
  const location = useLocation()
  const listSearch = typeof location.state?.listSearch === 'string' ? location.state.listSearch : ''
  const { request, session } = useAuth()
  const cache = useQueryClient()
  const archive = useMutation({ mutationFn: () => request<Product>(`/products/${id}`, { method: 'PATCH', body: JSON.stringify({ active: false }) }), onSuccess: async saved => { cache.setQueryData(['product', id], saved); await cache.invalidateQueries({ queryKey: ['products'] }) } })
  const query = useQuery({ queryKey: ['product', id], queryFn: ({ signal }) => request<Product>(`/products/${id}`, { signal }), retry: false })
  const product = query.data
  const supplier = useQuery({ queryKey: ['supplier', product?.supplierId], queryFn: ({ signal }) => request<{ name: string; active: boolean }>(`/suppliers/${product?.supplierId}`, { signal }), enabled: Boolean(product?.supplierId), retry: false })
  return <main className="catalogue">
    <Link to={`/products${listSearch ? `?${listSearch}` : ''}`}>← Products</Link>
    {location.state?.created && <p className="message" role="status">Product created successfully.</p>}
    {query.isPending && <p role="status">Loading product…</p>}
    {query.isError && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
    {archive.error && <p className="error" role="alert">{archive.error.message}</p>}
    {product && <>
      <p className="eyebrow">PRODUCT / {product.sku}</p><h1>{product.name}</h1>
      {session?.user.role === 'ADMIN' && <div className="form-actions"><Link className="button-link" to={`/products/${id}/edit`}>Edit product</Link>{product.active && <button className="secondary" disabled={archive.isPending} onClick={() => { if (window.confirm('Archive this product? Future order confirmation will be blocked. History is preserved. This cannot be undone.')) archive.mutate() }}>Archive product</button>}</div>}
      <section className="panel"><div className="panel-heading"><h2>Product details</h2><span className={`badge ${product.active ? 'ready' : ''}`}>{product.active ? 'Active' : 'Archived'}</span></div>
        <dl className="product-facts"><div><dt>Unit price</dt><dd>{price(product.unitPrice)}</dd></div><div><dt>Current stock</dt><dd>{product.stockQuantity}</dd></div><div><dt>Low-stock threshold</dt><dd>{product.lowStockThreshold}</dd></div><div><dt>SKU</dt><dd className="sku">{product.sku}</dd></div><div><dt>Created</dt><dd>{new Date(product.createdAt).toLocaleString()}</dd></div><div><dt>Supplier</dt><dd>{product.supplierId ? <Link to={`/suppliers/${product.supplierId}`}>{supplier.data?.name ?? 'View supplier'}{supplier.data && !supplier.data.active && ' (archived)'}</Link> : 'Not assigned'}</dd></div></dl>
        <h3>Description</h3><p className="description">{product.description ?? 'Not provided'}</p>
      </section>
      <InventoryPanel key={product.id} product={product} />
    </>}
  </main>
}
