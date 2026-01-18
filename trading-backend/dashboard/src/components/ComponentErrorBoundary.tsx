import type { ReactNode } from 'react';
import { ErrorBoundary } from './ErrorBoundary';

interface Props {
  children: ReactNode;
  name: string;
  minimal?: boolean;
}

/**
 * Lightweight error boundary wrapper for individual components.
 * 
 * Provides a minimal fallback UI that fits within panel layouts.
 * Use this to wrap individual dashboard widgets so one failure
 * doesn't crash the entire dashboard.
 * 
 * Usage:
 *   <ComponentErrorBoundary name="Positions Table">
 *     <PositionsTable />
 *   </ComponentErrorBoundary>
 */
export function ComponentErrorBoundary({ children, name, minimal = false }: Props) {
  const fallback = minimal ? (
    <MinimalFallback name={name} />
  ) : (
    <PanelFallback name={name} />
  );

  return (
    <ErrorBoundary 
      fallback={fallback}
      componentName={name}
      onError={(error) => {
        console.error(`[${name}] Component error:`, error.message);
      }}
    >
      {children}
    </ErrorBoundary>
  );
}

function MinimalFallback({ name }: { name: string }) {
  return (
    <div style={minimalStyles.container}>
      <span style={minimalStyles.icon}>⚠️</span>
      <span style={minimalStyles.text}>{name} unavailable</span>
    </div>
  );
}

function PanelFallback({ name }: { name: string }) {
  return (
    <div className="panel" style={panelStyles.container}>
      <div style={panelStyles.header}>
        <span style={panelStyles.icon}>⚠️</span>
        <span style={panelStyles.title}>{name}</span>
      </div>
      <div style={panelStyles.body}>
        <p style={panelStyles.message}>
          This component encountered an error and couldn't load.
        </p>
        <button 
          onClick={() => window.location.reload()}
          style={panelStyles.refreshButton}
        >
          Refresh Page
        </button>
      </div>
    </div>
  );
}

const minimalStyles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '8px 12px',
    background: 'rgba(255, 107, 107, 0.1)',
    borderRadius: '6px',
    border: '1px solid rgba(255, 107, 107, 0.2)',
  },
  icon: {
    fontSize: '14px',
  },
  text: {
    color: 'rgba(255, 255, 255, 0.6)',
    fontSize: '12px',
  },
};

const panelStyles: Record<string, React.CSSProperties> = {
  container: {
    minHeight: '150px',
    display: 'flex',
    flexDirection: 'column',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    marginBottom: '12px',
  },
  icon: {
    fontSize: '18px',
  },
  title: {
    color: 'rgba(255, 255, 255, 0.8)',
    fontSize: '14px',
    fontWeight: 500,
  },
  body: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '12px',
  },
  message: {
    color: 'rgba(255, 255, 255, 0.5)',
    fontSize: '13px',
    textAlign: 'center' as const,
    margin: 0,
  },
  refreshButton: {
    background: 'rgba(255, 255, 255, 0.1)',
    border: '1px solid rgba(255, 255, 255, 0.2)',
    borderRadius: '6px',
    color: 'rgba(255, 255, 255, 0.8)',
    padding: '8px 16px',
    fontSize: '12px',
    cursor: 'pointer',
  },
};

export default ComponentErrorBoundary;
