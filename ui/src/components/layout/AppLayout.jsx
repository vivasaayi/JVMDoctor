import React, { useState } from 'react'
import {
  CSidebar,
  CSidebarBrand,
  CSidebarNav,
  CNavItem,
  CButton,
  CFormSelect
} from '@coreui/react'
import CIcon from '@coreui/icons-react'

export default function AppLayout({
  sidebarItems,
  processes,
  selectedProcessId,
  onSelectProcess,
  onOpenProcessPicker,
  onOpenProcessLauncher,
  onRefreshProcesses,
  children
}) {
  const [sidebarVisible, setSidebarVisible] = useState(true)

  return (
    <div className="app-shell d-flex">
      <CSidebar
        className="app-sidebar"
        visible={sidebarVisible}
        onVisibleChange={(visible) => setSidebarVisible(visible)}
      >
        <CSidebarBrand href="#overview">JVMDoctor</CSidebarBrand>
        <CSidebarNav>
          {sidebarItems.map((item) => (
            <CNavItem key={item.href} href={item.href} className="d-flex align-items-center">
              {item.icon ? <CIcon icon={item.icon} className="me-2" /> : null}
              {item.label}
            </CNavItem>
          ))}
        </CSidebarNav>
      </CSidebar>
      <div className="flex-grow-1 d-flex flex-column">
        <header className="app-header border-bottom bg-white">
          <div className="d-flex flex-wrap gap-2">
            <CButton color="light" onClick={() => setSidebarVisible((v) => !v)}>
              {sidebarVisible ? 'Hide Menu' : 'Show Menu'}
            </CButton>
            <CButton color="primary" onClick={onOpenProcessPicker}>
              Attach Existing JVM
            </CButton>
            <CButton color="success" onClick={onOpenProcessLauncher}>
              Launch Managed Process
            </CButton>
            <CButton color="light" onClick={onRefreshProcesses}>
              Refresh
            </CButton>
          </div>
          <div className="d-flex align-items-center gap-2">
            <small className="text-muted">Inspecting</small>
            <CFormSelect
              className="w-auto"
              value={selectedProcessId ?? ''}
              onChange={(event) => onSelectProcess(event.target.value ? Number(event.target.value) : null)}
            >
              <option value="">Select a process</option>
              {processes.map((proc) => (
                <option key={proc.id} value={proc.id}>
                  {proc.jar} (pid {proc.pid})
                </option>
              ))}
            </CFormSelect>
          </div>
        </header>
        <main className="flex-grow-1 app-main">{children}</main>
      </div>
    </div>
  )
}
