import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
// CoreUI CSS
import '@coreui/coreui/dist/css/coreui.min.css'

createRoot(document.getElementById('root')).render(<App />)
