import { useState, useEffect } from 'react';
import { AlertTriangle, ShieldCheck, ShieldAlert, Power } from 'lucide-react';
import './SafetyStatus.css';
import { CONFIG } from '../config';

interface HeartbeatResponse {
    status: string;
    components: Record<string, number>;
}

export const SafetyStatus = () => {
    const [status, setStatus] = useState<'ok' | 'critical' | 'unknown'>('unknown');
    const [components, setComponents] = useState<Record<string, number>>({});
    const [showConfirm, setShowConfirm] = useState(false);
    const [panicTriggered, setPanicTriggered] = useState(false);

    useEffect(() => {
        const fetchHeartbeat = async () => {
            try {
                const response = await fetch(`${CONFIG.API_BASE_URL}/api/heartbeat`);
                if (response.ok) {
                    const data: HeartbeatResponse = await response.json();
                    
                    // Check individual component health (assuming < 120s is healthy for now)
                    // You might want to get this logic from the backend in the future
                    const isHealthy = Object.values(data.components).every(ms => ms < 120000); 
                    
                    setStatus(isHealthy ? 'ok' : 'critical');
                    setComponents(data.components);
                } else {
                    setStatus('critical');
                }
            } catch (error) {
                setStatus('critical');
            }
        };

        const interval = setInterval(fetchHeartbeat, 2000);
        fetchHeartbeat();
        return () => clearInterval(interval);
    }, []);

    const handlePanic = async () => {
        try {
            await fetch(`${CONFIG.API_BASE_URL}/api/emergency/panic?reason=manual_ui`, { method: 'POST' });
            setPanicTriggered(true);
            setTimeout(() => setPanicTriggered(false), 5000);
            setShowConfirm(false);
        } catch (error) {
            console.error('Failed to trigger panic:', error);
            alert('FAILED TO TRIGGER PANIC: CHECK CONSOLE');
        }
    };

    return (
        <div className="safety-status-container">
            {/* Heartbeat Indicator */}
            <div className={`heartbeat-indicator ${status}`}>
                {status === 'ok' ? (
                    <ShieldCheck size={20} className="icon-pulse-slow" />
                ) : (
                    <ShieldAlert size={20} className="icon-pulse-fast" />
                )}
                <span className="status-text">
                    {status === 'ok' ? 'SYSTEM SECURE' : 'SYSTEM CRITICAL'}
                </span>
                
                {/* Tooltip for component details */}
                <div className="status-tooltip">
                    <h4>Component Health</h4>
                    {Object.entries(components).map(([name, ms]) => (
                        <div key={name} className="component-row">
                            <span className="name">{name}</span>
                            <span className={`latency ${ms < 5000 ? 'good' : 'bad'}`}>
                                {ms < 1000 ? `${ms}ms` : `${(ms/1000).toFixed(1)}s`}
                            </span>
                        </div>
                    ))}
                    {Object.keys(components).length === 0 && (
                        <div className="no-data">No heartbeat data</div>
                    )}
                </div>
            </div>

            {/* Panic Button */}
            {!showConfirm ? (
                <button 
                    className={`panic-button ${status === 'critical' ? 'suggested' : ''}`}
                    onClick={() => setShowConfirm(true)}
                    title="Emergency Flatten All Positions"
                >
                    <Power size={16} /> PANIC
                </button>
            ) : (
                <div className="panic-confirm">
                    <span className="confirm-text">CONFIRM FLATTEN?</span>
                    <button className="confirm-yes" onClick={handlePanic}>YES</button>
                    <button className="confirm-no" onClick={() => setShowConfirm(false)}>NO</button>
                </div>
            )}
            
            {panicTriggered && (
                <div className="panic-overlay">
                    <div className="panic-message">
                        <AlertTriangle size={48} />
                        <h2>EMERGENCY PROTOCOL INITIATED</h2>
                        <p>Flattening all positions...</p>
                    </div>
                </div>
            )}
        </div>
    );
};
