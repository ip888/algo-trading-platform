# ⚡ Algo Trading Platform: Autonomous Stock Trading System

**Algorithmic trading engine for US Stocks via Alpaca API, with modern cloud deployment.**

![Status](https://img.shields.io/badge/Status-Production-green?style=flat-square)
![Java](https://img.shields.io/badge/Java-25%20LTS-orange?style=flat-square)
![Architecture](https://img.shields.io/badge/Architecture-Event--Driven-blue?style=flat-square)
![Frontend](https://img.shields.io/badge/Frontend-React%20%7C%20Vite-blueviolet?style=flat-square)
![Cloud](https://img.shields.io/badge/Cloud-Fly.io-blue?style=flat-square)

## 📖 Executive Summary

This project is a **full-stack, high-concurrency stock trading platform** using the Alpaca Securities API (US markets).

The trading engine leverages **Java 25's Virtual Threads (Project Loom)** for high-concurrency market data processing, deployed on Fly.io with an embedded React dashboard.

## 🏗 System Architecture

```mermaid
graph TD
    subgraph "Fly.io"
        JavaCore["☕ Java 25 Core"]
        VirtualThreads["🧵 Virtual Thread Pool"]
        AlpacaStrategy["📈 Alpaca Strategy Engine"]
        Dashboard["🖥️ React Dashboard"]

        JavaCore --> VirtualThreads
        VirtualThreads --> AlpacaStrategy
        JavaCore --> Dashboard
    end

    subgraph "External APIs"
        AlpacaAPI["📡 Alpaca API"]
    end

    AlpacaStrategy <-->|REST/WSS| AlpacaAPI
```

## 🛠 Technology Stack

### 🧠 Trading Backend (Java)

- **Language:** Java 25 (LTS) with Virtual Threads (Project Loom)
- **Framework:** Javalin - lightweight web framework (sub-1ms overhead)
- **Build:** Maven with Shade plugin for uber-JAR
- **Resilience:** Circuit breakers (Resilience4j), heartbeat monitoring, emergency protocol
- **Deployment:** Fly.io (containerized)

### 💻 Frontend: Command & Control Dashboard
- **Framework:** React 19 + TypeScript + Vite
- **State:** Zustand for lightweight state management
- **Charts:** Lightweight Charts (v5) + Recharts
- **Real-time:** WebSocket for sub-50ms latency updates

## 🧩 Key Features

### 1. Multi-Profile Trading
- **MAIN Profile**: Conservative, momentum-based trading
- **EXPERIMENTAL Profile**: Aggressive, volatility-adjusted strategies
- **Kelly Criterion**: Dynamic position sizing based on edge estimation
- **VIX Integration**: Automatic strategy adjustment based on market fear

### 2. Risk Management
- **Emergency Protocol**: Manual panic button to flatten all positions
- **Heartbeat Monitoring**: System health checks every 5 seconds
- **PDT Protection**: Pattern day trader safeguards
- **Position Tracking**: Real-time P&L monitoring with entry prices

### 3. Adaptive Capital-Tier Risk Management
- **BracketOrderResult**: Tracks whether server-side SL/TP protection was applied
- **CapitalTierManager**: Automatic risk adjustment based on account size
  - MICRO (<$500): Ultra-conservative, max 2 positions, prefer whole shares
  - SMALL ($500-$2K): Conservative, 3 positions, whole shares for bracket protection
  - MEDIUM ($2K-$5K): Balanced, 4 positions
  - STANDARD ($5K-$25K): Full trading capabilities
  - PDT ($25K+): Unrestricted day trading
- **PositionProtectionAuditor**: Monitors positions without broker-side protection
  - Telegram alerts when fractional orders skip bracket protection
  - WebSocket broadcasts to dashboard for real-time UI warnings
- **Dashboard Warning**: Positions without bracket orders show "⚠️ No Bracket" indicator

## 🚀 Getting Started

### Prerequisites
- Java 25 JDK (Temurin or GraalVM)
- Node.js 20+
- Docker
- Alpaca API Keys

### Local Development
```bash
# Clone and navigate
cd trading-backend

# Build dashboard
cd dashboard && npm install && npm run build && cd ..

# Set environment variables
export ALPACA_API_KEY=your_key
export ALPACA_API_SECRET=your_secret

# Build and run
mvn clean package -DskipTests
java --enable-preview -jar target/trading-backend-1.0-SNAPSHOT.jar
```

### Deployment
```bash
cd trading-backend && flyctl deploy
```

## 📡 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | System health check |
| `/api/heartbeat` | GET | Component heartbeat status |
| `/api/status` | GET | Bot running status |
| `/api/account` | GET | Alpaca account info |
| `/api/positions` | GET | Current stock positions |
| `/api/orders` | GET | Open orders |
| `/api/emergency/panic` | POST | Flatten ALL positions |
| `/api/emergency/reset` | POST | Reset emergency state |

## 📦 Project Structure
```
.
├── trading-backend/             # Java Stock Trading Bot
│   ├── src/main/java/com/trading/
│   │   ├── api/controller/      # REST API controllers
│   │   ├── bot/                 # Main TradingBot orchestrator
│   │   ├── broker/              # Alpaca client, BrokerRouter
│   │   ├── portfolio/           # ProfileManager, strategies
│   │   ├── protection/          # EmergencyProtocol
│   │   └── risk/                # Risk management
│   ├── dashboard/               # React frontend (embedded)
│   ├── Dockerfile               # Multi-stage build
│   └── fly.toml                 # Fly.io config
│
└── README.md                    # This file
```

## ⚙️ Configuration

### config.properties
```properties
# Alpaca Profiles
MAIN_TAKE_PROFIT_PERCENT=2.0
MAIN_STOP_LOSS_PERCENT=1.0
MAX_POSITIONS_AT_ONCE=5
MIN_HOLD_TIME_HOURS=4
```

## 🔒 Security Notes
- API keys stored in `config.properties` (git-ignored)
- Use `config.properties.template` as reference

---

## 👨‍💻 Author
Built by **Ihor Petrov** as a research project in **Low-Latency Java Systems** and **Algorithmic Trading**.

Open to consulting in FinTech, Algo-Trading, and High-Performance Systems.
