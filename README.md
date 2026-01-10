# ⚡ Alpaca Bot: Autonomous High-Frequency Trading System

**Enterprise-grade algorithmic trading engine built on Java 25 (Virtual Threads) and React 19.**

![Status](https://img.shields.io/badge/Status-Production-green?style=flat-square)
![Java](https://img.shields.io/badge/Java-25%20LTS-orange?style=flat-square)
![Architecture](https://img.shields.io/badge/Architecture-Event--Driven-blue?style=flat-square)
![Frontend](https://img.shields.io/badge/Frontend-React%20%7C%20Vite-blueviolet?style=flat-square)
![Native Image](https://img.shields.io/badge/GraalVM-Native%20Image%20Ready-orange?style=flat-square)

## 📖 Executive Summary
This project represents a **full-stack, high-concurrency trading platform** designed for the Alpaca Securities API. Unlike typical retail bots usually written in Python, this system leverages **Java 25's Virtual Threads (Project Loom)** to handle thousands of concurrent market data streams with microsecond-level overhead. It features a self-healing "Watchdog" architecture and a real-time Command & Control dashboard.

## 🏗 System Architecture

The system follows a **modern/hybrid microservice architecture**, separating the execution core from the observability layer.

```mermaid
graph TD
    subgraph "Execution Plane (GCP Cloud Run)"
        JavaCore["☕ Java 25 Core"]
        VirtualThreads["🧵 Virtual Thread Pool"]
        Watchdog["🐕 Reliability Watchdog"]
        Strategy["🧠 Strategy Engine"]
        
        JavaCore --> VirtualThreads
        VirtualThreads --> Strategy
        JavaCore --> Watchdog
    end

    subgraph "Data Plane"
        AlpacaAPI["📡 Alpaca Market Data API"]
        AlpacaTrade["💸 Alpaca Trading API"]
    end

    subgraph "Control Plane (Cloudflare Edge)"
        Dashboard["🖥️ React Command Center"]
        State["⚡ Real-Time WebSocket"]
    end

    JavaCore <-->|REST/WSS| AlpacaAPI
    JavaCore <-->|REST| AlpacaTrade
    JavaCore <-->|Secure WSS| Dashboard
```

## 🛠 Technology Stack Breakdown

### 🧠 Backend: The Execution Core
*   **Language:** Java 25 (LTS) - Selected for strict typing and performance.
*   **Concurrency:** **Virtual Threads** (Project Loom) instead of Reactive Streams. This allows blocking I/O (API calls) to be written in a simple imperative style while maintaining non-blocking runtime characteristics.
*   **Framework:** **Javalin** - Extremely lightweight web framework (sub-1ms overhead).
*   **Resilience:** Custom "Watchdog" implementation that monitors heartbeat, memory pressure, and API latency, capable of "Emergency Flattening" positions if critical thresholds are breached.

### 💻 Frontend: Command & Control
*   **Framework:** React 19 + TypeScript + Vite.
*   **State Management:** **Zustand** for lightweight, atomic state updates.
*   **Visualization:**
    *   **Lightweight Charts (v5):** For high-performance financial timeseries rendering (used in Backtest Lab).
    *   **Recharts:** For real-time P&L visualization.
*   **Communication:** Raw WebSockets for sub-50ms latency updates from the Core.

## 🧩 Key Solutions & Features

### 1. The Strategy Research Lab
A built-in **Backtesting Engine** allows for rapid strategy verification without risking capital.
*   **Dual Mode:** Supports both **Real Market Data** (historical re-simulation) and **Mock Data** (Algorithmic Noise Generation) for offline UI testing.
*   **Metrics:** Calculates **Sharpe Ratio**, **Max Drawdown**, and **Win Rate** instantly.
*   **Optimization:** Configurable Take Profit / Stop Loss parameters to tune outcomes.

### 2. Autonomous Market Regime Detection
The bot doesn't just trade blindly; it detects the **Context**:
*   **VIX Volatility Analysis:** Automatically switches strategies based on fear index (VIX).
*   **Regime Classification:** Identifies `BULLISH`, `BEARISH`, or `SIDELINE` markets and adjusts position sizing (Kelly Criterion) dynamically.

### 3. Reliability First
*   **Self-Healing:** The system monitors its own connection health.
*   **PDT Protection:** Built-in pattern day trader protection logic to prevent account locks.
*   **Cloud Native:** Containerized with **Docker** and deployed to **Google Cloud Run** for serverless scalability.

## � Getting Started

### Prerequisites
*   Java 25 JDK
*   Node.js 20+
*   Docker
*   Alpaca API Keys (Live or Paper)

### Deployment
The system is designed for **GitOps** deployment:
1.  **Backend:** `gcloud run deploy alpaca-bot-core`
2.  **Frontend:** `wrangler pages deploy dist`

---

## 👨‍💻 About the Author
Built by [Ihor Petrov] as a specialized research project into **Low-Latency Java Systems** and **React-based Financial Visualization**. Open to consulting in FinTech, Algo-Trading, and High-Performance Systems.
