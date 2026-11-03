// 必须排在 App 之前：让 react-lang 的 devtools 自动挂载在模块求值前就看到标记。
import './lib/openui/disableDevtools';
import { createRoot } from 'react-dom/client';
import App from './App';
import './global.css';

const root = document.getElementById('root');

if (root) {
  createRoot(root).render(
    <App />
  );
} else {
  console.error('Root element not found');
}
