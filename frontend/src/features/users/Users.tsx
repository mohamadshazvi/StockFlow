import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth, type User } from '../../auth'
import { Failure, Modal, Pager } from '../../components/Management'

function AccountDialog({ account, close }: { account?: User; close: () => void }) {
  const { request, session, refresh } = useAuth(), cache = useQueryClient(), navigate = useNavigate()
  const [email, setEmail] = useState(''), [password, setPassword] = useState('')
  const [role, setRole] = useState(account?.role ?? 'STAFF'), [active, setActive] = useState(account?.active ?? true)
  const [validation, setValidation] = useState('')
  const mutation = useMutation({
    mutationFn: () => request<User>(account ? `/users/${account.id}` : '/users', { method: account ? 'PATCH' : 'POST', body: JSON.stringify(account ? { role, active } : { email: email.trim(), password, role }) }),
    onSuccess: async saved => {
      setPassword('')
      await cache.invalidateQueries({ queryKey: ['users'] })
      if (saved.id === session?.user.id) {
        await refresh().catch(() => undefined)
        if (saved.active && saved.role !== 'ADMIN') navigate('/dashboard', { replace: true })
      }
      close()
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    if (mutation.isPending) return
    const bytes = new TextEncoder().encode(password).length
    if (!account && (bytes < 12 || bytes > 72)) { setValidation('Choose a password of 12–72 UTF-8 bytes (12–72 plain ASCII characters).'); return }
    setValidation('')
    if (account && account.active && !active && !window.confirm('Deactivate this account? Its protected requests will be rejected.')) return
    if (account?.id === session?.user.id && (role !== 'ADMIN' || !active) && !window.confirm('You are changing your own access. You may lose this page or be signed out. Continue?')) return
    mutation.mutate()
  }
  function dismiss() { if (!mutation.isPending && window.confirm('Close this account form? Unsaved values will be discarded.')) close() }
  return <Modal title={account ? 'Edit account access' : 'Create account'} close={dismiss} pending={mutation.isPending}><Failure error={mutation.error} />{validation && <p className="error" role="alert">{validation}</p>}<form onSubmit={submit}>{account ? <p>{account.email}</p> : <><label htmlFor="account-email">Account email</label><input id="account-email" type="email" autoComplete="off" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} disabled={mutation.isPending} /><label htmlFor="initial-password">Initial password</label><input id="initial-password" type="password" autoComplete="new-password" required maxLength={72} value={password} onChange={event => setPassword(event.target.value)} disabled={mutation.isPending} /><p className="muted">12–72 UTF-8 bytes. Share the initial password securely; it will not be shown after creation.</p></>}<label htmlFor="account-role">Role</label><select id="account-role" value={role} onChange={event => setRole(event.target.value as User['role'])} disabled={mutation.isPending}><option value="STAFF">Staff</option><option value="ADMIN">Admin</option></select>{account && <label className="check"><input type="checkbox" checked={active} onChange={event => setActive(event.target.checked)} disabled={mutation.isPending} />Account active</label>}<p className="muted">At least one active administrator must remain.</p><div className="form-actions"><button disabled={mutation.isPending}>{mutation.isPending ? 'Saving…' : account ? 'Save access' : 'Create account'}</button><button type="button" className="secondary" disabled={mutation.isPending} onClick={dismiss}>Cancel</button></div></form></Modal>
}

export function Users() {
  const { session, request } = useAuth()
  const [page, setPage] = useState(0), [sort, setSort] = useState('email,asc')
  const [dialog, setDialog] = useState<{ account?: User } | null>(null)
  const query = useQuery({ queryKey: ['users', page, sort], queryFn: ({ signal }) => request<{ content: User[]; totalElements: number; totalPages: number }>(`/users?page=${page}&sort=${sort}`, { signal }), enabled: session?.user.role === 'ADMIN', retry: false })
  if (session?.user.role !== 'ADMIN') return <main><h1>Access denied</h1><Link to="/dashboard">Back to dashboard</Link></main>
  return <main className="catalogue"><div className="page-heading"><div><p className="eyebrow">ADMINISTRATION</p><h1>Users</h1></div><button onClick={() => setDialog({})}>Add user</button></div><section className="panel"><label htmlFor="user-sort">Sort accounts</label><select id="user-sort" value={sort} onChange={event => { setSort(event.target.value); setPage(0) }}><option value="email,asc">Email (A–Z)</option><option value="createdAt,desc">Newest first</option></select><Failure error={query.error} />{query.isPending && <p role="status">Loading accounts…</p>}{query.data && <><div className="table-scroll"><table><caption className="sr-only">Business accounts</caption><thead><tr><th>Email</th><th>Role</th><th>Status</th><th>Actions</th></tr></thead><tbody>{query.data.content.map(user => <tr key={user.id}><th scope="row">{user.email}{user.id === session.user.id && ' (you)'}</th><td>{user.role}</td><td>{user.active ? 'Active' : 'Inactive'}</td><td><button className="secondary" aria-label={`Edit ${user.email}`} onClick={() => setDialog({ account: user })}>Edit access</button></td></tr>)}</tbody></table></div>{!query.data.content.length && <p>No accounts on this page.</p>}<Pager {...query.data} page={page} onChange={setPage} pending={query.isFetching} /></>}</section>{dialog && <AccountDialog account={dialog.account} close={() => setDialog(null)} />}</main>
}
