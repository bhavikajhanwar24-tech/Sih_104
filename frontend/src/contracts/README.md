# Frontend contracts (plain JavaScript)

The JSON Schemas in `docs/contracts/` are **frozen**. This folder adapts them for a
JSX (not TSX) React app:

| File | Role |
|------|------|
| `contracts.d.ts` | **GENERATED** ambient types — editor autocomplete / red squiggles via `jsconfig` `checkJs`. Not compiled. |
| `validators.js` | **GENERATED** precompiled Ajv validators (no Ajv compiler in the browser bundle). |
| `index.js` | **Hand-written** — `assertTelemetryFrame`, safe accessors, frozen enums. |

## Regenerate

```bash
npm run contracts
# → bash ../scripts/gen-contracts.sh
```

Commit the generated files so teammates can build without running the script.

## Usage

```js
import {
  assertTelemetryFrame,
  getSmoothedRisk,
  getLevel,
  INTERVENTION_LEVELS,
} from '@/contracts';

/** @param {TelemetryFrame} frame */
export function RiskGauge({ frame }) {
  const safe = assertTelemetryFrame(frame);
  const risk = getSmoothedRisk(safe);
  const level = getLevel(safe);
  // ...
}
```

`assertTelemetryFrame` **never throws**. In `import.meta.env.DEV` it logs every Ajv
`instancePath` on mismatch and still returns the frame so a schema drift cannot blank
the dashboard mid-demo.

## Why no TypeScript

`jsconfig.json` with `checkJs: true` type-checks JSDoc in the editor only — zero build
cost. ESLint bans TypeScript syntax in `.jsx` / `.js` (see P0.3).
