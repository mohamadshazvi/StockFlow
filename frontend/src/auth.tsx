import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { readResponse } from './lib/api'

export type User = { id: string; email: string; role: 'ADMIN' | 'STAFF'; active: boolean }
type Session = { user: User; expiresAt: number }
type Auth = { session: Session | null; notice: string; login: (email: string, password: string) => Promise<void>; logout: () => void; refresh: () => Promise<void>; request: <T>(path: string, options?: RequestInit) => Promise<T> }
const AuthContext = createContext<Auth | null>(null)
const base = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [notice, setNotice] = useState('')
  const token = useRef<string | null>(null)
  const queryClient = useQueryClient()
  const expiresAt = session?.expiresAt
  const clear = useCallback((message = '') => {
    token.current = null
    setSession(null)
    setNotice(message)
    queryClient.clear()
  }, [queryClient])

  const request = useCallback(async <T,>(path: string, options: RequestInit = {}): Promise<T> => {
    const currentToken = token.current
    const headers = new Headers(options.headers)
    if (currentToken) headers.set('Authorization', `Bearer ${currentToken}`)
    if (options.body) headers.set('Content-Type', 'application/json')
    const response = await fetch(`${base}/api/v1${path}`, {
      ...options, headers, signal: options.signal ?? AbortSignal.timeout(15_000),
    })
    if (response.status === 401 && token.current === currentToken) clear('Your session is no longer valid. Please sign in again.')
    return readResponse<T>(response)
  }, [clear])

  const refresh = useCallback(async () => {
    const currentToken = token.current
    const user = await request<User>('/auth/me')
    if (token.current === currentToken) setSession(previous => previous ? { ...previous, user } : null)
  }, [request])

  useEffect(() => {
    if (!expiresAt) return
    const timeout = window.setTimeout(() => clear('Your session expired. Please sign in again.'), Math.max(0, expiresAt - Date.now()))
    return () => window.clearTimeout(timeout)
  }, [expiresAt, clear])

  useEffect(() => {
    if (!expiresAt) return
    const check = async () => {
      if (expiresAt <= Date.now()) { clear('Your session expired. Please sign in again.'); return }
      try {
        const currentToken = token.current
        const response = await fetch(`${base}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${currentToken}` }, signal: AbortSignal.timeout(5000) })
        if (currentToken !== token.current) return
        if (response.status === 401) clear('Your session is no longer valid. Please sign in again.')
        else if (response.ok) {
          const user: User = await response.json()
          setSession(previous => previous ? { ...previous, user } : null)
        }
      } catch { /* A temporary network error does not silently destroy a session. */ }
    }
    const onFocus = () => void check()
    window.addEventListener('focus', onFocus)
    const interval = window.setInterval(onFocus, 60_000)
    return () => { window.removeEventListener('focus', onFocus); window.clearInterval(interval) }
  }, [expiresAt, clear]) // Refresh current account permissions without persisting credentials.

  async function login(email: string, password: string) {
    const response = await fetch(`${base}/api/v1/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }), signal: AbortSignal.timeout(15_000),
    })
    if (!response.ok) {
      const error = await response.json().catch(() => null) as { message?: string } | null
      throw new Error(error?.message ?? 'Unable to sign in. Please try again.')
    }
    const result: { accessToken: string; expiresIn: number; user: User } = await response.json()
    token.current = result.accessToken
    queryClient.clear()
    setNotice('')
    setSession({ user: result.user, expiresAt: Date.now() + result.expiresIn * 1000 })
  }
  return <AuthContext.Provider value={{ session, notice, login, logout: () => clear(), request, refresh }}>{children}</AuthContext.Provider>
}

// oxlint-disable-next-line react/only-export-components -- colocated context hook, no persistent state outside React
export function useAuth() {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('AuthProvider is required')
  return auth
}
