import React, { useState, useEffect, useRef } from 'react'
import Chart from 'chart.js/auto'
import 'chartjs-adapter-date-fns'
import {
  CContainer,
  CRow,
  CCol,
  CCard,
  CCardBody,
  CCardHeader,
  CButton,
  CFormInput,
  CListGroup,
  CListGroupItem,
  CAlert
} from '@coreui/react'

export default function App(){
  const [processes, setProcesses] = useState([])
  const [jvms, setJvms] = useState([])
  const [selected, setSelected] = useState(null)
  const [jfrPath, setJfrPath] = useState(null)
  const [profilePath, setProfilePath] = useState(null)
  const chartRef = useRef(null)
  const chartInstance = useRef(null)
  const dataPoints = useRef([])

  useEffect(() => { refresh() }, [])
  useEffect(() => {
    let handle = null
    if (selected) {
      handle = setInterval(() => { fetchMetrics(false) }, 2000)
    }
    return () => { if (handle) clearInterval(handle) }
  }, [selected])

  async function refresh(){
    const resp = await fetch('/api/processes')
    setProcesses(await resp.json())
    const jresp = await fetch('/api/processes/jvms')
    setJvms(await jresp.json())
  }

  async function startJar(){
    const jarPath = document.getElementById('jarPath').value
    const agentJar = document.getElementById('agentJar').value
    const agentPort = parseInt(document.getElementById('agentPort').value)
    const args = document.getElementById('jvmArgs').value.split(/\s+/).filter(x=>x)
    const envVars = document.getElementById('envVars').value.split(/\s+/).filter(x=>x).map(s => {
      const [k,v] = s.split('=')
      return {key: k, value: v}
    })
    await fetch('/api/processes/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({jarPath, agentJar, agentPort, args, envVars})})
    refresh()
  }

  async function attachAgentToJvm(pid){
    const agentJar = document.getElementById('agentJarAttach').value
    await fetch('/api/processes/jvms/' + pid + '/attach', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({agentJar}) })
    alert('Agent attached to JVM ' + pid)
    refresh()
  }

  async function fetchMetrics(force = true){
    if (!selected) return
    const resp = await fetch('/api/processes/' + selected + '/metrics')
    const text = await resp.text()
    const lines = text.split('\n')
    for(const line of lines){
      if(line.startsWith('jvmdoctor_thread_count')){
        const v = parseFloat(line.split(' ').pop())
        dataPoints.current.push({x: Date.now(), y: v})
      }
    }
    if (!chartInstance.current){
      const ctx = chartRef.current.getContext('2d')
      chartInstance.current = new Chart(ctx, {
        type: 'line',
        data: { datasets: [{ label:'threads', data: dataPoints.current }] },
        options: { scales: { x: { type:'time', time: { unit:'second' } }, y: { beginAtZero:true } } }
      })
    } else {
      chartInstance.current.data.datasets[0].data = dataPoints.current.slice(-60)
      chartInstance.current.update()
    }
  }

  async function attachAgent(pid){
    const agentJar = document.getElementById('agentJarAttach').value
    await fetch('/api/processes/jvms/' + pid + '/attach', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({agentJar}) })
    alert('attach attempted')
  }

  async function fetchLogs(id, q, regex = false, caseSensitive = true) {
    let params = [];
    if (q) params.push('q=' + encodeURIComponent(q));
    if (regex) params.push('regex=true');
    if (!caseSensitive) params.push('ignoreCase=true');
    const resp = await fetch('/api/processes/' + id + '/logs' + (params.length ? ('?' + params.join('&')) : ''))
    const body = await resp.json()
    document.getElementById('log').innerText = body.lines.join('\n')
    document.getElementById('log').scrollTop = document.getElementById('log').scrollHeight
  }

  function openLogs(id){
    const sse = new EventSource('/api/processes/' + id + '/logs/stream')
    sse.onmessage = (e) => {
      const el = document.getElementById('log')
      el.innerText += e.data + '\n'
      el.scrollTop = el.scrollHeight
    }
    fetchLogs(id)
  }

  async function toggle(id, enable){
    await fetch('/api/processes/' + id + '/toggle', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enable}) })
    alert('toggle sent')
  }

  async function startJfr(id){
    const name = document.getElementById('jfrName').value
    const maxAge = parseInt(document.getElementById('jfrMax').value)
    const resp = await fetch('/api/processes/' + id + '/jfr/start', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({name, maxAgeMillis: maxAge}) })
    const data = await resp.json()
    if (!resp.ok) alert('JFR start failed: ' + JSON.stringify(data))
    else alert('JFR started')
  }

  async function stopJfr(id){
    const filename = document.getElementById('jfrFile').value
    const resp = await fetch('/api/processes/' + id + '/jfr/stop', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({filename}) })
    const data = await resp.json()
    if (!resp.ok) alert('JFR stop failed: ' + JSON.stringify(data))
    else {
      alert('JFR dumped to ' + data.path)
      setJfrPath(data.path)
      const a = document.createElement('a')
      a.href = '/api/files/download?path=' + encodeURIComponent(data.path)
      a.innerText = 'Download JFR'
      a.target = '_blank'
      document.getElementById('log').appendChild(document.createElement('div')).appendChild(a)
    }
  }

  async function runProfiler(id){
    const duration = parseInt(document.getElementById('profDuration').value)
    const event = document.getElementById('profEvent').value
    const output = document.getElementById('profOutput').value
    const filename = document.getElementById('profFile').value
    const resp = await fetch('/api/processes/' + id + '/profiler/run', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({duration, event, output, filename}) })
    const data = await resp.json()
    if (!resp.ok) alert('Profiler run failed: ' + JSON.stringify(data))
    else {
      alert('Profiler queued. File expected at: ' + data.path)
      setProfilePath(data.path)
      const a = document.createElement('a')
      a.href = '/api/files/download?path=' + encodeURIComponent(data.path)
      a.innerText = 'Download profile'
      a.target = '_blank'
      document.getElementById('log').appendChild(document.createElement('div')).appendChild(a)
    }
  }

  return (
    <CContainer className="pt-4">
      <h1>JVMDoctor React UI (CoreUI)</h1>

      <CCard className="mb-3">
        <CCardHeader>Launch a JAR</CCardHeader>
        <CCardBody>
          <CRow>
            <CCol xs={8}><CFormInput id="jarPath" placeholder="Path to your JAR" defaultValue='sample-app/target/sample-app-0.1.0-SNAPSHOT-jar-with-dependencies.jar' /></CCol>
            <CCol xs={4}><CFormInput id="agentJar" placeholder="Agent JAR" defaultValue='agent/target/agent-0.1.0-SNAPSHOT.jar' /></CCol>
            <CCol xs={6}><CFormInput id="agentPort" placeholder="Agent Port" defaultValue='9404'/></CCol>
            <CCol xs={6}><CFormInput id="jvmArgs" placeholder="JVM Args" defaultValue='-Xmx256m'/></CCol>
            <CCol xs={12}><CFormInput id="envVars" placeholder="Env Vars (KEY=VALUE KEY2=VALUE2)" /></CCol>
            <CCol xs={12}><CButton color="primary" onClick={startJar}>Start JAR</CButton></CCol>
          </CRow>
        </CCardBody>
      </CCard>

      <CRow>
        <CCol xs={6}>
          <CCard>
            <CCardHeader>Running Processes</CCardHeader>
            <CCardBody>
              <CListGroup>
                {processes.map(p => (
                  <CListGroupItem key={p.id} action onClick={() => setSelected(p.id)}>
                    {p.jar} <small className="text-muted">(pid:{p.port})</small>
                    <CButton size='sm' color='secondary' style={{float:'right'}} onClick={(e)=>{e.stopPropagation(); attachAgent(p.id)}}>Attach</CButton>
                  </CListGroupItem>
                ))}
              </CListGroup>
            </CCardBody>
          </CCard>
        </CCol>
        <CCol xs={6}>
          <CCard>
            <CCardHeader>Local JVMs (Attach Agent)</CCardHeader>
            <CCardBody>
              <CListGroup>
                {jvms.map(j => (
                  <CListGroupItem key={j.id}>
                    {j.displayName} <small className="text-muted">(pid:{j.id})</small>
                    <CButton size='sm' color='primary' style={{float:'right'}} onClick={() => attachAgentToJvm(j.id)}>Attach Agent</CButton>
                  </CListGroupItem>
                ))}
              </CListGroup>
            </CCardBody>
          </CCard>
        </CCol>
      </CRow>

      <CRow>
        <CCol xs={9}>
          <CCard>
            <CCardHeader>Metrics</CCardHeader>
            <CCardBody>
              <canvas ref={chartRef} />
              {selected ? <div>Selected: {selected}</div> : <CAlert color="info">Select a process to show metrics</CAlert>}
            </CCardBody>
          </CCard>
        </CCol>
        <CCol xs={3}>
          <CCard>
            <CCardHeader>Controls</CCardHeader>
            <CCardBody>
              <CFormInput id="agentJarAttach" defaultValue='agent/target/agent-0.1.0-SNAPSHOT.jar' placeholder="Agent JAR for attach" />
              <div style={{marginTop:8}}><textarea id='log' style={{width:'100%',height:200}} placeholder="Logs will appear here"></textarea></div>
            </CCardBody>
          </CCard>
        </CCol>
      </CRow>
    </CContainer>
  )
}
