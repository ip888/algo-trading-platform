import { Component, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

/**
 * Global App Error Boundary - catches any unhandled errors in the entire app.
 * 
 * Shows a full-page fallback with:
 * - Error details
 * - Recovery options
 * - Links to reconnect
 */
export class AppErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      hasError: false,
      error: null
    };
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    console.error('🚨 CRITICAL: App-level error caught');
    console.error('Error:', error);
    console.error('Stack:', errorInfo.componentStack);
  }

  handleReload = (): void => {
    window.location.reload();
  };

  handleClearAndReload = (): void => {
    // Clear any cached state that might cause the error
    localStorage.clear();
    sessionStorage.clear();
    window.location.reload();
  };

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div style={styles.fullPage}>
          <div style={styles.card}>
            <div style={styles.iconLarge}>🚨</div>
            <h1 style={styles.title}>Trading Dashboard Error</h1>
            <p style={styles.subtitle}>
              The dashboard encountered an unexpected error and needs to restart.
            </p>
            
            {this.state.error && (
              <div style={styles.errorBox}>
                <div style={styles.errorLabel}>Error Details:</div>
                <code style={styles.errorCode}>{this.state.error.message}</code>
              </div>
            )}

            <div style={styles.actions}>
              <button onClick={this.handleReload} style={styles.primaryButton}>
                🔄 Reload Dashboard
              </button>
              <button onClick={this.handleClearAndReload} style={styles.secondaryButton}>
                🧹 Clear Cache & Reload
              </button>
            </div>

            <div style={styles.tips}>
              <h4 style={styles.tipsTitle}>Troubleshooting Tips:</h4>
              <ul style={styles.tipsList}>
                <li>Check if the backend server is running</li>
                <li>Verify WebSocket connection on port 8080</li>
                <li>Try clearing browser cache if issue persists</li>
              </ul>
            </div>

            <div style={styles.footer}>
              <span style={styles.version}>Alpaca Global Finance v6.0</span>
              <span style={styles.separator}>•</span>
              <span style={styles.timestamp}>
                Error at: {new Date().toLocaleTimeString()}
              </span>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

const styles: Record<string, React.CSSProperties> = {
  fullPage: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: 'linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%)',
    padding: '20px',
  },
  card: {
    background: 'rgba(26, 26, 46, 0.95)',
    border: '1px solid rgba(255, 107, 107, 0.3)',
    borderRadius: '16px',
    padding: '40px',
    maxWidth: '500px',
    width: '100%',
    textAlign: 'center' as const,
    boxShadow: '0 20px 60px rgba(0, 0, 0, 0.5)',
  },
  iconLarge: {
    fontSize: '64px',
    marginBottom: '20px',
  },
  title: {
    color: '#ff6b6b',
    fontSize: '24px',
    fontWeight: 700,
    margin: '0 0 12px 0',
  },
  subtitle: {
    color: 'rgba(255, 255, 255, 0.7)',
    fontSize: '15px',
    margin: '0 0 24px 0',
    lineHeight: 1.6,
  },
  errorBox: {
    background: 'rgba(255, 107, 107, 0.1)',
    border: '1px solid rgba(255, 107, 107, 0.2)',
    borderRadius: '8px',
    padding: '16px',
    marginBottom: '24px',
    textAlign: 'left' as const,
  },
  errorLabel: {
    color: 'rgba(255, 255, 255, 0.5)',
    fontSize: '12px',
    marginBottom: '8px',
  },
  errorCode: {
    color: '#ff6b6b',
    fontSize: '13px',
    wordBreak: 'break-word' as const,
  },
  actions: {
    display: 'flex',
    gap: '12px',
    marginBottom: '24px',
    flexWrap: 'wrap' as const,
    justifyContent: 'center',
  },
  primaryButton: {
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    border: 'none',
    borderRadius: '8px',
    color: 'white',
    padding: '12px 24px',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'pointer',
    flex: 1,
    minWidth: '180px',
  },
  secondaryButton: {
    background: 'transparent',
    border: '1px solid rgba(255, 255, 255, 0.2)',
    borderRadius: '8px',
    color: 'rgba(255, 255, 255, 0.8)',
    padding: '12px 24px',
    fontSize: '14px',
    fontWeight: 500,
    cursor: 'pointer',
    flex: 1,
    minWidth: '180px',
  },
  tips: {
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '8px',
    padding: '16px',
    marginBottom: '20px',
    textAlign: 'left' as const,
  },
  tipsTitle: {
    color: 'rgba(255, 255, 255, 0.8)',
    fontSize: '13px',
    margin: '0 0 12px 0',
  },
  tipsList: {
    color: 'rgba(255, 255, 255, 0.6)',
    fontSize: '12px',
    margin: 0,
    paddingLeft: '20px',
    lineHeight: 1.8,
  },
  footer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '8px',
    color: 'rgba(255, 255, 255, 0.4)',
    fontSize: '11px',
  },
  version: {},
  separator: {},
  timestamp: {},
};

export default AppErrorBoundary;
