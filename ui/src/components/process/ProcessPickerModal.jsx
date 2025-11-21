import React, { useEffect, useMemo, useState } from 'react'
import {
  CModal,
  CModalHeader,
  CModalBody,
  CModalFooter,
  CButton,
  CFormInput,
  CListGroup,
  CListGroupItem,
  CAlert
} from '@coreui/react'

export default function ProcessPickerModal({ visible, onClose, jvms, onAttach, busy }) {
  const [agentJar, setAgentJar] = useState('agent/target/agent-0.1.0-SNAPSHOT.jar')
  const [agentArgs, setAgentArgs] = useState('')
  const [filter, setFilter] = useState('')

  useEffect(() => {
    if (!visible) {
      setFilter('')
    }
  }, [visible])

  const filtered = useMemo(() => {
    if (!filter) {
      return jvms
    }
    return jvms.filter((jvm) => jvm.displayName?.toLowerCase().includes(filter.toLowerCase()) || jvm.id.includes(filter))
  }, [jvms, filter])

  const handleAttach = (pid) => {
    onAttach({ pid, agentJar, agentArgs })
  }

  return (
    <CModal visible={visible} onClose={onClose} size="lg" backdrop="static">
      <CModalHeader closeButton>Attach Agent to Running JVM</CModalHeader>
      <CModalBody>
        <div className="row g-3 mb-2">
          <div className="col-md-6">
            <label className="form-label">Agent JAR</label>
            <CFormInput value={agentJar} onChange={(event) => setAgentJar(event.target.value)} disabled={busy} />
          </div>
          <div className="col-md-6">
            <label className="form-label">Agent Args (optional)</label>
            <CFormInput value={agentArgs} onChange={(event) => setAgentArgs(event.target.value)} disabled={busy} />
          </div>
        </div>
        <CFormInput
          placeholder="Filter by display name or PID"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          className="mb-3"
        />
        {filtered.length === 0 ? (
          <CAlert color="info">No JVMs detected. Launch a process or refresh.</CAlert>
        ) : (
          <CListGroup className="process-picker-list">
            {filtered.map((jvm) => (
              <CListGroupItem key={jvm.id} className="d-flex justify-content-between align-items-center">
                <div>
                  <strong>{jvm.displayName || 'Unknown JVM'}</strong>
                  <div className="text-muted small">PID {jvm.id}</div>
                </div>
                <CButton color="primary" size="sm" disabled={busy} onClick={() => handleAttach(jvm.id)}>
                  {busy ? 'Attaching...' : 'Attach Agent'}
                </CButton>
              </CListGroupItem>
            ))}
          </CListGroup>
        )}
      </CModalBody>
      <CModalFooter>
        <CButton color="secondary" onClick={onClose} disabled={busy}>
          Close
        </CButton>
      </CModalFooter>
    </CModal>
  )
}
