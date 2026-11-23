export function getApiErrorMessage(error: unknown, fallback: string): string {
  const data = (error as { response?: { data?: { detail?: unknown; message?: unknown } } } | null)
    ?.response?.data;

  if (typeof data?.detail === 'string' && data.detail.trim()) {
    return data.detail;
  }
  if (typeof data?.message === 'string' && data.message.trim()) {
    return data.message;
  }
  return fallback;
}
