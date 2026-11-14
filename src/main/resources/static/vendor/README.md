# 控制台 Markdown 本地依赖

浏览器依赖随服务打包，不请求 CDN。升级时保留上游许可证，校验 npm tarball integrity 后提取指定文件，并运行 `tests/ui` 的渲染安全回归。

| 包 | 版本 | 来源 | 本地文件 SHA-256 |
| --- | --- | --- | --- |
| marked | 18.2.0 | https://registry.npmjs.org/marked/18.2.0 | `392f4df89533b50f1dadff3f6dc0952350bd39c9f17def50a0374ef47d6147ef` |
| DOMPurify | 3.4.17 | https://registry.npmjs.org/dompurify/3.4.17 | `cd37898f9782adf54820c413191b5691803f0f137c799d6edcfd38523b4c2c31` |

marked 使用 MIT 许可证；DOMPurify 使用 Apache-2.0 或 MPL-2.0 双许可证。许可证随文件保存。`markdown.js` 只允许排版标签、过滤链接协议，原始 HTML 显示为文本，图片仅显示说明。
