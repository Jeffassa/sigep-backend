/**
 * Build Tailwind STATIQUE pour SIGEP (remplace le Play-CDN cdn.tailwindcss.com).
 * Génère src/main/resources/static/css/tailwind.css à partir des classes réellement
 * utilisées dans les templates. Régénérer après tout ajout de classe : `npm run build`.
 */
module.exports = {
  content: ['../src/main/resources/templates/**/*.html'],
  darkMode: 'class', // thème sombre piloté par la classe .dark sur <html> (cf. sigep.css)
  theme: {
    extend: {
      colors: {
        ink: 'var(--c-ink)',
        'ink-2': 'var(--c-ink2)',
        muted: 'var(--c-muted)',
        line: 'var(--c-line)',
        paper: 'var(--c-bg)',
      },
      fontFamily: {
        serif: ['"Instrument Serif"', 'ui-serif', 'Georgia', 'serif'],
        sans: ['"Geist"', '"Geist Sans"', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
    },
  },
  plugins: [require('@tailwindcss/forms')],
};
