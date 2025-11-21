import React, { useState, useEffect, useRef } from 'react'
import Chart from 'chart.js/auto'
import 'chartjs-adapter-date-fns'
import { Container, Grid, Paper, Typography, TextField, Button, Checkbox, FormControlLabel, MenuItem, Select } from '@mui/material'

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
    await fetch('/api/processes/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({jarPath, agentJar, agentPort, args})})
    refresh()
  }

  async function fetchMetrics(force = true){
    if (!selected) return
    const resp = await fetch('/api/processes/' + selected + '/metrics')
    const text = await resp.text()
    // parse prometheus metrics
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
    // open SSE
    const sse = new EventSource('/api/processes/' + id + '/logs/stream')
    sse.onmessage = (e) => {
      const el = document.getElementById('log')
      el.innerText += e.data + '\n'
      el.scrollTop = el.scrollHeight
    }
    // load existing logs
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
      // add file link
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

  return (<Container sx={{paddingTop:4}}>
    <h1>JVMDoctor React UI</h1>

    <Paper sx={{p:2, mb:2}}>
      <Typography variant='h6'>Launch a JAR</Typography>
      <Grid container spacing={2} alignItems='center'>
        <Grid item xs={12}><TextField id='jarPath' label='Jar Path' defaultValue='sample-app/target/sample-app-0.1.0-SNAPSHOT-jar-with-dependencies.jar' fullWidth/></Grid>
        <Grid item xs={12}><TextField id='agentJar' label='Agent JAR' defaultValue='agent/target/agent-0.1.0-SNAPSHOT.jar' fullWidth/></Grid>
        <Grid item xs={6}><TextField id='agentPort' label='Agent Port' defaultValue='9404' fullWidth/></Grid>
        <Grid item xs={6}><TextField id='jvmArgs' label='JVM Args' defaultValue='-Xmx256m' fullWidth/></Grid>
        <Grid item><Button variant='contained' onClick={startJar}>Start</Button></Grid>
      </Grid>
    </Paper>

    <h2>Running processes</h2>
    <div>
      <Button variant='outlined' onClick={refresh}>Refresh</Button>
      <ul>
        {processes.map(p => <li key={p.id}>{p.jar} - port:{p.port}
          <Button onClick={()=>{setSelected(p.id)}}>Select</Button>
          <Button onClick={()=>openLogs(p.id)}>Logs</Button>
          <Button onClick={()=>toggle(p.id, true)}>Enable sample</Button>
          <Button onClick={()=>toggle(p.id, false)}>Disable sample</Button>
          <Button onClick={async () => {
              const resp = await fetch('/api/processes/' + p.id + '/history');
              const body = await resp.json();
              alert(JSON.stringify(body, null, 2));
            }}>History</Button>
        </li>)}
      </ul>
    </div>

    <h2>Local JVMs (attach)</h2>
    <div>
      <TextField id='agentJarAttach' defaultValue='agent/target/agent-0.1.0-SNAPSHOT.jar' sx={{width:'60%'}}/>
      <ul>
        {jvms.map(j => <li key={j.id}>{j.id} - {j.displayName} <button onClick={()=>attachAgent(j.id)}>Attach agent</button></li>)}
      </ul>
    </div>

    <Paper sx={{p:2, mt:2}}>
      <Typography variant='h6'>Metrics</Typography>
      <div>
        <Button variant='outlined' onClick={fetchMetrics}>Fetch metrics</Button>
        <canvas ref={chartRef} width={800} height={160} />
      </div>
    </Paper>

    {selected && (<Paper sx={{p:2, mt:2}}>
      <h3>Live controls for process {selected}</h3>
      <div style={{display:'flex', gap:8, alignItems: 'center'}}>
        <input id='jfrName' placeholder='JFR name' />
        <input id='jfrMax' placeholder='Max age (ms)' />
        <button onClick={()=>startJfr(selected)}>Start JFR</button>
        <input id='jfrFile' placeholder='dump.jfr' defaultValue={'/tmp/jvmdoctor-'+selected+'.jfr'} />
        <button onClick={()=>stopJfr(selected)}>Stop + Dump JFR</button>
      </div>
      <div style={{display:'flex', gap:8, marginTop:8, alignItems:'center'}}>
        <input id='profDuration' placeholder='Duration (s)' defaultValue='10' />
        <input id='profEvent' placeholder='Event' defaultValue='cpu' />
        <input id='profOutput' placeholder='Output' defaultValue='svg' />
        <input id='profFile' placeholder='file.svg' defaultValue={'/tmp/profile-'+selected+'.svg'} />
        <button onClick={()=>runProfiler(selected)}>Run profiler</button>
        <input id='nativePath' placeholder='native lib path (optional)' style={{width:240}} />
        <button onClick={async ()=>{
          const path = document.getElementById('nativePath').value
          const resp = await fetch('/api/processes/'+selected+'/native/load', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({path}) })
          const data = await resp.json()
          alert('Native load: ' + JSON.stringify(data))
        }}>Load native</button>
      </div>
    </Paper>)}

    <Paper sx={{p:2, mt:2}}>
      <Typography variant='h6'>Log</Typography>
      <div style={{display:'flex', gap:8, alignItems:'center', marginTop:8}}>
        <TextField id='logFilter' placeholder='Filter (substring or regex)' />
        <FormControlLabel control={<Checkbox id='regexToggle' />} label='Regex' />
        <FormControlLabel control={<Checkbox id='caseToggle' defaultChecked />} label='Case sensitive' />
        <Button onClick={() => fetchLogs(selected, document.getElementById('logFilter').value, document.getElementById('regexToggle').checked, !document.getElementById('caseToggle').checked)}>Apply</Button>
        <Button onClick={() => fetchLogs(selected, '', false, true)}>Clear</Button>
      </div>
      <pre id='log' style={{height:200, overflow:'auto', background:'#eee', padding:12}}></pre>
      {jfrPath && (<div style={{marginTop:8}}>JFR: <a href={'/api/files/download?path=' + encodeURIComponent(jfrPath)}>Download</a></div>)}
      {profilePath && (<div style={{marginTop:8}}>Profile: <a href={'/api/files/download?path=' + encodeURIComponent(profilePath)}>Download</a>
        {profilePath.endsWith('.svg') && (<div style={{marginTop:8}}><img src={'/api/files/download?path=' + encodeURIComponent(profilePath)} alt='profile' style={{width:'100%'}}/></div>)}
      </div>)}
    </Paper>
  </Container>)
}
