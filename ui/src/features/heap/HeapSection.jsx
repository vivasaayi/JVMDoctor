import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  CRow,
  CCol,
  CCard,
  CCardHeader,
  CCardBody,
  CAlert,
  CButton,
  CTable,
  CTableHead,
  CTableRow,
  CTableHeaderCell,
  CTableBody,
  CTableDataCell,
  CFormInput
} from '@coreui/react'
import TimeSeriesChart from '../../components/charts/TimeSeriesChart'
import { formatBytes } from '../../utils/format'
import { sumMetric } from '../../utils/prometheus'
import { fetchHeapHistogram, requestHeapDump } from '../../api/processes'

const palette = ['#20c997', '#39f', '#f9b115', '#f86c6b', '#6f42c1']

export default function HeapSection({ metricsSample, selectedProcess, notify }) {
  return (
    <div>
      <CRow className="g-4">
        <CCol lg={7}>
          <HeapUsageChart metricsSample={metricsSample} />
        </CCol>
        <CCol lg={5}>
          <HeapSummary metricsSample={metricsSample} />
        </CCol>
      </CRow>
      <CRow className="g-4 mt-1">
        <CCol lg={6}>
          <HeapDumpPanel selectedProcess={selectedProcess} notify={notify} />
        </CCol>
        <CCol lg={6}>
          <HeapHistogramPanel selectedProcess={selectedProcess} notify={notify} />
        </CCol>
      </CRow>
    </div>
  )
}

function HeapUsageChart({ metricsSample }) {
  const [series, setSeries] = useState({})

  useEffect(() => {
    if (!metricsSample) {
      setSeries({})
      return
    }
    if (!metricsSample.parsed) {
      return
    }
    const used = metricsSample.parsed['jvm_memory_bytes_used'] || []
    const heapEntries = used.filter((entry) => entry.labels.area === 'heap')
    if (heapEntries.length === 0) {
      return
    }
    setSeries((prev) => {
      const next = { ...prev }
      heapEntries.forEach((entry) => {
        const key = entry.labels.id || entry.labels.pool || entry.labels.area
        const existing = next[key] ? [...next[key]] : []
        existing.push({ x: metricsSample.timestamp, y: entry.value })
        next[key] = existing.slice(-180)
      })
      return next
    })
  }, [metricsSample])

  const datasets = useMemo(
    () =>
      Object.entries(series).map(([label, data], index) => ({
        label,
        data,
        borderColor: palette[index % palette.length],
        tension: 0.3,
        pointRadius: 0
      })),
    [series]
  )

  return (
    <CCard>
      <CCardHeader>Heap Usage</CCardHeader>
      <CCardBody>
        {datasets.length === 0 ? (
          <CAlert color="light">Waiting for heap metrics…</CAlert>
        ) : (
          <TimeSeriesChart datasets={datasets} />
        )}
      </CCardBody>
    </CCard>
  )
}

function HeapSummary({ metricsSample }) {
  const used = useMemo(() => sumMetric(metricsSample?.parsed, 'jvm_memory_bytes_used', (labels) => labels.area === 'heap'), [
    metricsSample
  ])
  const committed = useMemo(
    () => sumMetric(metricsSample?.parsed, 'jvm_memory_bytes_committed', (labels) => labels.area === 'heap'),
    [metricsSample]
  )
  const max = useMemo(() => sumMetric(metricsSample?.parsed, 'jvm_memory_bytes_max', (labels) => labels.area === 'heap'), [
    metricsSample
  ])

  return (
    <CCard>
      <CCardHeader>Heap Summary</CCardHeader>
      <CCardBody>
        {metricsSample ? (
          <ul className="list-unstyled mb-0">
            <li>
              <strong>Used:</strong> {formatBytes(used)}
            </li>
            <li>
              <strong>Committed:</strong> {formatBytes(committed)}
            </li>
            <li>
              <strong>Max:</strong> {formatBytes(max)}
            </li>
          </ul>
        ) : (
          <CAlert color="light" className="mb-0">
            Select a process to populate heap metrics.
          </CAlert>
        )}
      </CCardBody>
    </CCard>
  )
}

