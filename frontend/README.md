# FCTTS frontend source

This Vue 3/Vite source was recovered from the user-provided `D:\vs` directory during Phase 11.

## Reproducible build

```bash
corepack enable
corepack prepare pnpm@11.19.0 --activate
pnpm install --frozen-lockfile
pnpm build
```

The build output is written to `frontend/dist` and is intentionally not committed.

## Qualification boundary

The recovered source reproducibly builds the historical Vite application. Its original build matched `D:\vs\dist` byte for byte during recovery. It does not reproduce the current Spring Boot `src/main/resources/static/index.html`, which uses a different legacy bundle plus Phase 1-10 enhancement scripts. Do not replace the current backend static entry point until the feature comparison and migration are complete.
