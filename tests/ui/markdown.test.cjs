'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const root = path.resolve(__dirname, '../../src/main/resources/static');

function setup(omit = '') {
  const dom = new JSDOM('<div id="reply" class="message-text"></div>', { runScripts: 'outside-only' });
  for (const file of ['vendor/marked.umd.js', 'vendor/purify.min.js', 'markdown.js']) {
    if (file !== omit) dom.window.eval(fs.readFileSync(path.join(root, file), 'utf8'));
  }
  return { dom, reply: dom.window.document.getElementById('reply'), render(text) {
    dom.window.AgentMarkdown.render(this.reply, text);
    return this.reply;
  } };
}

test('headings, paragraphs, nested lists, emphasis, quotes and GFM tables render', () => {
  const view = setup();
  try {
    const el = view.render('# 排查方案\n\n第一段。\n保留换行。\n\n第二段 **重点**，*建议* 和 ~~旧结论~~。\n\n3. 检查 Pod\n   - 查看事件\n4. 验证恢复\n\n> 先采证\n\n---\n\n| 项目 | 状态 |\n| --- | --- |\n| Pod | Pending |');
    assert.equal(el.querySelector('h1').textContent, '排查方案');
    assert.ok(el.querySelectorAll('p').length >= 3);
    assert.ok(el.querySelector('p br'));
    for (const selector of ['strong','em','del','ol[start="3"] ul li','blockquote','hr','.markdown-table table th','.markdown-table td']) {
      assert.ok(el.querySelector(selector), selector);
    }
    assert.equal(el.querySelector('.markdown-table').tabIndex, 0);
    assert.ok(el.classList.contains('markdown-body'));
  } finally { view.dom.window.close(); }
});

test('inline and fenced code preserve literal HTML, spacing and line breaks', () => {
  const view = setup();
  try {
    const el = view.render('执行 `kubectl get pods`\n\n```html\n<script>alert(1)</script>\n  <b>literal</b>\n```');
    assert.equal(el.querySelector('p code').textContent, 'kubectl get pods');
    assert.equal(el.querySelector('pre code').textContent, '<script>alert(1)</script>\n  <b>literal</b>\n');
    assert.equal(el.querySelector('script,b'), null);
  } finally { view.dom.window.close(); }
});

test('HTML, event handlers, SVG, frames, forms and images cannot become active DOM', () => {
  const view = setup();
  try {
    const payload = '<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n<svg onload=alert(1)></svg>\n\n<iframe srcdoc="bad"></iframe>\n\n<form id="reply"><input name="x"></form>\n\n![外部图片](https://example.com/tracker.png)';
    const el = view.render(payload);
    assert.equal(el.querySelector('script,img,svg,iframe,form,input,style'), null);
    assert.ok(el.textContent.includes('<script>alert(1)</script>'));
    assert.ok(el.textContent.includes('外部图片'));
    for (const child of el.querySelectorAll('*')) {
      assert.equal([...child.attributes].some(attr => /^on|^style$|^id$|^name$/i.test(attr.name)), false);
    }
  } finally { view.dom.window.close(); }
});

test('only absolute HTTP(S) links without credentials are clickable', () => {
  const view = setup();
  try {
    const el = view.render('[安全](https://example.com/docs?q=1&x=2) [本机](http://127.0.0.1:8100/) [脚本](javascript:alert%281%29) [编码](jav&#x61;script:alert%281%29) [数据](data:text/html,bad) [文件](file:///etc/passwd) [相对](/api/console/llm) [协议省略](//evil.example) [凭据](https://user:pass@example.com)');
    assert.equal(el.querySelectorAll('a').length, 2);
    for (const link of el.querySelectorAll('a')) {
      assert.equal(link.target, '_blank');
      assert.equal(link.rel, 'noopener noreferrer');
      assert.equal(link.getAttribute('referrerpolicy'), 'no-referrer');
    }
    assert.ok(el.textContent.includes('脚本'));
    assert.ok(el.textContent.includes('编码'));
  } finally { view.dom.window.close(); }
});

