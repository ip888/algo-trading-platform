import { useState, useEffect } from 'react';
import { AlertTriangle, ShieldCheck, ShieldAlert, Power, RotateCcw, PauseCircle, PlayCircle } from 'lucide-react';
import './SafetyStatus.css';
import { CONFIG } from '../config';

interface HeartbeatResponse {
    status: string;
    components: Record<string, number>; // values are seconds since last beat
}

interface PanicResult {
    status: string;
    success?: boolean;
    alpacaPositions?: Array<{ symbol: string; quantity: number; status: string }>;
    reason?: string;
}

export const SafetyStatus = () => {
    const [status, setStatus]               = useState<'ok' | 'critical' | 'unknown'>('unknown');
    const [components, setComponents]       = useState<Record<string, number>>({});
    const [showConfirm, setShowConfirm]     = useState(false);
    const [panicTriggered, setPanicTriggered] = useState(false);
    const [emergencyActive, setEmergencyActive] = useState(false);
    const [panicResult, setPanicResult]     = useState<PanicResult | null>(null);
    const [tradingPaused, setTradingPaused] = useState(false);

    useEffect(() => {
        const fetchStatus = async () => {
            try {
                const [hbRes, emRes, pauseRes] = await Promise.all([
                    fetch(`${CONFIG.API_BASE_URL}/api/heartbeat`),
                    fetch(`${CONFIG.API_BASE_URL}/api/emergency/status`),
                    fetch(`${CONFIG.API_BASE_URL}/api/trading/paused`),
                ]);

                if (hbRes.ok) {
                    const data: HeartbeatResponse = await hbRes.json();
                    // Backend returns seconds. Healthy = last beat within 120 seconds.
                    const isHealthy = Object.values(data.components).every(s => s < 120);
                    setStatus(isHealthy ? 'ok' : 'critical');
                    setComponents(data.components);
                } else {
                    setStatus('critical');
                }

                if (emRes.ok) {
                    const emData = await emRes.json();
                    setEmergencyActive(emData.triggered);
                }

                if (pauseRes.ok) {
                    const pauseData = await pauseRes.json();
                    setTradingPaused(pauseData.paused);
                }
            } catch {
                setStatus('critical');
            }
        };

        const interval = setInterval(fetchStatus, 2000);
        fetchStatus();
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

    const handlePauseToggle = async () => {
        try {
            const endpoint = tradingPaused ? '/api/trading/resume' : '/api/trading/pause';
            await fetch(`${CONFIG.API_BASE_URL}${endpoint}`, { method: 'POST' });
            setTradingPaused(prev => !prev);
        } catch (error) {
            console.error('Failed to toggle pause:', error);
        }
    };

    // Determine display state (emergency overrides pause overrides normal)
    const indicatorClass = emergencyActive ? 'emergency' : tradingPaused ? 'paused' : status;

    return (
        <div className="safety-status-container">
            {/* Heartbeat Indicator */}
            <div className={`heartbeat-indicator ${indicatorClass}`}>
                {emergencyActive ? (
                    <AlertTriangle size={20} className="icon-pulse-fast" />
                ) : tradingPaused ? (
                    <PauseCircle size={20} />
                ) : status === 'ok' ? (
                    <ShieldCheck size={20} className="icon-pulse-slow" />
                ) : (
                    <ShieldAlert size={20} className="icon-pulse-fast" />
                )}
                <span className="status-text">
                    {emergencyActive ? 'EMERGENCY ACTIVE'
                        : tradingPaused ? 'TRADING PAUSED'
                        : status === 'ok' ? 'SYSTEM SECURE'
                        : 'SYSTEM CRITICAL'}
                </span>

                {/* Tooltip: component health */}
                <div className="status-tooltip">
                    <h4>Component Health</h4>
                    {Object.entries(components).map(([name, secs]) => (
                        <div key={name} className="component-row">
                            <span className="name">{name}</span>
                            <span className={`latency ${secs < 10 ? 'good' : 'bad'}`}>
                                {secs}s ago
                            </span>
                        </div>
                    ))}
                    {Object.keys(components).length === 0 && (
                        <div className="no-data">No heartbeat data</div>
                    )}
                </div>
            </div>

            {/* Pause / Resume Button */}
            {!emergencyActive && (
                <button
                    className={`pause-button ${tradingPaused ? 'paused' : ''}`}
                    onClick={handlePauseToggle}
                    title={tradingPaused ? 'Resume trading (re-enable new entries)' : 'Pause trading (hold positions, no new entries)'}
                >
                    {tradingPaused
                        ? <><PlayCircle size={16} /> RESUME</>
                        : <><PauseCircle size={16} /> PAUSE</>}
                </button>
            )}

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
                    title="Emergency: cancel orders + flatten all positions"
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
                                        {pos.symbol}: {pos.quantity} shares — {pos.status}
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
