export type FieldError = { field: string; message: string }

export class ApiError extends Error {
  status: number
  code: string
  fieldErrors: FieldError[]
  traceId?: string
  constructor(status: number, message: string, code = 'REQUEST_FAILED', fieldErrors: FieldError[] = [], traceId?: string) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.traceId = traceId
  }
}

export async function readResponse<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(response.status, body?.message ?? 'The request could not be completed.', body?.code, body?.fieldErrors, body?.traceId)
  return body as T
}
