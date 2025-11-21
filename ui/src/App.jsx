import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { CAlert } from '@coreui/react'
import AppLayout from './components/layout/AppLayout'
import ProcessPickerModal from './components/process/ProcessPickerModal'
import ProcessLauncherModal from './components/process/ProcessLauncherModal'
import OverviewPanel from './features/overview/OverviewPanel'
import GCDashboard from './features/gc/GCDashboard'
import HeapSection from './features/heap/HeapSection'
import ProfilingPanel from './features/profiling/ProfilingPanel'
import LogPanel from './features/logs/LogPanel'
import {
  fetchProcesses,
  fetchLocalJvms,
  startManagedProcess,
  attachAgentToJvm
} from './api/processes'
import { useMetricsFeed } from './hooks/useMetricsFeed'
import { cilSpeedometer, cilLoopCircular, cilMemory, cilChartLine, cilList } from '@coreui/icons'

const SIDEBAR_ITEMS = [
  { href: '#overview', label: 'Overview', icon: cilSpeedometer },
  { href: '#gc', label: 'GC Metrics', icon: cilLoopCircular },
  { href: '#heap', label: 'Heap & Memory', icon: cilMemory },
  { href: '#profiling', label: 'Profiling', icon: cilChartLine },
  { href: '#logs', label: 'Logs', icon: cilList }
]

export default function App() {
  const [processes, setProcesses] = useState([])
  const [jvms, setJvms] = useState([])
  const [selectedProcessId, setSelectedProcessId] = useState(null)
  const [status, setStatus] = useState(null)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [launcherOpen, setLauncherOpen] = useState(false)
  const [launcherBusy, setLauncherBusy] = useState(false)
  const [attachBusy, setAttachBusy] = useState(false)

  const notify = useCallback((color, message) => {
    setStatus({ color, message, id: Date.now() })
  }, [])

  const loadProcesses = useCallback(async () => {
    try {
      const data = await fetchProcesses()
      setProcesses(data)
      setSelectedProcessId((current) => {
        if (current && data.some((proc) => proc.id === current)) {
          return current
        }
        return data[0]?.id ?? null
      })
    } catch (err) {
      notify('danger', `Failed to load processes: ${err.message}`)
    }
  }, [notify])

  const loadJvms = useCallback(async () => {
    try {
      const data = await fetchLocalJvms()
      setJvms(data)
    } catch (err) {
      notify('warning', `Unable to list JVMs: ${err.message}`)
    }
  }, [notify])

  useEffect(() => {
    loadProcesses()
  }, [loadProcesses])

  useEffect(() => {
    loadJvms()
    const timer = setInterval(loadJvms, 10000)
    return () => clearInterval(timer)
  }, [loadJvms])

  const selectedProcess = useMemo(
    () => processes.find((proc) => proc.id === selectedProcessId) || null,
    [processes, selectedProcessId]
  )

  const { sample: metricsSample, error: metricsError } = useMetricsFeed(selectedProcess ? selectedProcess.id : null)

  const handleLaunchProcess = async (payload) => {
    setLauncherBusy(true)
    try {
      await startManagedProcess(payload)
      notify('success', 'Process launch request submitted')
      setLauncherOpen(false)
      loadProcesses()
    } catch (err) {
      notify('danger', `Unable to start process: ${err.message}`)
    } finally {
      setLauncherBusy(false)
    }
  }

  const handleAttachAgent = async ({ pid, agentJar, agentArgs }) => {
    setAttachBusy(true)
    try {
      await attachAgentToJvm(pid, { agentJar, agentArgs })
      notify('success', `Agent attached to PID ${pid}`)
      setPickerOpen(false)
      loadJvms()
    } catch (err) {
      notify('danger', `Failed to attach agent: ${err.message}`)
    } finally {
      setAttachBusy(false)
    }
  }

  return (
    <>
      <AppLayout
        sidebarItems={SIDEBAR_ITEMS}
        processes={processes}
        selectedProcessId={selectedProcessId}
        onSelectProcess={setSelectedProcessId}
        onOpenProcessPicker={() => setPickerOpen(true)}
        onOpenProcessLauncher={() => setLauncherOpen(true)}
        onRefreshProcesses={loadProcesses}
      >
        {status ? (
          <CAlert color={status.color} dismissible onClose={() => setStatus(null)}>
            {status.message}
          </CAlert>
        ) : null}
        {metricsError ? <CAlert color="danger">{metricsError}</CAlert> : null}
        <section id="overview" className="app-section">
          <OverviewPanel
            processesCount={processes.length}
            selectedProcess={selectedProcess}
            metricsSample={metricsSample}
          />
        </section>
        <section id="gc" className="app-section">
          <GCDashboard metricsSample={metricsSample} />
        </section>
        <section id="heap" className="app-section">
          <HeapSection metricsSample={metricsSample} selectedProcess={selectedProcess} notify={notify} />
        </section>
        <section id="profiling" className="app-section">
          <ProfilingPanel selectedProcess={selectedProcess} notify={notify} />
        </section>
        <section id="logs" className="app-section">
          <LogPanel selectedProcess={selectedProcess} />
        </section>
      </AppLayout>
      <ProcessPickerModal
        visible={pickerOpen}
        onClose={() => setPickerOpen(false)}
        jvms={jvms}
        onAttach={handleAttachAgent}
        busy={attachBusy}
      />
      <ProcessLauncherModal
        visible={launcherOpen}
        onClose={() => setLauncherOpen(false)}
        onLaunch={handleLaunchProcess}
        busy={launcherBusy}
      />
    </>
  )
}
