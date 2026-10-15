(function () {
  'use strict';
  const content = document.getElementById('content');
  const md = window.markdownit({html:false,linkify:false,typographer:false,breaks:true});
  md.use(window.texmath, {engine:window.katex,delimiters:['dollars','brackets'],
    katexOptions:{trust:false,throwOnError:false,strict:'ignore',maxExpand:1000,maxSize:20}});
  // Model images are labels here: displaying prose must not make hidden network requests.
  md.renderer.rules.image = function (tokens, idx) {
    return md.utils.escapeHtml(tokens[idx].content || tokens[idx].attrGet('alt') || '');
  };
  let source = '';
  function height() {
    const pixels = Math.ceil(content.getBoundingClientRect().height);
    if (window.HarnessMateDisplay) window.HarnessMateDisplay.height(Math.max(1,pixels));
  }
  function copy(text) {
    if (window.HarnessMateDisplay) window.HarnessMateDisplay.copy(text);
    else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
  }
  function setSource(value, palette) {
    source = String(value);
    if (palette) {
      Object.keys(palette).forEach(function (key) {
        if (['text','muted','surface','border'].includes(key) && /^#[0-9a-f]{6}$/i.test(palette[key]))
          document.documentElement.style.setProperty('--'+key,palette[key]);
      });
      if (Number.isFinite(palette.fontScale)) document.documentElement.style.fontSize=(16*palette.fontScale)+'px';
    }
    content.innerHTML = window.DOMPurify.sanitize(md.render(source), {
      USE_PROFILES:{html:true,mathMl:true,svg:true},
      FORBID_TAGS:['style','script','iframe','object','embed','img','form','input'],
      ADD_TAGS:['eq','eqn']
    });
    content.querySelectorAll('table').forEach(function (table) {
      const wrap=document.createElement('div');wrap.className='table-scroll';table.replaceWith(wrap);wrap.appendChild(table);
    });
    content.querySelectorAll('pre').forEach(function (pre) {
      const code=pre.querySelector('code');if(!code)return;
      const text=code.textContent; const button=document.createElement('button');
      button.className='copy-code';button.type='button';button.textContent='复制代码';
      button.addEventListener('click',function(){copy(text);});pre.prepend(button);
    });
    const original=document.createElement('button');original.type='button';original.className='copy-source';
    original.textContent='复制原文';original.addEventListener('click',function(){copy(source);});
    content.appendChild(original);
    requestAnimationFrame(height);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(height);
    return true;
  }
  if (window.ResizeObserver) new ResizeObserver(height).observe(content);
  window.HarnessMateRenderer={setSource:setSource,copySource:function(){copy(source);}};
}());
