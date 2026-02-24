# Copilot Instructions for AI Coding Agents

## Big Picture Architecture
- **Java monorepo**: Contains the trading-backend (Java) and dashboard (TypeScript/React) components. Each component has its own build and deployment configuration.
- **Service boundaries**: Key components include `trading-backend` (Java, main backend with embedded dashboard) and `dashboard` (TypeScript/React, UI).
- **Data flow**: Trading logic and orchestration are handled in `trading-backend`. The dashboard communicates via WebSocket and REST APIs. See `config.properties` and `Dockerfile` for integration details.

## Developer Workflows
- **Build Java**: Use Maven (`mvn clean install`) in the `trading-backend` directory.
- **Run/Deploy**: Use `flyctl deploy` in `trading-backend` for Fly.io deployment. Docker is used for containerization.
- **Test Java**: Run `mvn test` in the `trading-backend` directory. Tests are in `trading-backend/src/test/`.
- **Frontend**: For the dashboard, use Vite (`vite.config.ts`) and npm scripts in `dashboard/package.json`.

## Project-Specific Conventions
- **Config files**: `trading-backend/config.properties` contains all trading configuration.
- **Docker**: The trading-backend has its own `Dockerfile` for multi-stage builds.
- **Scripts**: Prefer using provided shell scripts for deployment and health checks. Use relative paths as in scripts.

## Integration Points & Dependencies
- **External APIs**: Trading services integrate with Alpaca Securities API. Check for API keys and endpoints in config files.
- **Cloud deployment**: Uses `fly.toml` (Fly.io) for cloud deployment.

## Examples & References
- **Java backend**: See `trading-backend/src/main/` for main logic.
- **Frontend**: See `trading-backend/dashboard/src/` for React/Vite setup.

---
For questions or unclear conventions, ask for clarification or reference the relevant README in the service directory.