test('missing parser, missing sanitizer or render failure fall back to safe plain text', () => {
  for (const omit of ['vendor/marked.umd.js', 'vendor/purify.min.js', '']) {
    const view = setup(omit);
    try {
      if (!omit) view.dom.window.DOMPurify.sanitize = () => { throw new Error('fixture'); };
      const raw = '**保留原文** <img src=x onerror=alert(1)>';
      const el = view.render(raw);
      assert.equal(el.textContent, raw);
      assert.equal(el.childElementCount, 0);
      assert.equal(el.classList.contains('markdown-body'), false);
    } finally { view.dom.window.close(); }
  }
});

test('chat entry renders historical and new replies while user, failed and pending messages stay text', async () => {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), {
    runScripts: 'outside-only', url: 'http://127.0.0.1/'
  });
  try {
    const win = dom.window;
    win.TextEncoder = TextEncoder;
    win.setInterval = () => 0;
    const session = { id: 'fixture-session', title: 'Markdown 回归' };
    const turns = [
      { id: 'ok', userContent: '**用户原文**', assistantContent: '## 历史方案\n\n**证据**', state: 'SUCCEEDED' },
      { id: 'failed', userContent: '失败', assistantContent: '**失败提示** <script>bad</script>', state: 'FAILED', includeCluster: true },
      { id: 'pending', userContent: '等待', state: 'RUNNING' }
    ];
    win.fetch = async (url, options) => {
      let body;
      if (url === '/api/console/bootstrap') body = {
        username: 'operator', mode: 'DEMO', connections: [], runs: [], sessions: [session],
        llm: { enabled: false, provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://example.com/v1', model: 'fixture', temperature: 0.1, maxTokens: 512 }
      };
      else if (url.endsWith('/messages') && options.method === 'POST') {
        turns.splice(0, turns.length, { id: 'new', userContent: JSON.parse(options.body).content, assistantContent: '### 新回复\n\n- 检查事件', state: 'SUCCEEDED' });
        body = turns[0];
      } else if (url.endsWith('/inspection')) body = { available: true, report: {
        stage: 'PARTIAL', plan: { namespace: 'fixture-ns' }, at: '2026-11-13T00:00:00Z',
        requests: [{ request: 'GET /api/v1/namespaces/fixture-ns/events', state: 'FAILED', result: 'HTTP_403' }],
        issues: ['events list 权限不足'], evidence: '保留证据 <script>literal</script>'
      } };
      else if (url.endsWith('/turns')) body = turns;
      else throw new Error('Unexpected fixture URL: ' + url);
      return { status: 200, ok: true, text: async () => JSON.stringify(body) };
    };
    for (const file of ['vendor/marked.umd.js', 'vendor/purify.min.js', 'markdown.js', 'app.js']) {
      win.eval(fs.readFileSync(path.join(root, file), 'utf8'));
    }
    const settle = () => new Promise(resolve => setImmediate(resolve));
    const doc = win.document;
    doc.querySelector('#login-form input[name="password"]').value = 'fixture-only';
    doc.querySelector('#login-form button').click();
    await settle();
    assert.equal(doc.getElementById('app').hidden, false);
    const select = doc.getElementById('session-select');
    select.value = session.id;
    select.dispatchEvent(new win.Event('change'));
    await settle();
    assert.equal(doc.querySelector('.assistant .markdown-body h2').textContent, '历史方案');
    assert.equal(doc.querySelector('.user .message-text').textContent, '**用户原文**');
    assert.equal(doc.querySelector('.failed .markdown-body, .pending .markdown-body, .user .markdown-body'), null);
    assert.equal(doc.querySelector('.failed .message-text').textContent, '**失败提示** <script>bad</script>');
    assert.equal(doc.querySelector('#messages script'), null);
    const details = doc.querySelector('.failed .chat-inspection');
    assert.ok(details, 'failed model replies keep a tool evidence entry');
    details.open = true;
    await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(details.textContent.includes('HTTP_403'));
    assert.ok(details.textContent.includes('保留证据 <script>literal</script>'));
    assert.equal(details.querySelector('script'), null);
    doc.getElementById('chat-input').value = '继续排查';
    doc.getElementById('send-message').click();
    await settle();
    assert.equal(doc.querySelector('.assistant .markdown-body h3').textContent, '新回复');
    assert.equal(doc.querySelector('.assistant .markdown-body li').textContent, '检查事件');
  } finally { dom.window.close(); }
});
