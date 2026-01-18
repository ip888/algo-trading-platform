import { Component, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
  onError?: (error: Error, errorInfo: React.ErrorInfo) => void;
  componentName?: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorInfo: React.ErrorInfo | null;
}

/**
 * React Error Boundary for graceful error handling.
 * 
 * Modern best practices:
 * - Catches JavaScript errors in child component tree
 * - Logs errors for debugging
 * - Shows fallback UI instead of crashing
 * - Supports recovery via retry button
 * 
 * Usage:
 *   <ErrorBoundary>
 *     <ComponentThatMightError />
 *   </ErrorBoundary>
 */
export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null
    };
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    this.setState({ errorInfo });
    
    // Log error details
    console.error('🚨 ErrorBoundary caught error:', error);
    console.error('Component stack:', errorInfo.componentStack);
    
    // Call optional error handler
    this.props.onError?.(error, errorInfo);
    
    // In production, you might send to error tracking service
    // logErrorToService(error, errorInfo);
  }

  handleRetry = (): void => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null
    });
  };

  render(): ReactNode {
    if (this.state.hasError) {
      // Custom fallback provided
      if (this.props.fallback) {
        return this.props.fallback;
      }

      // Default fallback UI
      return (
        <div style={styles.container}>
          <div style={styles.card}>
            <div style={styles.icon}>⚠️</div>
            <h2 style={styles.title}>Something went wrong</h2>
            <p style={styles.message}>
              {this.props.componentName 
                ? `The ${this.props.componentName} component encountered an error.`
                : 'An unexpected error occurred in this section.'}
            </p>
            {this.state.error && (
              <pre style={styles.errorDetail}>
                {this.state.error.message}
              </pre>
            )}
            <button 
              onClick={this.handleRetry}
              style={styles.retryButton}
            >
              🔄 Try Again
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

// Inline styles for error boundary (no external CSS dependency)
const styles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '20px',
    minHeight: '200px',
  },
  card: {
    background: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 100%)',
    border: '1px solid rgba(255, 107, 107, 0.3)',
    borderRadius: '12px',
    padding: '24px',
    textAlign: 'center' as const,
    maxWidth: '400px',
    boxShadow: '0 4px 20px rgba(0, 0, 0, 0.3)',
  },
  icon: {
    fontSize: '48px',
    marginBottom: '16px',
  },
  title: {
    color: '#ff6b6b',
    fontSize: '18px',
    fontWeight: 600,
    margin: '0 0 12px 0',
  },
  message: {
    color: 'rgba(255, 255, 255, 0.7)',
    fontSize: '14px',
    margin: '0 0 16px 0',
    lineHeight: 1.5,
  },
  errorDetail: {
    background: 'rgba(255, 107, 107, 0.1)',
    border: '1px solid rgba(255, 107, 107, 0.2)',
    borderRadius: '6px',
    padding: '12px',
    fontSize: '12px',
    color: '#ff6b6b',
    textAlign: 'left' as const,
    overflow: 'auto',
    maxHeight: '100px',
    marginBottom: '16px',
  },
  retryButton: {
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    border: 'none',
    borderRadius: '8px',
    color: 'white',
    padding: '10px 24px',
    fontSize: '14px',
    fontWeight: 500,
    cursor: 'pointer',
    transition: 'transform 0.2s, box-shadow 0.2s',
  },
};

export default ErrorBoundary;
