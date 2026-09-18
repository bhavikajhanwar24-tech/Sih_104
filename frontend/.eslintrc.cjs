/* eslint-env node */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true,
  },
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: {
      jsx: true,
    },
  },
  settings: {
    react: {
      version: 'detect',
    },
  },
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  plugins: ['react', 'react-hooks'],
  rules: {
    'react/prop-types': 'error',
    'react/react-in-jsx-scope': 'off',
    'no-restricted-syntax': [
      'error',
      {
        selector: 'TSTypeAnnotation',
        message: 'TypeScript syntax is not allowed - use JSDoc (see P0.3)',
      },
      {
        selector: 'TSInterfaceDeclaration',
        message: 'TypeScript syntax is not allowed - use JSDoc (see P0.3)',
      },
      {
        selector: 'TSAsExpression',
        message: 'TypeScript syntax is not allowed - use JSDoc (see P0.3)',
      },
    ],
  },
  overrides: [
    {
      files: ['**/audio/capture-worklet.js'],
      env: {
        // AudioWorklet global scope
        browser: true,
      },
      globals: {
        AudioWorkletProcessor: 'readonly',
        registerProcessor: 'readonly',
        currentFrame: 'readonly',
        currentTime: 'readonly',
        sampleRate: 'readonly',
      },
    },
    {
      files: ['**/__tests__/**', '**/*.test.js', '**/*.test.jsx'],
      env: {
        jest: true,
      },
      globals: {
        describe: 'readonly',
        it: 'readonly',
        expect: 'readonly',
        vi: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
      },
    },
  ],
};
