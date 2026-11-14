'use strict';
(() => {
  const escape = value => value.replace(/[&<>"']/g, character => ({
    '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
  })[character]);
  const parser = window.marked && new window.marked.Marked({
    gfm: true,
    breaks: true,
    renderer: {
      // Model HTML is displayed as text; images never load remote resources.
      html: token => escape(token.text),
      image: token => escape(token.text || '图片')
    }
  });
  window.AgentMarkdown = Object.freeze({
    render(element, text) {
      element.textContent = text;
      element.classList.remove('markdown-body');
      if (!parser || !window.DOMPurify?.isSupported) return;
      try {
        const fragment = window.DOMPurify.sanitize(parser.parse(text), {
          RETURN_DOM_FRAGMENT: true,
          ALLOWED_TAGS: ['p','br','h1','h2','h3','h4','h5','h6','strong','em','del',
            'a','pre','code','blockquote','ul','ol','li','hr','table','thead','tbody','tr','th','td'],
          ALLOWED_ATTR: ['href','title','start'],
          ALLOW_DATA_ATTR: false,
          ALLOW_ARIA_ATTR: false
        });
        fragment.querySelectorAll('a').forEach(link => {
          let url;
          try { url = new URL(link.getAttribute('href')); } catch { /* Non-absolute links stay text. */ }
          if (!url || !['https:', 'http:'].includes(url.protocol) || url.username || url.password) {
            link.replaceWith(...link.childNodes);
            return;
          }
          link.setAttribute('href', url.href);
          link.setAttribute('target', '_blank');
          link.setAttribute('rel', 'noopener noreferrer');
          link.setAttribute('referrerpolicy', 'no-referrer');
        });
        fragment.querySelectorAll('table').forEach(table => {
          const scroll = document.createElement('div');
          scroll.className = 'markdown-table';
          scroll.tabIndex = 0;
          scroll.setAttribute('role', 'region');
          scroll.setAttribute('aria-label', '回复表格，可横向滚动');
          table.replaceWith(scroll);
          scroll.append(table);
        });
        element.replaceChildren(fragment);
        element.classList.add('markdown-body');
      } catch {
        // A renderer failure must preserve a readable reply and never insert raw HTML.
        element.textContent = text;
      }
    }
  });
})();
