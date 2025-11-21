import React, { useEffect, useRef } from 'react'
import Chart from 'chart.js/auto'
import 'chartjs-adapter-date-fns'

const baseOptions = {
  responsive: true,
  animation: false,
  interaction: { mode: 'nearest', intersect: false },
  scales: {
    x: { type: 'time', time: { unit: 'second' }, ticks: { autoSkip: true } },
    y: { beginAtZero: false }
  },
  plugins: {
    legend: { display: true }
  }
}

export default function TimeSeriesChart({ datasets, options }) {
  const canvasRef = useRef(null)
  const chartRef = useRef(null)

  useEffect(() => {
    if (!canvasRef.current || chartRef.current) {
      return undefined
    }
    chartRef.current = new Chart(canvasRef.current, {
      type: 'line',
      data: { datasets: datasets || [] },
      options: { ...baseOptions, ...(options || {}) }
    })
    return () => {
      if (chartRef.current) {
        chartRef.current.destroy()
        chartRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    if (!chartRef.current || !datasets) {
      return
    }
    chartRef.current.data.datasets = datasets
    chartRef.current.update('none')
  }, [datasets])

  useEffect(() => {
    if (!chartRef.current || !options) {
      return
    }
    chartRef.current.options = { ...baseOptions, ...options }
    chartRef.current.update('none')
  }, [options])

  return <canvas ref={canvasRef} />
}
