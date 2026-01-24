import { useState, useEffect } from 'react';
import { AlertTriangle, ShieldCheck, ShieldAlert, Power, RotateCcw } from 'lucide-react';
import './SafetyStatus.css';
import { CONFIG } from '../config';

interface HeartbeatResponse {
    status: string;
    components: Record<string, number>;
}

interface PanicResult {
    status: string;
    success?: boolean;
    alpacaPositions?: Array<{ symbol: string; quantity: number; status: string }>;
    reason?: string;
}

export const SafetyStatus = () => {
    const [status, setStatus] = useState<'ok' | 'critical' | 'unknown'>('unknown');
    const [components, setComponents] = useState<Record<string, number>>({});
    const [showConfirm, setShowConfirm] = useState(false);
    const [panicTriggered, setPanicTriggered] = useState(false);
    const [emergencyActive, setEmergencyActive] = useState(false);
    const [panicResult, setPanicResult] = useState<PanicResult | null>(null);

    useEffect(() => {
        const fetchHeartbeat = async () => {
            try {
                const response = await fetch(`${CONFIG.API_BASE_URL}/api/heartbeat`);
                if (response.ok) {
                    const data: HeartbeatResponse = await response.json();
                    const isHealthy = Object.values(data.components).every(ms => ms < 120000); 
                    setStatus(isHealthy ? 'ok' : 'critical');
                    setComponents(data.components);
                } else {
                    setStatus('critical');
                }
                
                // Also check emergency status
                const emergencyRes = await fetch(`${CONFIG.API_BASE_URL}/api/emergency/status`);
                if (emergencyRes.ok) {
                    const emergencyData = await emergencyRes.json();
                    setEmergencyActive(emergencyData.triggered);
                }
            } catch {
                setStatus('critical');
            }
        };

        const interval = setInterval(fetchHeartbeat, 2000);
        fetchHeartbeat();
        return () => clearInterval(interval);
    }, []);

    const handlePanic = async () => {
        try {
            const response = await fetch(`${CONFIG.API_BASE_URL}/api/emergency/panic?reason=manual_ui`, { method: 'POST' });
            const result: PanicResult = await response.json();
            setPanicResult(result);
            setPanicTriggered(true);
            setEmergencyActive(true);
            setShowConfirm(false);
        } catch (error) {
            console.error('Failed to trigger panic:', error);
            alert('FAILED TO TRIGGER PANIC: CHECK CONSOLE');
        }
    };
    
    const handleReset = async () => {
        try {
            await fetch(`${CONFIG.API_BASE_URL}/api/emergency/reset`, { method: 'POST' });
            setEmergencyActive(false);
            setPanicTriggered(false);
            setPanicResult(null);
        } catch (error) {
            console.error('Failed to reset emergency:', error);
            alert('FAILED TO RESET: CHECK CONSOLE');
        }
    };

    return (
        <div className="safety-status-container">
            {/* Heartbeat Indicator */}
            <div className={`heartbeat-indicator ${emergencyActive ? 'emergency' : status}`}>
                {emergencyActive ? (
                    <AlertTriangle size={20} className="icon-pulse-fast" />
                ) : status === 'ok' ? (
                    <ShieldCheck size={20} className="icon-pulse-slow" />
                ) : (
                    <ShieldAlert size={20} className="icon-pulse-fast" />
                )}
                <span className="status-text">
                    {emergencyActive ? 'EMERGENCY ACTIVE' : status === 'ok' ? 'SYSTEM SECURE' : 'SYSTEM CRITICAL'}
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

            {/* Panic / Reset Buttons */}
            {emergencyActive ? (
                <button 
                    className="reset-button"
                    onClick={handleReset}
                    title="Reset Emergency Protocol"
                >
                    <RotateCcw size={16} /> RESET
                </button>
            ) : !showConfirm ? (
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
            
            {panicTriggered && panicResult && (
                <div className="panic-overlay" onClick={() => setPanicTriggered(false)}>
                    <div className="panic-message" onClick={(e) => e.stopPropagation()}>
                        <AlertTriangle size={48} />
                        <h2>EMERGENCY PROTOCOL {panicResult.success ? 'EXECUTED' : 'FAILED'}</h2>
                        
                        {panicResult.alpacaPositions && panicResult.alpacaPositions.length > 0 && (
                            <div className="positions-closed">
                                <h4>Alpaca Positions:</h4>
                                {panicResult.alpacaPositions.map((pos, i) => (
                                    <div key={i} className={`position-item ${pos.status === 'close_ordered' ? 'success' : 'failed'}`}>
                                        {pos.symbol}: {pos.quantity} shares - {pos.status}
                                    </div>
                                ))}
                            </div>
                        )}
                        
                        {(!panicResult.alpacaPositions?.length) && (
                            <p>No open positions to close.</p>
                        )}
                        
                        <button className="dismiss-btn" onClick={() => setPanicTriggered(false)}>
                            DISMISS
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
};
