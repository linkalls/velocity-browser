package dev.velocity.chromium;

/** An explicit, read-only snapshot of visible prose in the current main frame. */
final class ReaderText {
    static final int MAX_TEXT = 45_000;
    static final int MIN_TEXT = 120;
    static final String SCRIPT = """
        (() => {
          const root = document.querySelector('article') ||
                       document.querySelector('main, [role="main"]') || document.body;
          if (!root) return null;
          const exclude = 'nav,header,footer,aside,dialog,form,button,[role="navigation"],'
                        + '[contenteditable],[hidden],[aria-hidden="true"]';
          const nodes = root.querySelectorAll('p,h1,h2,h3,h4,blockquote,pre,li');
          const out = [];
          let total = 0;
          for (let i = 0; i < nodes.length && i < 1500 && total < 45000; i++) {
            const node = nodes[i];
            if (node.closest(exclude) || !node.getClientRects().length) continue;
            if (getComputedStyle(node).visibility !== 'visible') continue;
            if ((node.tagName === 'LI' || node.tagName === 'BLOCKQUOTE')
                    && node.querySelector('p,pre,li')) continue;
            const t = (node.innerText || '').trim().slice(0, 45000 - total);
            if (!t || out[out.length - 1] === t) continue;
            out.push(t);
            total += t.length + 2;
          }
          if (total < 120 || out.length === 0) return null;
          return { url: location.href, text: out.join('\\n\\n').slice(0, 45000) };
        })()
        """;
    private ReaderText() {}
}
