//! Dashboard module - Trading bot web interface
//!
//! Provides a single-page dashboard for monitoring the trading bot.
//! Separated into HTML, CSS, and JS submodules for maintainability.
//!
//! # Architecture
//! - `html.rs`: Page structure and layout
//! - `css.rs`: Styling with CSS custom properties
//! - `js.rs`: API calls, UI updates, user interactions
//!
//! # Features
//! - Real-time portfolio value and P&L
//! - Position tracking with dynamic TP/SL
//! - Market scan with signal indicators
//! - 30-second auto-refresh

mod css;
mod html;
mod js;

/// Generate the complete dashboard HTML page
pub fn dashboard_html() -> String {
    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Coinbase Trading Bot</title>
    <style>
{css}
    </style>
</head>
<body>
{html}
    <script>
{js}
    </script>
</body>
</html>"#,
        css = css::STYLES,
        html = html::TEMPLATE,
        js = js::SCRIPT
    )
}
