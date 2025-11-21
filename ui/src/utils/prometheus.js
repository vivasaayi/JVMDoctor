export function parsePrometheus(text = '') {
  const metrics = {}
  if (!text) {
    return metrics
  }
  const lines = text.split('\n')
  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) {
      continue
    }
    const lastSpace = line.lastIndexOf(' ')
    if (lastSpace < 0) {
      continue
    }
    const metricPart = line.substring(0, lastSpace)
    const valuePart = line.substring(lastSpace + 1)
    const value = Number(valuePart)
    if (Number.isNaN(value)) {
      continue
    }
    let name = metricPart
    let labels = {}
    const labelStart = metricPart.indexOf('{')
    if (labelStart !== -1) {
      const labelEnd = metricPart.lastIndexOf('}')
      name = metricPart.substring(0, labelStart)
      const labelString = metricPart.substring(labelStart + 1, labelEnd)
      if (labelString.trim().length > 0) {
        const labelPairs = labelString.split(',')
        labels = labelPairs.reduce((acc, token) => {
          const [k, v] = token.split('=')
          if (k && v) {
            acc[k.trim()] = v.replace(/^"|"$/g, '')
          }
          return acc
        }, {})
      }
    }
    if (!metrics[name]) {
      metrics[name] = []
    }
    metrics[name].push({ labels, value })
  }
  return metrics
}

export function firstMetricValue(parsedMetrics, name, predicate) {
  const entries = parsedMetrics?.[name]
  if (!entries || entries.length === 0) {
    return null
  }
  if (!predicate) {
    return entries[0].value
  }
  const match = entries.find((entry) => predicate(entry.labels))
  return match ? match.value : null
}

export function sumMetric(parsedMetrics, name, predicate) {
  const entries = parsedMetrics?.[name]
  if (!entries) {
    return 0
  }
  return entries
    .filter((entry) => (predicate ? predicate(entry.labels) : true))
    .reduce((acc, entry) => acc + entry.value, 0)
}
