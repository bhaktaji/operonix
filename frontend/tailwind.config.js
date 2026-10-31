/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: {
          950: "#0a101d",
          900: "#111a2c",
          800: "#1b2740",
          700: "#33415c",
          500: "#5b6b85",
          400: "#7c8aa5",
        },
        sand: {
          50: "#fbfaf7",
          100: "#f5f3ec",
          200: "#eae6da",
          300: "#dcd6c6",
        },
        brand: {
          50: "#ecfdf6",
          100: "#d0f7e8",
          200: "#a4edd4",
          300: "#6bdbb9",
          400: "#35c29d",
          500: "#12a385",
          600: "#0b8370",
          700: "#0a685b",
          800: "#0a534a",
          900: "#09453e",
        },
        amber: {
          400: "#f0b357",
          500: "#e79b32",
          600: "#c97f1e",
        },
      },
      fontFamily: {
        sans: ["Inter", "ui-sans-serif", "system-ui", "-apple-system", "Segoe UI", "sans-serif"],
        display: ["Sora", "Inter", "ui-sans-serif", "system-ui", "sans-serif"],
      },
      boxShadow: {
        card: "0 1px 2px rgba(15, 23, 42, 0.04), 0 8px 24px -12px rgba(15, 23, 42, 0.12)",
        float: "0 24px 60px -24px rgba(10, 16, 29, 0.35)",
      },
      borderRadius: {
        xl2: "1.15rem",
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
      },
      animation: {
        "fade-up": "fade-up 0.28s ease-out both",
        "fade-in": "fade-in 0.2s ease-out both",
      },
    },
  },
  plugins: [],
};
