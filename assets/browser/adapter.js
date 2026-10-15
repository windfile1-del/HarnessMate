(function () {
  'use strict';
  const key = __BROWSER_KEY__;
  if (window[key]) return;
  let revision = 0, snapshot = null, refs = new Map(), protectedPage = false;
  const observed = new WeakSet();
  const nodeRoot = el => typeof el.getRootNode === 'function' ? el.getRootNode() : el.ownerDocument;
  const clean = (s, n = 180) => String(s || '').replace(/\s+/g, ' ').trim().slice(0, n);
  const sensitive = el => {
    const type = String(el.type || '').toLowerCase();
    const hint = [el.name, el.id, el.autocomplete, el.getAttribute('aria-label'), el.getAttribute('placeholder')].join(' ');
    return type === 'password' || /(?:password|passwd|passcode|one.?time|otp|verification.?code|security.?code|credit.?card|card.?number|cc-|cvc|cvv|密码|验证码|银行卡|卡号|安全码)/i.test(hint);
  };
  function label(el) {
    const labelled = clean(el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || '');
    if (labelled) return labelled;
    if (el.labels && el.labels.length) return clean(Array.from(el.labels).map(x => x.innerText).join(' '));
    return clean(el.innerText || (el.tagName === 'INPUT' && /^(submit|button)$/i.test(el.type) ? el.value : '') || el.alt || el.name || el.id);
  }
  function safeUrl(raw) {
    try {
      const u = new URL(raw, location.href); u.hash = '';
      for (const k of Array.from(u.searchParams.keys())) if (/(?:token|secret|password|authorization|code|session|sid|csrf|key|credential)/i.test(k)) u.searchParams.delete(k);
      u.username = ''; u.password = ''; return u.href;
    } catch (_) { return ''; }
  }
  function observeRoot(root) {
    if (observed.has(root)) return;
    observed.add(root);
    new MutationObserver(changes => { if (changes.length) revision++; }).observe(root, {subtree: true, childList: true, attributes: true, characterData: true});
    root.addEventListener('input', () => revision++, true);
    root.addEventListener('change', () => revision++, true);
    root.addEventListener('scroll', () => revision++, true);
  }
  function roots() {
    const result = [], skipped = [];
    function visit(root, frame, offsetX, offsetY, depth, frames = []) {
      if (depth > 6 || result.length >= 32) { skipped.push({kind:'structure_limit'}); return; }
      observeRoot(root); result.push({root, frame, offsetX, offsetY, frames});
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) visit(el.shadowRoot, frame, offsetX, offsetY, depth + 1, frames);
        if (el.tagName === 'IFRAME') {
          try {
            if (!el.contentDocument || !el.contentDocument.documentElement) { skipped.push({kind: 'cross_origin_frame', title: clean(el.title)}); continue; }
            const r = el.getBoundingClientRect(); visit(el.contentDocument, frame + '/' + result.length, offsetX + r.left + el.clientLeft, offsetY + r.top + el.clientTop, depth + 1, frames.concat({el,offsetX,offsetY,left:r.left,top:r.top,width:r.width,height:r.height}));
          } catch (_) { skipped.push({kind: 'cross_origin_frame', title: clean(el.title)}); }
        }
      }
    }
    visit(document, 'main', 0, 0, 0); return {items: result, skipped};
  }
  function visible(el) {
    const w = el.ownerDocument.defaultView, s = w.getComputedStyle(el), r = el.getBoundingClientRect();
    return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) !== 0 && r.width > 0 && r.height > 0 && !el.closest('[hidden],[inert],[aria-hidden="true"]');
  }
  function role(el) {
    return el.getAttribute('role') || ({A:'link', BUTTON:'button', TEXTAREA:'textbox', SELECT:'combobox', VIDEO:'video'}[el.tagName]) || (el.isContentEditable ? 'textbox' : el.type === 'file' ? 'file' : el.type === 'checkbox' ? 'checkbox' : 'textbox');
  }
  function observation(a) {
    refs = new Map(); protectedPage = false;
    const elements = [], sections = [], all = roots(); let i = 0, truncated = false, textTruncated = false;
    const selector = 'a[href],button,input,textarea,select,[role="button"],[role="link"],[role="radio"],[role="checkbox"],[role="option"],[role="tab"],[role="combobox"],[contenteditable="true"],video';
    for (const item of all.items) {
      for (const el of item.root.querySelectorAll('input,textarea,[contenteditable="true"]')) if (sensitive(el)) protectedPage = true;
      const container = item.root.body || item.root;
      const reader = item.root.ownerDocument || item.root;
      const walker = reader.createTreeWalker(container, NodeFilter.SHOW_TEXT);
      let node, text = '', count = 0;
      while ((node = walker.nextNode()) && count++ < 12000 && text.length < (a.max_chars || 12000)) {
        const p = node.parentElement;
        if (!p || p.closest('script,style,noscript,textarea,input,[contenteditable="true"]') || sensitive(p) || !visible(p)) continue;
        const piece = clean(node.nodeValue, 1000); if (piece) text += piece + '\n';
      }
      if (text.length >= (a.max_chars || 12000) || count >= 12000) textTruncated = true;
      if (text) sections.push({frame_id: item.frame, text});
      for (const el of item.root.querySelectorAll(selector)) {
        if (!visible(el)) continue;
        if (i >= (a.max_elements || 120)) { truncated = true; break; }
        const ref = 'e' + (++i), r = el.getBoundingClientRect(), secret = sensitive(el);
        const bounds = {x: r.x + item.offsetX, y: r.y + item.offsetY, width: r.width, height: r.height};
        refs.set(ref, {el, item, bounds, label: label(el), role: role(el)});
        const e = {ref, frame_id: item.frame, role: role(el), name: label(el), tag: el.tagName.toLowerCase(), type: el.type || '', disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true', sensitive: secret, bounds};
        if (secret) { e.value = '[REDACTED]'; e.name = 'Protected input'; }
        else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') e.value = clean(el.value, 1000);
        else if (el.isContentEditable) e.value = clean(el.innerText, 1000);
        if (el.tagName === 'A') e.href = safeUrl(el.href);
        e.focused = (nodeRoot(el).activeElement || el.ownerDocument.activeElement) === el;
        if (el.type === 'checkbox' || el.type === 'radio') e.checked = !!el.checked;
        else if (el.hasAttribute('aria-checked')) { e.aria_checked = el.getAttribute('aria-checked'); if (e.aria_checked !== 'mixed') e.checked = e.aria_checked === 'true'; }
        if (el.hasAttribute('aria-selected')) e.selected = el.getAttribute('aria-selected') === 'true';
        if (el.type === 'file') e.files = Array.from(el.files || []).map(f => ({name:clean(f.name),size:f.size,type:f.type}));
        if (el.tagName === 'SELECT') { e.options = Array.from(el.options).slice(0, 20).map(o => ({label: clean(o.label,100), value: clean(o.value,100), selected: o.selected, disabled: o.disabled})); e.options_truncated = el.options.length > 20; }
        elements.push(e);
      }
    }
    const fullBody = sections.map(s => s.text).join('\n');
    textTruncated = textTruncated || fullBody.length > (a.max_chars || 12000);
    let body = fullBody.slice(0, a.max_chars || 12000);
    // Keep all returned element metadata bounded, not only the page body.
    const elementBudget = 16000;
    while (elements.length && JSON.stringify(elements).length > elementBudget) { const omitted=elements.pop(); refs.delete(omitted.ref); truncated=true; }
    const challenge = /(?:verify you are human|checking your browser|captcha|人机验证|安全验证|验证码)/i.test(body) && (body.length < 2500 || !!document.querySelector('iframe[src*="captcha"],iframe[src*="turnstile"],.g-recaptcha,.h-captcha'));
    snapshot = {id: a.snapshot_id, revision, url: location.href, width: innerWidth, height: innerHeight};
    return {status:'OBSERVED', snapshot_id:a.snapshot_id, revision, url:safeUrl(location.href), title:clean(document.title, 240), ready_state:document.readyState, viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio}, content_untrusted:true, text:body, elements, truncated:truncated||textTruncated, text_truncated:textTruncated, unavailable:all.skipped, protected_inputs:protectedPage, challenge_detected:challenge};
  }
  function proof(a) {
    // Scrolling has no DOM target. Dynamic banners must not invalidate a viewport-only action.
    // Page identity and viewport still apply; clicks/fills retain the full revision proof.
    if (!snapshot || a.snapshot_id !== snapshot.id || (a.action !== 'scroll' && snapshot.revision !== revision) || snapshot.url !== location.href || snapshot.width !== innerWidth || snapshot.height !== innerHeight) throw Error('BROWSER_SNAPSHOT_STALE');
    if (a.action === 'scroll') return {status:'READY', sensitive:false, requires_approval:false};
    const found = refs.get(a.ref); if (!found) throw Error('BROWSER_REF_UNKNOWN');
    const el = found.el;
    if (!el.isConnected || !visible(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') throw Error('BROWSER_TARGET_UNAVAILABLE');
    if (sensitive(el)) throw Error('BROWSER_PROTECTED_INPUT_REQUIRES_TAKEOVER');
    if (label(el) !== found.label || role(el) !== found.role) throw Error('BROWSER_TARGET_CHANGED');
    if (a.action === 'press' && (nodeRoot(el).activeElement || el.ownerDocument.activeElement) !== el) throw Error('BROWSER_FOCUS_REQUIRED');
    const r = el.getBoundingClientRect(), x = r.left + found.item.offsetX + r.width/2, y = r.top + found.item.offsetY + r.height/2;
    if (Math.abs(r.x + found.item.offsetX - found.bounds.x) > 1 || Math.abs(r.y + found.item.offsetY - found.bounds.y) > 1 || Math.abs(r.width-found.bounds.width)>1 || Math.abs(r.height-found.bounds.height)>1) throw Error('BROWSER_TARGET_MOVED');
    if (a.action === 'click' || a.action === 'press') {
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) throw Error('BROWSER_TARGET_OFFSCREEN');
      const hitRoot = nodeRoot(el);
      const hit = (hitRoot.elementFromPoint ? hitRoot : el.ownerDocument).elementFromPoint(r.left+r.width/2,r.top+r.height/2);
      if (!hit || hit !== el && !el.contains(hit) && !hit.contains(el)) throw Error('BROWSER_TARGET_OCCLUDED');
      for (const frame of found.item.frames) {
        const now = frame.el.getBoundingClientRect();
        if (Math.abs(now.left-frame.left)>1 || Math.abs(now.top-frame.top)>1 || Math.abs(now.width-frame.width)>1 || Math.abs(now.height-frame.height)>1) throw Error('BROWSER_FRAME_MOVED');
        const parentHit = frame.el.ownerDocument.elementFromPoint(x-frame.offsetX,y-frame.offsetY);
        if (parentHit !== frame.el) throw Error('BROWSER_TARGET_OCCLUDED');
      }
    }
    const name = label(el), form = el.form || el.closest('form');
    const submit = (el.tagName === 'BUTTON' && (!el.type || el.type === 'submit')) || el.type === 'submit' || a.action === 'press' && a.key === 'Enter' && !!form;
    const search = form && (form.getAttribute('role') === 'search' || !!form.querySelector('input[type="search"]')) && !form.querySelector('input[type="password"]');
    const critical = /(?:发布|下单|购买|支付|发送|删除|转账|确认订单|提交订单|publish|checkout|purchase|\bbuy\b|\bpay\b|\bsend\b|delete|transfer|place.?order)/i.test(name);
    const fields = form ? Array.from(form.querySelectorAll('input,textarea,select,[contenteditable="true"]')).filter(e => !sensitive(e) && e.type !== 'hidden' && visible(e)) : [];
    if (fields.length > 16 || fields.some(e => String(e.value || e.innerText || '').length > 16000)) throw Error('BROWSER_FORM_REVIEW_LIMIT_REQUIRES_TAKEOVER');
    // Full values stay in the native approval proof, not the model observation or journal.
    const formFields = fields.map(e => ({name:label(e),value:clean(e.value || e.innerText,600),exact_value:String(e.value || e.innerText || ''),checked:!!e.checked,selected_values:e.tagName==='SELECT'?Array.from(e.selectedOptions).map(o=>o.value):[]}));
    return {status:'READY', x, y, role:role(el), name, frame_id:found.item.frame, sensitive:false, protected_inputs:protectedPage, requires_approval:(a.action==='click'||a.action==='press') && (critical || !!(form && submit && !search)), form_fields:formFields, href:el.tagName==='A'?safeUrl(el.href):'', native_click:a.action==='click'};
  }
  function act(a) {
    proof(a); const el = a.action === 'scroll' ? null : refs.get(a.ref).el;
    if (a.action === 'fill') {
      if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && !el.isContentEditable) throw Error('BROWSER_TARGET_NOT_EDITABLE');
      if (el.readOnly || /^(?:file|checkbox|radio|submit|button|hidden)$/i.test(el.type || '')) throw Error('BROWSER_TARGET_NOT_EDITABLE');
      el.focus();
      if (el.isContentEditable) el.textContent = a.value;
      else {
        const w = el.ownerDocument.defaultView, proto = el.tagName === 'TEXTAREA' ? w.HTMLTextAreaElement.prototype : w.HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, a.value);
      }
      el.dispatchEvent(new el.ownerDocument.defaultView.Event('input',{bubbles:true}));
      el.dispatchEvent(new el.ownerDocument.defaultView.Event('change',{bubbles:true}));
      revision++;
      return {status:(el.isContentEditable ? el.textContent : el.value) === a.value ? 'VERIFIED_VALUE':'UNKNOWN', input_method:'dom_setter', submitted:true};
    }
    if (a.action === 'select') {
      if (el.tagName !== 'SELECT') throw Error('BROWSER_TARGET_NOT_SELECT');
      const o = Array.from(el.options).find(o => o.value === a.value && !o.disabled); if (!o) throw Error('BROWSER_OPTION_UNAVAILABLE');
      el.value = a.value; el.dispatchEvent(new el.ownerDocument.defaultView.Event('input',{bubbles:true})); el.dispatchEvent(new el.ownerDocument.defaultView.Event('change',{bubbles:true})); revision++;
      return {status:el.value===a.value?'VERIFIED_VALUE':'UNKNOWN', submitted:true};
    }
    if (a.action === 'scroll') {
      const direction = a.direction || 'down', amount = Math.max(80, Math.min(1000, innerHeight * 0.65));
      window.scrollBy(direction==='left'?-amount:direction==='right'?amount:0,direction==='up'?-amount:direction==='down'?amount:0); revision++;
      return {status:'DISPATCHED', submitted:true};
    }
    throw Error('BROWSER_NATIVE_INPUT_REQUIRED');
  }
  function media() {
    const videos = []; let textBudget=12000, cueBudget=200, truncated=false;
    for (const item of roots().items) for (const v of item.root.querySelectorAll('video')) {
      if(videos.length>=8){truncated=true;continue;}
      const allTracks=Array.from(v.textTracks || []);if(allTracks.length>4)truncated=true;
      const tracks=allTracks.slice(0,4).map(t=>{
        const available=Array.from(t.cues||[]),cues=[];
        for(const c of available){if(cueBudget<=0||textBudget<=0){truncated=true;break;}const text=clean(c.text,Math.min(1000,textBudget));if(String(c.text||'').length>text.length)truncated=true;cues.push({start:c.startTime,end:c.endTime,text});cueBudget--;textBudget-=text.length;}
        return {label:clean(t.label),language:t.language,kind:t.kind,mode:t.mode,available_cues:available.length,cues};
      });
      videos.push({frame_id:item.frame,current_time:v.currentTime,duration:Number.isFinite(v.duration)?v.duration:null,paused:v.paused,ready_state:v.readyState,tracks});
    }
    return {status:'MEDIA_OBSERVED', content_untrusted:true, videos, truncated, analysis:'METADATA_AND_AVAILABLE_TEXT_TRACKS_ONLY', audio_transcription:'NOT_IMPLEMENTED', frame_analysis:'NOT_IMPLEMENTED'};
  }
  Object.defineProperty(window,key,{value:Object.freeze({command:function(command,a){try {return command==='observe'?observation(a):command==='prepare'?proof(a):command==='act'?act(a):command==='video'?media():{status:'ERROR',error:'BROWSER_COMMAND_UNKNOWN'};}catch(e){return {status:'NOT_SUBMITTED',submitted:false,error:String(e.message||e).slice(0,180)};}}}), configurable:false, writable:false});
})();
