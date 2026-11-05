# Profit Optimization Document + Modernization Check

## Profit optimization document

The existing profit optimization/profitability planning document is:

- [`docs/PROFITABILITY_EVALUATION_PLAN.md`](./PROFITABILITY_EVALUATION_PLAN.md)

It covers Tier 1–3 profitability controls, KPIs, attribution, and a promotion/rollback decision process.

## Codebase modernization check (quick audit)

A focused review found the platform can be modernized in a few high-impact areas:

1. **Backend Java toolchain portability**
   - `trading-backend/pom.xml` requires Java 25 target (`<source>25</source>`, `<target>25</target>`).
   - In environments without JDK 25, backend compile/test fails with `invalid target release: 25`.
   - Recommendation: add Maven Toolchains guidance (or CI/toolchain bootstrap) so the required JDK is resolved consistently across local and CI environments.

2. **Frontend quality gate stabilization**
   - Dashboard lint currently reports multiple blocking errors (`npm run lint`) and dashboard tests currently have failures (`npm run test:run`), which limits safe refactoring velocity.
   - Recommendation: prioritize resolving lint and failing test baselines, then enforce lint + test in CI as required checks.

3. **Dashboard docs modernization**
   - `trading-backend/dashboard/README.md` still contains default Vite template text.
   - Recommendation: replace template content with repository-specific run/build/test and architecture notes to reduce onboarding friction.

## Suggested implementation order

1. Stabilize frontend lint/tests (fastest ROI for safe modernization).
2. Standardize Java 25 toolchain setup across environments.
3. Refresh dashboard documentation after quality gates are green.
