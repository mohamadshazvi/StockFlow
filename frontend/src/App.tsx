import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth'
import { ProductList, ProductCreate, ProductDetail, ProductEdit } from './features/products/Products'
import { SupplierList, SupplierCreate, SupplierDetail, SupplierEdit } from './features/suppliers/Suppliers'
import { Users } from './features/users/Users'
import { OrderList, OrderCreate, OrderDetail } from './features/orders/Orders'
import { Dashboard } from './features/dashboard/Dashboard'
import './App.css'

function Login() {
  const { session, notice, login } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  if (session) return <Navigate to="/dashboard" replace />
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    setPending(true); setError('')
    try {
      await login(email.trim(), password)
      setPassword('')
      const from: unknown = location.state?.from
      const safePaths = ['/dashboard', '/products', '/suppliers', '/orders', '/users']
      navigate(typeof from === 'string' && safePaths.includes(from) ? from : '/dashboard', { replace: true })
    } catch (failure) {
      setError(failure instanceof Error && failure.name !== 'TypeError' ? failure.message : 'Cannot reach the server. Check your connection and try again.')
    } finally { setPending(false) }
  }
  return <main className="login-layout"><section className="login-intro"><Link className="brand" to="/">Stock<span>Flow</span></Link><p className="eyebrow">YOUR OPERATIONS, IN ORDER</p><h1>Clear stock.<br />Confident decisions.</h1><p className="intro">One workspace for your catalogue, inventory, and customer orders.</p><p className="muted">Secure access for your business team.</p></section><section className="panel login-card"><h2>Welcome back</h2><p className="muted">Sign in with your team account.</p>{notice && <p role="status" className="message">{notice}</p>}{error && <p role="alert" className="error">{error}</p>}<form onSubmit={event => void submit(event)}><label htmlFor="email">Email address</label><input id="email" type="email" autoComplete="username" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} disabled={pending} /><label htmlFor="password">Password</label><input id="password" type={visible ? 'text' : 'password'} autoComplete="current-password" required maxLength={72} value={password} onChange={event => setPassword(event.target.value)} disabled={pending} /><label className="check"><input type="checkbox" checked={visible} onChange={event => setVisible(event.target.checked)} />Show password</label><button className="full" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</button></form><p className="muted">Need an account? Contact your administrator.</p><p className="muted">For security, reloading the page requires signing in again.</p></section></main>
}

function Protected() {
  const { session } = useAuth()
  const location = useLocation()
  return session ? <Outlet /> : <Navigate to="/login" state={{ from: location.pathname }} replace />
}

function Shell() {
  const { session, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLButtonElement>(null)
  const navigation = [['/dashboard', 'Dashboard'], ['/products', 'Products'], ['/suppliers', 'Suppliers'], ['/orders', 'Orders']]
  if (session?.user.role === 'ADMIN') navigation.push(['/users', 'Users'])
  return <div className="app-shell"><a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); const main = document.getElementById('main-content'); main?.focus(); main?.scrollIntoView() }}>Skip to main content</a><aside className="sidebar" onKeyDown={event => { if (event.key === 'Escape' && open) { setOpen(false); menu.current?.focus() } }}><Link className="brand" to="/dashboard">Stock<span>Flow</span></Link><button ref={menu} className="menu-toggle" aria-expanded={open} aria-controls="navigation" onClick={() => setOpen(!open)}>Menu</button><nav id="navigation" className={open ? 'expanded' : ''} aria-label="Main navigation">{navigation.map(([to, label]) => <NavLink key={to} to={to} onClick={() => setOpen(false)}>{label}</NavLink>)}</nav><p className="sidebar-note">Inventory & order management</p></aside><div className="workspace"><header className="workspace-header"><span className="muted">Team workspace</span><div className="account"><div><strong>{session?.user.email}</strong><span className="muted">{session?.user.role}</span></div><button className="secondary" onClick={() => { if (window.dispatchEvent(new Event('stockflow:signout', { cancelable: true }))) logout() }}>Sign out</button></div></header><Outlet /></div></div>
}

function RouteAccessibility() {
  const { pathname } = useLocation()
  useEffect(() => {
    const main = document.querySelector('main')
    if (main) { main.id = 'main-content'; main.tabIndex = -1; main.focus({ preventScroll: true }) }
    window.scrollTo(0, 0)
    document.title = `StockFlow — ${pathname.split('/')[1] || 'Workspace'}`
  }, [pathname])
  return null
}


function Home() { const { session } = useAuth(); return <Navigate to={session ? '/dashboard' : '/login'} replace /> }

export default function App() {
  return <AuthProvider><RouteAccessibility /><Routes>
    <Route path="/" element={<Home />} /><Route path="/login" element={<Login />} />
    <Route element={<Protected />}><Route element={<Shell />}>
      <Route path="/dashboard" element={<Dashboard />} />
      <Route path="/products" element={<ProductList />} /><Route path="/products/new" element={<ProductCreate />} /><Route path="/products/:id/edit" element={<ProductEdit />} /><Route path="/products/:id" element={<ProductDetail />} />
      <Route path="/suppliers" element={<SupplierList />} /><Route path="/suppliers/new" element={<SupplierCreate />} /><Route path="/suppliers/:id/edit" element={<SupplierEdit />} /><Route path="/suppliers/:id" element={<SupplierDetail />} />
      <Route path="/orders" element={<OrderList />} /><Route path="/orders/new" element={<OrderCreate />} /><Route path="/orders/:id" element={<OrderDetail />} /><Route path="/users" element={<Users />} />
      <Route path="*" element={<main><h1>Page not found</h1><Link to="/dashboard">Back to dashboard</Link></main>} />
    </Route></Route>
  </Routes></AuthProvider>
}
