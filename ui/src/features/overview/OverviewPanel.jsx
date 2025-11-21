import React, { useEffect, useMemo, useState } from 'react'
import { CRow, CCol, CCard, CCardHeader, CCardBody, CAlert, CBadge } from '@coreui/react'
import TimeSeriesChart from '../../components/charts/TimeSeriesChart'
import { firstMetricValue } from '../../utils/prometheus'
import { formatBytes } from '../../utils/format'

const palette = ['#321fdb']

export default function OverviewPanel({ processesCount, selectedProcess, metricsSample }) {
  const [threadSeries, setThreadSeries] = useState([])
  const [cpuSeries, setCpuSeries] = useState([])
  const prevCpuTime = React.useRef(0)

  useEffect(() => {
    if (!metricsSample) {
      setThreadSeries([])
      return
    }
    if (!metricsSample.parsed) {
      return
    }
    const value =
      firstMetricValue(metricsSample.parsed, 'jvm_threads_current') ??
      firstMetricValue(metricsSample.parsed, 'jvmdoctor_thread_count')
    if (value === null || value === undefined) {
      return
    }
    setThreadSeries((prev) => [...prev, { x: metricsSample.timestamp, y: value }].slice(-120))
  }, [metricsSample])

  useEffect(() => {
    if (!metricsSample) {
      setCpuSeries([])
      return
    }
    if (!metricsSample.parsed) {
      return
    }
    const value = firstMetricValue(metricsSample.parsed, 'process_cpu_seconds_total')
    if (value === null || value === undefined) {
      return
    }
    const delta = value - prevCpuTime.current
    prevCpuTime.current = value
    if (delta > 0) {
      setCpuSeries((prev) => [...prev, { x: metricsSample.timestamp, y: delta }].slice(-120))
    }
  }, [metricsSample])

  const liveThreads = useMemo(() => {
    if (!metricsSample?.parsed) {
      return null
    }
    return (
      firstMetricValue(metricsSample.parsed, 'jvm_threads_current') ??
      firstMetricValue(metricsSample.parsed, 'jvmdoctor_thread_count')
    )
  }, [metricsSample])

  const cpuProcessSeconds = useMemo(() => {
    if (!metricsSample?.parsed) {
      return null
    }
    return firstMetricValue(metricsSample.parsed, 'process_cpu_seconds_total')
  }, [metricsSample])

  const rssBytes = useMemo(() => {
    if (!metricsSample?.parsed) {
      return null
    }
    return firstMetricValue(metricsSample.parsed, 'process_resident_memory_bytes')
  }, [metricsSample])

  return (
    <div>
      <CRow className="g-4">
        <CCol lg={4}>
          <CCard>
            <CCardHeader>Process Summary</CCardHeader>
            <CCardBody>
              {selectedProcess ? (
                <div>
                  <div className="mb-2">
                    <strong>{selectedProcess.jar}</strong>
                    <div className="text-muted small">PID {selectedProcess.pid}</div>
                    <div className="text-muted small">Agent Port {selectedProcess.port}</div>
                  </div>
                  <CBadge color="primary">{processesCount} active</CBadge>
                </div>
              ) : (
                <CAlert color="info" className="mb-0">
                  No managed process selected.
                </CAlert>
              )}
            </CCardBody>
          </CCard>
        </CCol>
        <CCol lg={4}>
          <CCard>
            <CCardHeader>Runtime Snapshot</CCardHeader>
            <CCardBody>
              {selectedProcess ? (
                <ul className="list-unstyled mb-0">
                  <li>
                    <span className="text-muted">Threads:</span> {liveThreads ?? 'N/A'}
                  </li>
                  <li>
                    <span className="text-muted">CPU Time:</span>{' '}
                    {cpuProcessSeconds ? `${cpuProcessSeconds.toFixed(1)}s` : 'N/A'}
                  </li>
                  <li>
                    <span className="text-muted">RSS:</span> {formatBytes(rssBytes)}
                  </li>
                </ul>
              ) : (
                <CAlert color="light" className="mb-0">
                  Pick a process to inspect metrics.
                </CAlert>
              )}
            </CCardBody>
          </CCard>
        </CCol>
        <CCol lg={4}>
          <CCard>
            <CCardHeader>Thread Activity</CCardHeader>
            <CCardBody>
              {threadSeries.length === 0 ? (
                <CAlert color="light" className="mb-0">
                  Waiting for metrics…
                </CAlert>
              ) : (
                <TimeSeriesChart
                  datasets={[
                    {
                      label: 'Live Threads',
                      data: threadSeries,
                      borderColor: palette[0],
                      tension: 0.3,
                      pointRadius: 0
                    }
                  ]}
                  options={{ scales: { y: { beginAtZero: true } } }}
                />
              )}
            </CCardBody>
          </CCard>
        </CCol>
        <CCol lg={4}>
          <CCard>
            <CCardHeader>CPU Activity</CCardHeader>
            <CCardBody>
              {cpuSeries.length === 0 ? (
                <CAlert color="light" className="mb-0">
                  Waiting for metrics…
                </CAlert>
              ) : (
                <TimeSeriesChart
                  datasets={[
                    {
                      label: 'CPU Seconds Delta',
                      data: cpuSeries,
                      borderColor: '#f86c6b',
                      tension: 0.3,
                      pointRadius: 0
                    }
                  ]}
                  options={{ scales: { y: { beginAtZero: true } } }}
                />
              )}
            </CCardBody>
          </CCard>
        </CCol>
      </CRow>
    </div>
  )
}
