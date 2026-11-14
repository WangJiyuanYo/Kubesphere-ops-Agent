# 对话渲染安全回归

使用 Node.js 24 或更新版本：

```powershell
npm --prefix tests/ui ci
npm --prefix tests/ui test
```

测试直接加载实际随服务打包的解析器、净化库及 `markdown.js`，覆盖段落、代码转义、原始 HTML、危险链接、图片和依赖失败降级。本目录只有开发测试依赖；前端运行不需要 Node.js。窄屏滚动和实际聊天入口另用浏览器验收。

聊天入口回归同时验证新旧回复、失败轮次的独立采证记录、HTTP_403展示，以及证据中的HTML仍作为文本；失败模型不能隐藏已保存的证据。
