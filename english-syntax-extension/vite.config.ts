import { defineConfig } from "vite";
import webExtension from "vite-plugin-web-extension";

export default defineConfig({
  plugins: [webExtension()],
  // modulePreload 关闭:扩展页面加载的是本地 chrome-extension:// 资源,预加载没有
  // 收益,反而触发 Chrome "cross-world extension resource mismatch / preload not
  // used" 控制台警告。
  build: { sourcemap: true, target: "chrome120", modulePreload: false },
});
