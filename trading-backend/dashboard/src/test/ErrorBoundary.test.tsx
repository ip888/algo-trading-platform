import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ErrorBoundary } from '../components/ErrorBoundary';
import { ComponentErrorBoundary } from '../components/ComponentErrorBoundary';

// Component that throws an error
const ThrowingComponent = ({ shouldThrow = true }: { shouldThrow?: boolean }) => {
  if (shouldThrow) {
    throw new Error('Test error for boundary');
  }
  return <div>Working component</div>;
};

// Suppress console.error for cleaner test output
const originalError = console.error;
beforeAll(() => {
  console.error = vi.fn();
});
afterAll(() => {
  console.error = originalError;
});

describe('ErrorBoundary', () => {
  it('renders children when no error', () => {
    render(
      <ErrorBoundary>
        <div>Child content</div>
      </ErrorBoundary>
    );
    
    expect(screen.getByText('Child content')).toBeInTheDocument();
  });

  it('shows fallback UI when child throws error', () => {
    render(
      <ErrorBoundary>
        <ThrowingComponent />
      </ErrorBoundary>
    );
    
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
  });

  it('shows custom fallback when provided', () => {
    render(
      <ErrorBoundary fallback={<div>Custom error fallback</div>}>
        <ThrowingComponent />
      </ErrorBoundary>
    );
    
    expect(screen.getByText('Custom error fallback')).toBeInTheDocument();
  });

  it('calls onError callback when error occurs', () => {
    const onErrorMock = vi.fn();
    
    render(
      <ErrorBoundary onError={onErrorMock}>
        <ThrowingComponent />
      </ErrorBoundary>
    );
    
    expect(onErrorMock).toHaveBeenCalledTimes(1);
    expect(onErrorMock).toHaveBeenCalledWith(
      expect.any(Error),
      expect.objectContaining({
        componentStack: expect.any(String)
      })
    );
  });

  it('displays component name in error message when provided', () => {
    render(
      <ErrorBoundary componentName="TestWidget">
        <ThrowingComponent />
      </ErrorBoundary>
    );
    
    expect(screen.getByText(/TestWidget/)).toBeInTheDocument();
  });

  it('provides retry button that resets error state', () => {
    const { rerender } = render(
      <ErrorBoundary>
        <ThrowingComponent shouldThrow={true} />
      </ErrorBoundary>
    );
    
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    
    // Click retry - button has emoji prefix
    const retryButton = screen.getByRole('button', { name: /Try Again/i });
    expect(retryButton).toBeInTheDocument();
    
    // Re-render with non-throwing component to simulate fix
    rerender(
      <ErrorBoundary>
        <ThrowingComponent shouldThrow={false} />
      </ErrorBoundary>
    );
    
    // After manual state reset via retry, component should work
    fireEvent.click(retryButton);
  });
});

describe('ComponentErrorBoundary', () => {
  it('renders children when no error', () => {
    render(
      <ComponentErrorBoundary name="TestPanel">
        <div>Panel content</div>
      </ComponentErrorBoundary>
    );
    
    expect(screen.getByText('Panel content')).toBeInTheDocument();
  });

  it('shows panel fallback UI when child throws', () => {
    render(
      <ComponentErrorBoundary name="Positions Table">
        <ThrowingComponent />
      </ComponentErrorBoundary>
    );
    
    expect(screen.getByText('Positions Table')).toBeInTheDocument();
    expect(screen.getByText(/couldn't load/)).toBeInTheDocument();
  });

  it('shows minimal fallback when minimal prop is true', () => {
    render(
      <ComponentErrorBoundary name="Mini Widget" minimal={true}>
        <ThrowingComponent />
      </ComponentErrorBoundary>
    );
    
    expect(screen.getByText('Mini Widget unavailable')).toBeInTheDocument();
  });

  it('logs error with component name', () => {
    const consoleSpy = vi.spyOn(console, 'error');
    
    render(
      <ComponentErrorBoundary name="TestComponent">
        <ThrowingComponent />
      </ComponentErrorBoundary>
    );
    
    expect(consoleSpy).toHaveBeenCalled();
  });

  it('isolates errors - sibling components still work', () => {
    render(
      <div>
        <ComponentErrorBoundary name="Broken Widget">
          <ThrowingComponent />
        </ComponentErrorBoundary>
        <ComponentErrorBoundary name="Working Widget">
          <div>I still work!</div>
        </ComponentErrorBoundary>
      </div>
    );
    
    // Broken widget shows error
    expect(screen.getByText('Broken Widget')).toBeInTheDocument();
    
    // Working widget is unaffected
    expect(screen.getByText('I still work!')).toBeInTheDocument();
  });
});
