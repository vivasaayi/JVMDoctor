import React, { useEffect, useState } from 'react'
import { CCard, CCardHeader, CCardBody, CAlert, CFormInput, CFormSelect, CButton } from '@coreui/react'
import { runAsyncProfilerRequest } from '../../api/processes'

export default function ProfilingPanel({ selectedProcess, notify }) {
  const [form, setForm] = useState({ duration: 20, event: 'cpu', output: 'svg', filename: '' })
  const [pending, setPending] = useState(false)
  const [profilePath, setProfilePath] = useState(null)

  useEffect(() => {
    if (selectedProcess) {
      setForm((prev) => ({ ...prev, filename: `/tmp/profile-${selectedProcess.pid}.svg` }))
    } else {
      setProfilePath(null)
    }
  }, [selectedProcess])

  const handleChange = (field) => (event) => {
    const value = event.target.value
    setForm((prev) => ({ ...prev, [field]: field === 'duration' ? Number(value) : value }))
  }

  const handleProfile = async () => {
    if (!selectedProcess) {
      return
    }
    setPending(true)
    try {
      const body = {
        duration: Number(form.duration),
        event: form.event,
        output: form.output,
        filename: form.filename
      }
      const result = await runAsyncProfilerRequest(selectedProcess.id, body)
      notify('success', 'Profiler started. Refresh after it finishes to view the flamegraph.')
      if (result.path) {
        setProfilePath(result.path)
      }
    } catch (err) {
      // If backend returns a helpful JSON error, parse it and show hint
      let message = err.message || String(err)
      try {
        const parsed = typeof message === 'string' ? JSON.parse(message) : message
        if (parsed && parsed.error) {
          message = parsed.error
          if (parsed.hint) message += ` — ${parsed.hint}`
        }
      } catch (e) {
        // not JSON — use original message
      }
      if (message && message.toLowerCase().includes('async-profiler')) {
        notify('danger', `Profiler not available: ${message}. Install async-profiler and set ASYNC_PROFILER_HOME environment variable for the backend process. See README for installation steps.`)
      } else {
        notify('danger', `Profiler failed: ${message}`)
      }
    } finally {
      setPending(false)
    }
  }

  const flamegraphUrl = profilePath ? `/api/files/download?path=${encodeURIComponent(profilePath)}` : null

  return (
    <CCard>
      <CCardHeader>CPU Profiling & Flamegraph</CCardHeader>
      <CCardBody>
        {!selectedProcess ? (
          <CAlert color="info">Select a managed process to run async-profiler.</CAlert>
        ) : (
          <div className="row g-3 align-items-end mb-3">
            <div className="col-md-2">
              <label className="form-label">Duration (s)</label>
              <CFormInput type="number" value={form.duration} onChange={handleChange('duration')} disabled={pending} />
            </div>
            <div className="col-md-3">
              <label className="form-label">Event</label>
              <CFormSelect value={form.event} onChange={handleChange('event')} disabled={pending}>
                <option value="cpu">CPU</option>
                <option value="alloc">Allocation</option>
                <option value="lock">Locks</option>
              </CFormSelect>
            </div>
            <div className="col-md-3">
              <label className="form-label">Output</label>
              <CFormSelect value={form.output} onChange={handleChange('output')} disabled={pending}>
                <option value="svg">SVG</option>
                <option value="html">HTML</option>
              </CFormSelect>
            </div>
            <div className="col-md-4">
              <label className="form-label">Output File</label>
              <CFormInput value={form.filename} onChange={handleChange('filename')} disabled={pending} />
            </div>
            <div className="col-12">
              <CButton color="primary" onClick={handleProfile} disabled={pending}>
                {pending ? 'Profiling…' : 'Capture CPU Profile'}
              </CButton>
            </div>
          </div>
        )}
        {flamegraphUrl ? (
          <div className="flamegraph-viewer">
            <iframe title="flamegraph" src={flamegraphUrl} frameBorder="0" />
          </div>
        ) : (
          <CAlert color="light">Run a profile to display the flamegraph inline.</CAlert>
        )}
      </CCardBody>
    </CCard>
  )
}
