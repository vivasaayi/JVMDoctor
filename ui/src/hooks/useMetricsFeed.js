import { useEffect, useState } from 'react'
import { fetchProcessMetrics } from '../api/processes'
import { parsePrometheus } from '../utils/prometheus'

export function useMetricsFeed(processId, intervalMs = 4000) {
  const [sample, setSample] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!processId) {
      setSample(null)
      setError(null)
      setLoading(false)
      return undefined
    }
    let active = true
    let timerId = null

    const poll = async () => {
      if (!active) return
      setLoading(true)
      try {
        const raw = await fetchProcessMetrics(processId)
        if (!active) return
        setSample({ raw, parsed: parsePrometheus(raw), timestamp: Date.now() })
        setError(null)
      } catch (err) {
        if (active) {
          setError(err.message)
        }
      } finally {
        if (active) {
          setLoading(false)
        }
      }
      if (active) {
        timerId = window.setTimeout(poll, intervalMs)
      }
    }

    poll()
    return () => {
      active = false
      if (timerId) {
        window.clearTimeout(timerId)
      }
    }
  }, [processId, intervalMs])

  return { sample, error, loading }
}
