#!/usr/bin/env node
/**
 * Precompile Ajv validators for the frontend (ESM, no Ajv compiler in the browser).
 * Invoked by scripts/gen-contracts.sh — do not run against frozen schemas for edits.
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..');
const frontend = join(root, 'frontend');
const contractsDir = join(root, 'docs', 'contracts');
const outFile = join(frontend, 'src', 'contracts', 'validators.js');

const require = createRequire(join(frontend, 'package.json'));
const ajvRoot = dirname(require.resolve('ajv/package.json'));

const { default: Ajv2020 } = await import(pathToFileURL(join(ajvRoot, 'dist', '2020.js')).href);
const { default: standaloneCode } = await import(
  pathToFileURL(join(ajvRoot, 'dist', 'standalone', 'index.js')).href
);

const SCHEMAS = [
  'FeatureFrame',
  'TelemetryFrame',
  'AuditBlock',
  'SessionStartRequest',
  'ChallengeIssued',
  'ChallengeResult',
  'InterventionOverride',
  'CrossChannelEvent',
];

const ajv = new Ajv2020({
  allErrors: true,
  strict: false,
  code: { source: true, esm: true },
});

/** @type {Record<string, string>} */
const exportMap = {};

for (const name of SCHEMAS) {
  const schemaPath = join(contractsDir, `${name}.schema.json`);
  if (!existsSync(schemaPath)) {
    throw new Error(`Missing schema: ${schemaPath}`);
  }
  const schema = JSON.parse(readFileSync(schemaPath, 'utf8'));
  if (!schema.$id) {
    throw new Error(`Schema ${name} is missing $id`);
  }
  ajv.addSchema(schema);
  exportMap[`validate${name}`] = schema.$id;
}

const header = `/* GENERATED - DO NOT EDIT. Precompiled Ajv validators. Run scripts/gen-contracts.sh */
/* eslint-disable */
`;

let code = standaloneCode(ajv, exportMap);

// Rewrite residual CJS runtime requires to ESM imports (Ajv standalone quirk).
/** @type {Map<string, string>} */
const runtimeImports = new Map();
code = code.replace(
  /require\("ajv\/dist\/runtime\/([^"]+)"\)\.default/g,
  (_match, name) => {
    const id = `${String(name).replace(/\W/g, '_')}Runtime`;
    runtimeImports.set(name, id);
    return id;
  },
);

if (runtimeImports.size > 0) {
  const imports = [...runtimeImports.entries()]
    .map(([name, id]) => `import ${id} from "ajv/dist/runtime/${name}.js";`)
    .join('\n');
  code = `${imports}\n${code}`;
}

if (/\brequire\s*\(/.test(code)) {
  throw new Error('Generated validators.js still contains require() — aborting');
}

writeFileSync(outFile, header + code + '\n', 'utf8');
console.log('Wrote', outFile);
