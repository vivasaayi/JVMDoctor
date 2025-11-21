import React, { useEffect, useState } from 'react'
import {
  CModal,
  CModalHeader,
  CModalBody,
  CModalFooter,
  CButton,
  CFormInput,
  CFormTextarea
} from '@coreui/react'

const defaultState = {
  jarPath: '/Users/rajanpanneerselvam/JVMDoctor/sample-app/target/sample-app-0.1.0-SNAPSHOT-jar-with-dependencies.jar',
  agentJar: '/Users/rajanpanneerselvam/JVMDoctor/agent/target/agent-0.1.0-SNAPSHOT.jar',
  agentPort: 9404,
  jvmArgs: '-Xmx256m',
  envVars: ''
}

const createDefaultState = () => ({ ...defaultState })

function parseEnvVars(envText) {
  return envText
    .split(/\s+/)
    .map((token) => token.trim())
    .filter(Boolean)
    .map((pair) => {
      const idx = pair.indexOf('=')
      if (idx === -1) {
        return null
      }
      return { key: pair.substring(0, idx), value: pair.substring(idx + 1) }
    })
    .filter(Boolean)
}

export default function ProcessLauncherModal({ visible, onClose, onLaunch, busy }) {
  const [form, setForm] = useState(createDefaultState)

  useEffect(() => {
    if (!visible) {
      setForm(createDefaultState())
    }
  }, [visible])

  const handleChange = (field) => (event) => {
    setForm((prev) => ({ ...prev, [field]: event.target.value }))
  }

  const handleLaunch = () => {
    const payload = {
      jarPath: form.jarPath,
      agentJar: form.agentJar,
      agentPort: Number(form.agentPort),
      args: form.jvmArgs.split(/\s+/).filter(Boolean),
      envVars: parseEnvVars(form.envVars)
    }
    onLaunch(payload)
  }

  return (
    <CModal visible={visible} onClose={onClose} backdrop="static">
      <CModalHeader closeButton>Launch Managed Process</CModalHeader>
      <CModalBody>
        <div className="mb-3">
          <label className="form-label">Jar Path</label>
          <CFormInput value={form.jarPath} onChange={handleChange('jarPath')} disabled={busy} />
        </div>
        <div className="row g-3 mb-3">
          <div className="col-md-6">
            <label className="form-label">Agent Jar</label>
            <CFormInput value={form.agentJar} onChange={handleChange('agentJar')} disabled={busy} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Agent Port</label>
            <CFormInput type="number" value={form.agentPort} onChange={handleChange('agentPort')} disabled={busy} />
          </div>
          <div className="col-md-3">
            <label className="form-label">JVM Args</label>
            <CFormInput value={form.jvmArgs} onChange={handleChange('jvmArgs')} disabled={busy} />
          </div>
        </div>
        <div>
          <label className="form-label">Environment Variables</label>
          <CFormTextarea
            placeholder="EXAMPLE_KEY=value OTHER=value"
            value={form.envVars}
            onChange={handleChange('envVars')}
            disabled={busy}
          />
        </div>
      </CModalBody>
      <CModalFooter>
        <CButton color="secondary" onClick={onClose} disabled={busy}>
          Cancel
        </CButton>
        <CButton color="success" onClick={handleLaunch} disabled={busy}>
          {busy ? 'Starting...' : 'Start Process'}
        </CButton>
      </CModalFooter>
    </CModal>
  )
}
