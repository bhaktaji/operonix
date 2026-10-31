/**
 * Operonix 2.0 design tokens. See docs/DESIGN_SYSTEM.md.
 *
 * The palette is intentionally cool, quiet and high-contrast: a deep midnight "ink" for structure,
 * a porcelain neutral for surfaces, and a single indigo "brand" accent. Colour is used to signal
 * state, never decoration, which is what keeps a data-dense B2B product calm as it grows.
 */
/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: {
          950: "#080b14",
          900: "#0e1322",
          800: "#171f33",
          700: "#2b3651",
          600: "#42506e",
          500: "#5d6b88",
          400: "#7d89a3",
          300: "#a3adc2",
        },
        sand: {
          50: "#fafbfd",
          100: "#f4f6fa",
          200: "#e8ecf3",
          300: "#d7dde8",
          400: "#bcc5d4",
        },
        brand: {
          50: "#eef1ff",
          100: "#e0e5ff",
          200: "#c7cfff",
          300: "#a3aefe",
          400: "#7d87f8",
          500: "#5b63ec",
          600: "#4649d2",
          700: "#3a3bab",
          800: "#33348a",
          900: "#2c2d6d",
        },
        mint: {
          50: "#ecfdf5",
          100: "#d1fae5",
          500: "#10b981",
          600: "#059669",
          700: "#047857",
        },
        amber: {
          50: "#fffbeb",
          100: "#fef3c7",
          400: "#fbbf24",
          500: "#f59e0b",
          600: "#d97706",
        },
      },
      fontFamily: {
        sans: [
          "Inter",
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "Segoe UI",
          "Noto Sans",
          "sans-serif",
        ],
        display: ["Sora", "Inter", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      boxShadow: {
        card: "0 1px 2px rgba(8, 11, 20, 0.04), 0 10px 30px -18px rgba(8, 11, 20, 0.18)",
        float: "0 32px 80px -32px rgba(8, 11, 20, 0.42)",
        ring: "0 0 0 1px rgba(8, 11, 20, 0.06)",
      },
      borderRadius: {
        xl2: "1.15rem",
        xl3: "1.5rem",
      },
      letterSpacing: {
        tightest: "-0.035em",
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(6px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        "fade-in": {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" },
        },
        "scale-in": {
          "0%": { opacity: "0", transform: "scale(0.985)" },
          "100%": { opacity: "1", transform: "scale(1)" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.28s ease-out both",
        "fade-in": "fade-in 0.2s ease-out both",
        "scale-in": "scale-in 0.18s ease-out both",
      },
    },
  },
  plugins: [],
};