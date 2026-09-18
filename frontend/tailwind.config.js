/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        sv: {
          bg: 'var(--sv-bg)',
          panel: 'var(--sv-panel)',
          elevated: 'var(--sv-elevated)',
          border: 'var(--sv-border)',
          fg: 'var(--sv-fg)',
          muted: 'var(--sv-muted)',
          accent: 'var(--sv-accent)',
          live: 'var(--sv-live)',
          fault: 'var(--sv-fault)',
        },
        risk: {
          clear: 'var(--risk-clear)',
          watch: 'var(--risk-watch)',
          elevated: 'var(--risk-elevated)',
          critical: 'var(--risk-critical)',
        },
      },
      fontFamily: {
        display: ['"IBM Plex Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"IBM Plex Mono"', 'ui-monospace', 'monospace'],
      },
      screens: {
        projector: '1280px',
      },
    },
  },
  plugins: [],
};
