import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth'
import { Failure, Pager } from '../../components/Management'
import { useDirty } from '../../lib/useDirty'

export type Supplier = { id: string; name: string; email: string | null; phone: string | null; address: string | null; active: boolean }
export type SupplierPage = { content: Supplier[]; page: number; totalPages: number; totalElements: number }
type Input = { name: string; email: string | null; phone: string | null; address: string | null }

export function SupplierPicker({ value, change, disabled = false }: { value: string; change: (id: string) => void; disabled?: boolean }) {
  const { request } = useAuth()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: ['suppliers', 'picker', search, page], queryFn: ({ signal }) => request<SupplierPage>(`/suppliers?${new URLSearchParams({ search, page: String(page), size: '20' })}`, { signal }), retry: false })
  const selected = useQuery({ queryKey: ['supplier', value], queryFn: ({ signal }) => request<Supplier>(`/suppliers/${value}`, { signal }), enabled: Boolean(value), retry: false })
  return <div className="supplier-picker"><label htmlFor="supplier-search">Find supplier (optional)</label><input id="supplier-search" value={search} maxLength={160} disabled={disabled} onChange={event => { setSearch(event.target.value); setPage(0) }} /><label htmlFor="supplierId">Assigned supplier</label><select id="supplierId" value={value} onChange={event => change(event.target.value)} disabled={disabled}><option value="">No supplier</option>{value && !query.data?.content.some(s => s.id === value) && <option value={value}>{selected.data?.name ?? 'Current supplier'}{selected.data && !selected.data.active ? ' (archived — existing link)' : ''}</option>}{query.data?.content.map(s => <option value={s.id} key={s.id}>{s.name}</option>)}</select>{query.isPending && <p role="status">Loading suppliers…</p>}<Failure error={query.error ?? selected.error} />{query.data && query.data.totalPages > 1 && <Pager {...query.data} page={page} onChange={setPage} pending={query.isFetching || disabled} />}</div>
}

export function SupplierList() {
  const { request, session } = useAuth()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page')) || 0)
  const search = params.get('search') ?? '', active = params.get('active') ?? 'true', sort = params.get('sort') ?? 'name,asc'
  const query = useQuery({ queryKey: ['suppliers', search, active, page, sort], queryFn: ({ signal }) => request<SupplierPage>(`/suppliers?${new URLSearchParams({ search, active, page: String(page), sort })}`, { signal }), retry: false })
  function update(values: Record<string, string>) { setParams({ search, active, sort, page: '0', ...values }) }
  return <main className="catalogue"><div className="page-heading"><div><p className="eyebrow">CATALOGUE</p><h1>Suppliers</h1></div>{session?.user.role === 'ADMIN' && <Link className="button-link" to="/suppliers/new">Add supplier</Link>}</div><section className="panel"><div className="catalogue-toolbar"><form className="search-form" onSubmit={event => { event.preventDefault(); update({ search: String(new FormData(event.currentTarget).get('search') ?? '').trim() }) }}><label htmlFor="supplier-list-search">Search suppliers</label><div className="search-controls"><input key={search} id="supplier-list-search" name="search" defaultValue={search} maxLength={160} /><button>Search</button></div></form><div><label htmlFor="supplier-status">Status</label><select id="supplier-status" value={active} onChange={event => update({ active: event.target.value })}><option value="true">Active</option><option value="false">Archived</option><option value="all">All</option></select></div><div><label htmlFor="supplier-sort">Sort suppliers</label><select id="supplier-sort" value={sort} onChange={event => update({ sort: event.target.value })}><option value="name,asc">Name (A–Z)</option><option value="createdAt,desc">Newest first</option></select></div></div>{query.isPending && <p role="status">Loading suppliers…</p>}<Failure error={query.error} />{query.data && <>{query.data.content.length ? <div className="table-scroll"><table><caption className="sr-only">Supplier directory</caption><thead><tr><th>Name</th><th>Email</th><th>Phone</th><th>Status</th></tr></thead><tbody>{query.data.content.map(s => <tr key={s.id}><th scope="row"><Link to={`/suppliers/${s.id}`}>{s.name}</Link></th><td>{s.email ?? 'Not provided'}</td><td>{s.phone ?? 'Not provided'}</td><td>{s.active ? 'Active' : 'Archived'}</td></tr>)}</tbody></table></div> : <p>No suppliers match these filters.</p>}<Pager {...query.data} page={page} pending={query.isFetching} onChange={value => update({ page: String(value) })} /></>}</section></main>
}

