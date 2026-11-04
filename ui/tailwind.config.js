/** @type {import('tailwindcss').Config} */
import plugin from 'tailwindcss/plugin';

export const scrollbarUtilities = {
  '.scrollbar-hidden': {
    '::-webkit-scrollbar': {
      display: 'none',
    },
    'scrollbar-width': 'none',
    '-ms-overflow-style': 'none',
  },
};

export default {
  content: ['./src/**/*.{js,jsx,ts,tsx}', './*.html'],
  important: true,
  theme: {
    extend: {
      colors: {
        primary: '#4040FFB2',
      },
    },
  },
  plugins: [
    plugin(({ addUtilities }) => {
      addUtilities(scrollbarUtilities);
    }),
  ],
  corePlugins: {
    preflight: true,
  },
};
