import React from 'react';

interface TooltipProps {
  text: string;
  children?: React.ReactNode;
}

export const Tooltip: React.FC<TooltipProps> = ({ text, children }) => {
  return (
    <div className="tooltip-container">
      {children || <span className="tooltip-icon">ℹ️</span>}
      <div className="tooltip-content">
        {text}
      </div>
    </div>
  );
};