function SupplierForm({ existing }: { existing?: Supplier }) {
  const { request, session } = useAuth()
  const navigate = useNavigate(), cache = useQueryClient()
  const initial = { name: existing?.name ?? '', email: existing?.email ?? '', phone: existing?.phone ?? '', address: existing?.address ?? '' }
  const [values, setValues] = useState(initial)
  const dirty = JSON.stringify(values) !== JSON.stringify(initial)
  const allowNavigation = useDirty(dirty)
  const mutation = useMutation({ mutationFn: (input: Input) => request<Supplier>(existing ? `/suppliers/${existing.id}` : '/suppliers', { method: existing ? 'PATCH' : 'POST', body: JSON.stringify(input) }), onSuccess: async saved => { await cache.invalidateQueries({ queryKey: ['suppliers'] }); cache.setQueryData(['supplier', saved.id], saved); allowNavigation(); navigate(`/suppliers/${saved.id}`, { replace: true }) } })
  if (session?.user.role !== 'ADMIN') return <main><h1>Access denied</h1><Link to="/suppliers">Back to suppliers</Link></main>
  function submit(event: FormEvent) { event.preventDefault(); if (!mutation.isPending) mutation.mutate({ name: values.name.trim(), email: values.email.trim() || null, phone: values.phone.trim() || null, address: values.address.trim() || null }) }
  return <main className="catalogue"><Link to="/suppliers">← Suppliers</Link><h1>{existing ? 'Edit supplier' : 'Add supplier'}</h1><section className="panel product-form"><Failure error={mutation.error} /><form onSubmit={submit}><label htmlFor="name">Supplier name</label><input id="name" value={values.name} onChange={e => setValues({ ...values, name: e.target.value })} maxLength={160} required disabled={mutation.isPending} /><label htmlFor="email">Contact email (optional)</label><input id="email" type="email" value={values.email} onChange={e => setValues({ ...values, email: e.target.value })} maxLength={254} disabled={mutation.isPending} /><label htmlFor="phone">Phone (optional)</label><input id="phone" value={values.phone} onChange={e => setValues({ ...values, phone: e.target.value })} maxLength={32} disabled={mutation.isPending} /><label htmlFor="address">Address (optional)</label><textarea id="address" value={values.address} onChange={e => setValues({ ...values, address: e.target.value })} maxLength={500} disabled={mutation.isPending} /><div className="form-actions"><button disabled={mutation.isPending}>{mutation.isPending ? 'Saving…' : 'Save supplier'}</button><button type="button" className="secondary" disabled={mutation.isPending} onClick={() => navigate(existing ? `/suppliers/${existing.id}` : '/suppliers')}>Cancel</button></div></form></section></main>
}

export function SupplierCreate() { return <SupplierForm /> }
export function SupplierEdit() {
  const { id } = useParams(), { request } = useAuth()
  const query = useQuery({ queryKey: ['supplier', id], queryFn: ({ signal }) => request<Supplier>(`/suppliers/${id}`, { signal }), retry: false })
  return query.data ? <SupplierForm key={query.data.id} existing={query.data} /> : <main><Failure error={query.error} />{query.isPending && <p role="status">Loading supplier…</p>}</main>
}

export function SupplierDetail() {
  const { id } = useParams(), { request, session } = useAuth(), cache = useQueryClient()
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: ['supplier', id], queryFn: ({ signal }) => request<Supplier>(`/suppliers/${id}`, { signal }), retry: false })
  const linked = useQuery({ queryKey: ['products', 'supplier', id, page], queryFn: ({ signal }) => request<{ content: { id: string; name: string; sku: string; active: boolean }[]; totalPages: number; totalElements: number }>(`/products?supplierId=${id}&active=all&page=${page}`, { signal }), retry: false })
  const archive = useMutation({ mutationFn: () => request<Supplier>(`/suppliers/${id}`, { method: 'PATCH', body: JSON.stringify({ active: false }) }), onSuccess: async saved => { cache.setQueryData(['supplier', id], saved); await cache.invalidateQueries({ queryKey: ['suppliers'] }) } })
  const s = query.data
  return <main className="catalogue"><Link to="/suppliers">← Suppliers</Link><Failure error={query.error ?? archive.error} />{query.isPending && <p role="status">Loading supplier…</p>}{s && <><div className="page-heading"><h1>{s.name}</h1>{session?.user.role === 'ADMIN' && <div className="form-actions"><Link className="button-link" to={`/suppliers/${id}/edit`}>Edit supplier</Link>{s.active && <button className="secondary" disabled={archive.isPending} onClick={() => { if (window.confirm('Archive this supplier? Existing product links remain; new assignments will be blocked. This cannot be undone.')) archive.mutate() }}>Archive supplier</button>}</div>}</div><section className="panel"><span className={`badge ${s.active ? 'ready' : ''}`}>{s.active ? 'Active' : 'Archived'}</span><dl className="product-facts"><div><dt>Email</dt><dd>{s.email ?? 'Not provided'}</dd></div><div><dt>Phone</dt><dd>{s.phone ?? 'Not provided'}</dd></div><div><dt>Address</dt><dd className="description">{s.address ?? 'Not provided'}</dd></div></dl></section><section className="panel"><h2>Linked products</h2><Failure error={linked.error} />{linked.isPending && <p role="status">Loading products…</p>}{linked.data && <>{linked.data.content.length ? <ul>{linked.data.content.map(p => <li key={p.id}><Link to={`/products/${p.id}`}>{p.name}</Link> · {p.sku}{!p.active && ' (archived)'}</li>)}</ul> : <p>No linked products.</p>}<Pager {...linked.data} page={page} onChange={setPage} pending={linked.isFetching} /></>}</section></>}</main>
}
