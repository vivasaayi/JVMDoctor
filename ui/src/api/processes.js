async function handleResponse(response, parser = 'json') {
  if (!response.ok) {
    const message = await response.text()
    const error = message || response.statusText
    throw new Error(error)
  }
  if (parser === 'text') {
    return response.text()
  }
  return response.json()
}

export async function fetchProcesses() {
  const resp = await fetch('/api/processes')
  return handleResponse(resp)
}

export async function fetchLocalJvms() {
  const resp = await fetch('/api/processes/jvms')
  return handleResponse(resp)
}

export async function startManagedProcess(payload) {
  const resp = await fetch('/api/processes/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  return handleResponse(resp)
}

export async function attachAgentToJvm(pid, payload) {
  const resp = await fetch(`/api/processes/jvms/${pid}/attach`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  return handleResponse(resp)
}

export async function fetchProcessMetrics(id) {
  const resp = await fetch(`/api/processes/${id}/metrics`)
  return handleResponse(resp, 'text')
}

export async function requestHeapDump(id, body) {
  const resp = await fetch(`/api/processes/${id}/heapdump`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return handleResponse(resp)
}

export async function fetchHeapHistogram(id, limit = 25) {
  const resp = await fetch(`/api/processes/${id}/heap/histogram?limit=${limit}`)
  return handleResponse(resp)
}

export async function runAsyncProfilerRequest(id, body) {
  const resp = await fetch(`/api/processes/${id}/profiler/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return handleResponse(resp)
}

export async function fetchProcessLogs(id, params = {}) {
  if (!id) {
    return { lines: [] }
  }
  const searchParams = new URLSearchParams()
  if (params.q) {
    searchParams.set('q', params.q)
  }
  if (params.limit) {
    searchParams.set('limit', String(params.limit))
  }
  const suffix = searchParams.toString() ? `?${searchParams.toString()}` : ''
  const resp = await fetch(`/api/processes/${id}/logs${suffix}`)
  return handleResponse(resp)
}
