import React, { useEffect, useMemo, useState } from 'react'
import { CCard, CCardHeader, CCardBody, CAlert, CListGroup, CListGroupItem } from '@coreui/react'
import TimeSeriesChart from '../../components/charts/TimeSeriesChart'

const palette = ['#39f', '#f86c6b', '#20c997', '#ffa600', '#6f42c1']

export default function GCDashboard({ metricsSample }) {
  const [series, setSeries] = useState({})
  // previous cumulative totals per collector — used to compute deltas (time spent per interval)
  const prevTotals = React.useRef({})

  useEffect(() => {
    if (!metricsSample) {
      setSeries({})
      return
    }
    if (!metricsSample.parsed) {
      return
    }
    const entries = metricsSample.parsed['jvm_gc_collection_seconds_count'] || []
    if (entries.length === 0) {
      return
    }
    setSeries((prev) => {
      const next = { ...prev }
      entries.forEach((entry) => {
        const key = entry.labels.gc || entry.labels.name || 'gc'
        const prevVal = prevTotals.current[key]
        const delta = prevVal != null ? Math.max(0, entry.value - prevVal) : 0
        prevTotals.current[key] = entry.value
        const valueForChart = delta // show delta of cycles in the interval
        const existing = next[key] ? [...next[key]] : []
        existing.push({ x: metricsSample.timestamp, y: valueForChart })
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
        tension: 0.35,
        pointRadius: 0
      })),
    [series]
  )

  const collectorTotals = useMemo(() => {
    if (!metricsSample?.parsed) {
      return []
    }
    const totals = metricsSample.parsed['jvm_gc_collection_seconds_sum'] || []
    const counts = metricsSample.parsed['jvm_gc_collection_seconds_count'] || []
    return totals.map((entry) => {
      const countMatch = counts.find((c) => c.labels.gc === entry.labels.gc)
      return {
        collector: entry.labels.gc,
        time: entry.value,
        count: countMatch ? countMatch.value : null
      }
    })
  }, [metricsSample])

  return (
    <CCard>
      <CCardHeader>GC Cycles</CCardHeader>
      <CCardBody>
        {datasets.length === 0 ? (
          <CAlert color="light">No GC metrics yet. Select a running process with the agent attached.</CAlert>
        ) : (
          <TimeSeriesChart datasets={datasets} />
        )}
        {collectorTotals.length > 0 && (
          <CListGroup className="mt-3">
            {collectorTotals.map((row) => (
              <CListGroupItem key={row.collector} className="d-flex justify-content-between">
                <span>{row.collector}</span>
                <span>
                  {row.count ? `${row.count.toFixed(0)} cycles · ` : ''}
                  {row.time.toFixed(3)}s total
                </span>
              </CListGroupItem>
            ))}
          </CListGroup>
        )}
      </CCardBody>
    </CCard>
  )
}