function HeapDumpPanel({ selectedProcess, notify }) {
  const [filename, setFilename] = useState('')
  const [pending, setPending] = useState(false)
  const [lastDump, setLastDump] = useState(null)

  useEffect(() => {
    if (selectedProcess) {
      setFilename(`/tmp/heapdump-${selectedProcess.pid}.hprof`)
    } else {
      setFilename('')
    }
    setLastDump(null)
  }, [selectedProcess])

  const handleDump = async () => {
    if (!selectedProcess) {
      return
    }
    setPending(true)
    try {
      const response = await requestHeapDump(selectedProcess.id, { filename })
      setLastDump(response.path)
      notify('success', `Heap dump created at ${response.path}`)
    } catch (err) {
      // backend often returns JSON with error/hint fields — try to parse and show helpful hint
      let message = err.message || String(err)
      try {
        const parsed = typeof message === 'string' ? JSON.parse(message) : message
        if (parsed && parsed.error) {
          let hint = parsed.hint ? ` — ${parsed.hint}` : ''
          message = `${parsed.error}${hint}`
        }
      } catch (e) {
        // not JSON — show raw message
      }
      notify('danger', `Heap dump failed: ${message}`)
    } finally {
      setPending(false)
    }
  }

  return (
    <CCard>
      <CCardHeader>Heap Dumps</CCardHeader>
      <CCardBody>
        {!selectedProcess ? (
          <CAlert color="info">Select a process to take a heap dump.</CAlert>
        ) : (
          <div>
            <label className="form-label">Output File</label>
            <CFormInput value={filename} onChange={(event) => setFilename(event.target.value)} disabled={pending} />
            <CButton color="danger" className="mt-3" onClick={handleDump} disabled={pending}>
              {pending ? 'Dumping…' : 'Take Heap Dump'}
            </CButton>
            {lastDump && (
              <CAlert color="success" className="mt-3">
                <a href={`/api/files/download?path=${encodeURIComponent(lastDump)}`} target="_blank" rel="noreferrer">
                  Download last heap dump
                </a>
              </CAlert>
            )}
          </div>
        )}
      </CCardBody>
    </CCard>
  )
}

function HeapHistogramPanel({ selectedProcess, notify }) {
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const processId = selectedProcess ? selectedProcess.id : null

  const loadHistogram = useCallback(async () => {
    if (!processId) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      const data = await fetchHeapHistogram(processId, 25)
      setRows(data.entries || [])
      setError(null)
    } catch (err) {
      // backend may return structured JSON with error/hint — try to parse it so UI shows helpful guidance
      let message = err.message || String(err)
      try {
        const parsed = typeof message === 'string' ? JSON.parse(message) : message
        if (parsed && parsed.error) {
          message = parsed.error + (parsed.hint ? ` — ${parsed.hint}` : '')
        }
      } catch (e) {
        // keep original message
      }
      setError(message)
      notify('warning', `Heap histogram failed: ${message}`)
    } finally {
      setLoading(false)
    }
  }, [processId, notify])

  useEffect(() => {
    setRows([])
    setError(null)
    if (processId) {
      loadHistogram()
    }
  }, [processId, loadHistogram])

  return (
    <CCard>
      <CCardHeader className="d-flex justify-content-between align-items-center">
        <span>Heap Analysis (Top Classes)</span>
        <CButton color="light" size="sm" disabled={!processId || loading} onClick={loadHistogram}>
          Refresh
        </CButton>
      </CCardHeader>
      <CCardBody>
        {!processId ? (
          <CAlert color="info">Select a process to inspect class histograms.</CAlert>
        ) : loading ? (
          <CAlert color="light">Collecting histogram…</CAlert>
        ) : error ? (
          <CAlert color="danger">{error}</CAlert>
        ) : rows.length === 0 ? (
          <CAlert color="light">Histogram did not return data.</CAlert>
        ) : (
          <CTable small hover responsive>
            <CTableHead>
              <CTableRow>
                <CTableHeaderCell>#</CTableHeaderCell>
                <CTableHeaderCell>Class</CTableHeaderCell>
                <CTableHeaderCell>Instances</CTableHeaderCell>
                <CTableHeaderCell>Bytes</CTableHeaderCell>
              </CTableRow>
            </CTableHead>
            <CTableBody>
              {rows.map((row) => (
                <CTableRow key={row.rank}>
                  <CTableDataCell>{row.rank}</CTableDataCell>
                  <CTableDataCell className="text-truncate" title={row.className}>
                    {row.className}
                  </CTableDataCell>
                  <CTableDataCell>{row.instances.toLocaleString()}</CTableDataCell>
                  <CTableDataCell>{formatBytes(row.bytes)}</CTableDataCell>
                </CTableRow>
              ))}
            </CTableBody>
          </CTable>
        )}
      </CCardBody>
    </CCard>
  )
}
