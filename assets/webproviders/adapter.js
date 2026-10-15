/* Harness DOM adapter. No credential reads, network interception, native bridge or tool execution. */
(function (key, cfg) {
  'use strict';
  if (location.protocol !== 'https:' || cfg.hosts.indexOf(location.hostname) < 0) return;
  if (window[key] && window[key].version === cfg.version) return;
  const common = {
    input: ['textarea', '[contenteditable="true"][role="textbox"]', '[contenteditable="true"][data-placeholder]', '[contenteditable="true"]'],
    send: ['button[type="submit"]', 'button[data-testid="send-button"]', 'button[aria-label="Send message"]', 'button[aria-label="发送"]'],
    user: ['[data-message-author-role="user"]', '[data-role="user"]', '[data-testid="user-message"]'],
    answer: ['[data-message-author-role="assistant"]', '[data-role="assistant"]', '[data-testid="assistant-message"]'],
    stop: ['button[data-testid="stop-button"]', 'button[aria-label="Stop generating"]', 'button[aria-label="停止生成"]'],
    regenerate: ['button[data-testid="regenerate-button"]', 'button[aria-label="Regenerate response"]'],
    upload: ['input[type="file"]', 'button[aria-label="Attach files"]', 'button[aria-label="上传附件"]'],
    attachments: ['[data-testid="attachment-preview"]', '[data-testid="file-preview"]', '[class*="attachment-preview"]'],
    busy: ['[aria-busy="true"]', '[data-testid="upload-progress"]', '[class*="uploading"]']
  };
  const presets = {
    fixture: { input: ['#prompt'], user: ['.fixture-user'], answer: ['.fixture-answer'], send: ['#send'],
      stop: ['#stop'], regenerate: ['#regenerate'], upload: ['#files'], attachments: ['.fixture-file'], busy: ['#upload-busy'] },
    deepseek: { input: ['textarea[placeholder]', 'textarea'], answer: ['.ds-markdown'],
      user: ['.fbb737a4', '[class*="user-message"]', '[data-role="user"]', '[class*="message-user"]'],
      send: ['[role="button"][aria-label="发送"]', 'div.ds-button--primary.ds-button--filled.ds-button--circle[role="button"]', 'button[type="submit"]'],
      upload: ['input[type="file"]', '[role="button"][aria-label*="上传"]', '[role="button"][title*="上传"]'],
      attachments: ['[class*="file-card"]', '[class*="attachment"]'] },
    glm: { input: ['textarea', '[contenteditable="true"]'], answer: ['.conversation-item.assistant .markdown-body', '.assistant-message .markdown-body', '[class*="assistant"] [class*="markdown"]', '[class*="answer"] [class*="markdown"]', '.markdown-body'],
      user: ['[class*="user-message"]', '[class*="message-user"]', '.conversation-item.user', '.user .message-content'],
      send: ['div.input-wrap > div.enter', 'button.send-button', '.enter[role="button"]', 'button[aria-label="发送消息"]'],
      upload: ['input[type="file"]', 'button.upload-button', '[role="button"][aria-label*="上传"]'] },
    kimi: { input: ['[contenteditable="true"]', 'textarea'], answer: ['[class*="segment-assistant"] [class*="markdown"]', '[class*="chat-content-item-assistant"]'],
      user: ['[class*="segment-user"]', '[class*="chat-content-item-user"]'] },
    doubao: { input: ['textarea', '[contenteditable="true"]'], answer: ['[data-testid="receive_message"]', '[class*="message-assistant"]', '[class*="assistant"] [class*="markdown"]'],
      user: ['[data-testid="send_message"]', '[class*="message-user"]'] },
    yuanbao: { input: ['[contenteditable="true"]', 'textarea'], answer: ['.agent-chat__bubble--ai', '[class*="agent-chat"] [class*="markdown"]'],
      user: ['.agent-chat__bubble--human', '[class*="bubble--user"]'] },
    hunyuan: { input: ['textarea', '[contenteditable="true"]'] },
    gemini: { input: ['rich-textarea [contenteditable="true"]', '.ql-editor[contenteditable="true"]'], answer: ['model-response message-content', 'model-response'], user: ['user-query-content .query-text', 'user-query'],
      send: ['button.send-button', 'button[aria-label="Send message"]', 'button[aria-label="发送消息"]', 'button[aria-label="发送"]'], stop: ['button.stop-button'],
      upload: ['input[type="file"]', 'button.upload-file-button'],
      uploadMenu: ['button[aria-label="Add files"]', 'button[aria-label="添加文件"]', 'button[aria-label="添加文件和工具"]', 'button[aria-label="Add files and tools"]', 'button.upload-menu-button'],
      attachments: ['file-preview', 'attachment-preview', '.file-preview', '.attachment-preview'],
      regenerate: ['button[aria-label="Regenerate"]'] },
    chatgpt: { input: ['#prompt-textarea'], answer: ['[data-message-author-role="assistant"]'], user: ['[data-message-author-role="user"]'],
      send: ['button[data-testid="send-button"]', 'button[aria-label="Send prompt"]'],
      stop: ['button[data-testid="stop-button"]'], regenerate: ['button[aria-label="Regenerate response"]'] },
    claude: { input: ['[contenteditable="true"][data-placeholder]', '[contenteditable="true"]'],
      answer: ['[data-is-streaming]', '[data-testid="assistant-message"]', '[class*="font-claude-response"]'],
      user: ['[data-testid="user-message"]', '[class*="font-user-message"]'],
      send: ['button[aria-label="Send message"]', 'button[aria-label="发送消息"]'],
      stop: ['button[aria-label="Stop response"]', 'button[aria-label="停止回答"]'] },
    grok: { input: ['textarea', '[contenteditable="true"]'], answer: ['[data-role="assistant"]', '[class*="message-bubble"] [class*="markdown"]'],
      user: ['[data-role="user"]', '[class*="message-bubble"][data-role="user"]'] },
    meta: { input: ['textarea', '[contenteditable="true"]'], answer: ['[data-role="assistant"]'], user: ['[data-role="user"]'] }
  };
  const p = presets[cfg.adapter] || {};
  let current = null, uploadExpectation = null, changedAt = Date.now(), revision = 0;
  const observer = new MutationObserver(() => { changedAt = Date.now(); revision++; });
  observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true });
  function visible(node) {
    if (!node || !node.isConnected || node.hidden) return false;
    const style = getComputedStyle(node);
    return style.display !== 'none' && style.visibility !== 'hidden' && node.getClientRects().length > 0;
  }
  function all(kind) {
    const selectors = (p[kind] || []).concat(common[kind] || []), found = [];
    for (const selector of selectors) {
      let nodes; try { nodes = document.querySelectorAll(selector); } catch (_) { continue; }
      for (const node of nodes) if (visible(node) && found.indexOf(node) < 0) found.push(node);
      // Provider-specific message selectors take precedence over generic fallbacks.
      if (found.length && (kind === 'answer' || kind === 'user' || kind === 'input')) break;
    }
    return found.filter(node => !found.some(other => other !== node && other.contains(node)));
  }
  function fileInput() {
    // File inputs are normally hidden. Prefer the current composer; never pick a disabled one.
    const scope = composerScope();
    const local = Array.from(scope.querySelectorAll('input[type="file"]'));
    const candidates = local.length ? local : Array.from(document.querySelectorAll('input[type="file"]'));
    const usable = candidates.filter(n => n.isConnected && !n.disabled && n.getAttribute('aria-disabled') !== 'true');
    if (usable.length === 1) return usable[0];
    // Fixture has a deterministic input; ambiguous real-page uploaders require takeover.
    if (cfg.adapter === 'fixture') return document.querySelectorAll('#files')[0] || null;
    return null;
  }
  function label(node) { return (node.getAttribute('aria-label') || node.getAttribute('title') || node.innerText || '').trim(); }
  function enabled(node) {
    return visible(node) && !node.disabled && node.getAttribute('aria-disabled') !== 'true' &&
      !/(?:^|\s)(?:ds-button--disabled|ds-icon-button--disabled|disabled)(?:\s|$)/.test(node.className || '');
  }
  function button(kind, labels) {
    const scoped = kind === 'send' || kind === 'upload' || kind === 'uploadMenu';
    const scope = scoped ? composerScope() : document;
    const configured = all(kind).find(node => enabled(node) && node.tagName !== 'INPUT' && (!scoped || scope.contains(node)));
    if (configured) return configured;
    const candidates = Array.from(scope.querySelectorAll('button,[role="button"],.ds-icon-button'));
    const matches = candidates.filter(node => enabled(node) && labels.indexOf(label(node).toLowerCase()) >= 0);
    if (matches.length === 1) return matches[0];
    // Material icons expose semantic names even when the surrounding button has no label.
    const icons = kind === 'send' ? ['send','arrow_upward'] : kind === 'upload' ? ['attach_file','upload_file'] : [];
    const semantic = candidates.filter(node => enabled(node) && Array.from(node.querySelectorAll('mat-icon,[data-icon],svg title')).some(icon =>
      icons.indexOf((icon.getAttribute('data-icon') || plain(icon)).trim().toLowerCase()) >= 0));
    if (semantic.length === 1) return semantic[0];
    // DeepSeek's unlabelled send glyph. Match its known path, not the last/first icon button.
    if (kind === 'send' && cfg.adapter === 'deepseek') {
      const arrows = candidates.filter(node => enabled(node) && Array.from(node.querySelectorAll('svg path')).some(path =>
        (path.getAttribute('d') || '').startsWith('M8.3125 0.981587') && (path.getAttribute('d') || '').includes('V15.0431H7')));
      if (arrows.length === 1) return arrows[0];
    }
    return null;
  }
  function composerScope() {
    const input = all('input')[0];
    if (!input) return document;
    const form = input.closest('form');
    if (form) return form;
    const known = input.closest('input-container,chat-input,.input-area-container,.input-container,.input-wrap,[data-testid="composer"]');
    if (known) return known;
    // Editor wrappers are often several levels below their sibling toolbar.
    let node = input.parentElement, scope = null;
    for (let depth = 0; node && node !== document.body && depth < 7; depth++, node = node.parentElement) {
      if (node.querySelectorAll('model-response,.ds-markdown,[data-message-author-role],.markdown-body').length) break;
      if (node.querySelectorAll('button,[role="button"],.ds-icon-button').length) scope = node;
    }
    return scope || input.parentElement && input.parentElement.parentElement || document;
  }
  const uploadLabels = ['attach files','upload file','upload files','upload from computer','上传文件','上传附件','添加附件','上传图片或文件','从设备上传','从电脑上传'];
  function uploadItem() {
    const items = Array.from(document.querySelectorAll('[role="menuitem"],.mat-mdc-menu-item'));
    const matches = items.filter(n => enabled(n) && uploadLabels.indexOf(label(n).toLowerCase()) >= 0);
    return matches.length === 1 ? matches[0] : null;
  }
  function upload(args) {
    uploadExpectation = Array.isArray(args.names) && args.names.length ? args.names : null;
    const target = fileInput() || uploadItem() || (!args.continuation && button('upload', uploadLabels));
    if (target) { target.click(); return { requested:true }; }
    if (!args.continuation) {
      const menu = button('uploadMenu', ['add files','add files and tools','添加文件','添加文件和工具']);
      if (menu) { menu.click(); return { menuOpened:true }; }
    }
    return { error:'UPLOAD_CONTROL_NOT_FOUND' };
  }
  function plain(node) { return (node && (node.innerText || node.textContent) || '').replace(/\r/g, '').trim(); }
  function normalize(s) { return String(s || '').replace(/\s+/g, ' ').trim(); }
  function questionMatches(node, expected) {
    const text = normalize(plain(node));
    if (text === expected || (expected.length > 256 && text.indexOf(expected.slice(0,128)) >= 0 && text.indexOf(expected.slice(-128)) >= 0)) return true;
    // A message wrapper may also contain attachment names and edit/copy labels.
    return Array.from(node.querySelectorAll('p,div,[data-role="content"]')).some(n => normalize(plain(n)) === expected);
  }
  function matchingBubbles(text) {
    const expected = normalize(text), input = all('input')[0], answers = all('answer');
    if (!expected) return [];
    return Array.from(document.querySelectorAll('main div,main p,[role="main"] div,[role="main"] p')).filter(n =>
      visible(n) && normalize(plain(n)) === expected && n !== input && !(input && (n.contains(input) || input.contains(n))) &&
      !answers.some(a => a === n || a.contains(n) || n.contains(a)));
  }
  function fingerprint(s) {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(16) + ':' + s.length;
  }
  function markdown(root) {
    function walk(node) {
      if (node.nodeType === 3) return node.nodeValue || '';
      if (node.nodeType !== 1 || ['BUTTON','SCRIPT','STYLE','SVG','NAV','MAT-ICON','MESSAGE-ACTIONS','COPY-BUTTON','COPY-TABLE-BUTTON','REGENERATE-BUTTON','PROMPT-COPY-BUTTON','PROMPT-EDIT-BUTTON'].indexOf(node.tagName) >= 0 || node.hidden) return '';
      const tag = node.tagName;
      if (tag === 'PRE') {
        const code = node.querySelector('code') || node;
        const lang = ((code.className || '').match(/language-([\w+-]+)/) || [])[1] || '';
        const body = code.textContent || '';
        const fence = body.indexOf('```') >= 0 ? '````' : '```';
        return '\n' + fence + lang + '\n' + body + '\n' + fence + '\n';
      }
      if (tag === 'BR') return '\n';
      const body = Array.from(node.childNodes).map(walk).join('');
      if (tag === 'CODE') return '`' + body + '`';
      if (tag === 'STRONG' || tag === 'B') return '**' + body + '**';
      if (tag === 'EM' || tag === 'I') return '*' + body + '*';
      if (/^H[1-6]$/.test(tag)) return '\n' + '#'.repeat(Number(tag[1])) + ' ' + body + '\n';
      if (tag === 'LI') return '\n- ' + body.trim();
      if (tag === 'A') {
        const href = node.getAttribute('href') || '';
        if (/^https?:\/\//i.test(href)) return '[' + body.replace(/\]/g, '\\]') + '](' + href.replace(/\)/g, '%29') + ')';
      }
      if (tag === 'TABLE') {
        const rows = Array.from(node.querySelectorAll('tr')).map(tr => Array.from(tr.querySelectorAll('th,td')).map(c => plain(c).replace(/\|/g, '\\|').replace(/\n/g,' ')));
        if (rows.length) return '\n' + rows.map((r,i) => '| ' + r.join(' | ') + ' |' + (i === 0 ? '\n| ' + r.map(() => '---').join(' | ') + ' |' : '')).join('\n') + '\n';
      }
      return ['P','DIV','BLOCKQUOTE','UL','OL','SECTION'].indexOf(tag) >= 0 ? '\n' + body + '\n' : body;
    }
    return walk(root).replace(/\n{3,}/g, '\n\n').trim().slice(0, 500000);
  }
  function completionControl(answer) {
    let scope = answer;
    for (let i=0; scope && i<4; i++,scope=scope.parentElement) {
      if (Array.from(scope.querySelectorAll('copy-button,copy-response-button')).some(visible)) return true;
      if (Array.from(scope.querySelectorAll('button,[role="button"]')).some(n => visible(n) &&
        (/^(copy(?: response| answer| text)?|复制(?:回答|内容|文本)?|重新生成|regenerate|good response|bad response)(?:\s*\([^)]*\))?$/i.test(label(n)) ||
          Array.from(n.querySelectorAll('mat-icon,[data-icon]')).some(icon => /^(content_copy|copy)$/.test((icon.getAttribute('data-icon') || plain(icon)).trim()))))) return true;
    }
    return false;
  }
  function probe() {
    const input = all('input')[0], answers = all('answer'), users = all('user');
    const stop = button('stop', ['stop generating','stop','停止生成','停止回答','停止']);
    const body = (document.body.innerText || '').slice(0, 18000);
    const challenge = Boolean(document.querySelector('iframe[src*="captcha"],iframe[src*="challenges.cloudflare.com"]')) ||
      /verify you are human|验证您是人类|请完成安全验证|异常验证|disallowed_useragent/i.test(body);
    const login = !input && (Boolean(document.querySelector('input[type="password"]')) ||
      /sign in to continue|log in to continue|登录后使用|请先登录|登录以继续/i.test(body));
    const alerts = Array.from(document.querySelectorAll('[role="alert"],[data-testid="rate-limit-banner"],[data-testid="error-toast"]')).filter(visible).map(plain).join('\n');
    const limited = /too many requests|rate limit|已达到.*上限|今日.*次数.*用完|请求过于频繁/i.test(alerts);
    const last = answers[answers.length - 1];
    let answer = '', answerId = '', userConfirmed = false, fresh = false, done = false;
    if (current) {
      const expected = normalize(current.text);
      let userNode = users.slice(-1).find(n => expected && questionMatches(n, expected));
      userConfirmed = Boolean(userNode && (current.recovery || current.operation === "REGENERATE" || users.length > current.userCount));
      if (!userConfirmed && !current.recovery && current.operation === 'SEND' && users.length <= current.userCount) {
        const bubbles = matchingBubbles(current.text);
        const newBubble = bubbles.filter(n => !(current.baselineBubbles || []).includes(n)).pop();
        if (newBubble && bubbles.length > (current.baselineBubbles || []).length) { userNode = newBubble; userConfirmed = true; }
      }
      if (stop) current.sawGenerating = true;
      if (last && userConfirmed) {
        const text = markdown(last);
        const id = last.getAttribute('data-message-id') || last.id || fingerprint(text);
        const afterUser = Boolean(userNode.compareDocumentPosition(last) & Node.DOCUMENT_POSITION_FOLLOWING);
        fresh = afterUser && (answers.length > current.answerCount || id !== current.lastAnswerId || text !== current.lastAnswerText || (current.operation === 'REGENERATE' && current.sawGenerating));
        if (fresh) {
          answer = text; answerId = id;
          if (current.lastText !== answer) { current.lastText = answer; current.lastTextAt = Date.now(); }
          const stable = Date.now() - current.lastTextAt >= 1800;
          done = Boolean(answer && !stop && stable && (current.sawGenerating || completionControl(last)));
        }
      }
    }
    const scope = composerScope(), fi = fileInput();
    const attachmentNodes = all('attachments');
    const fileNames = attachmentNodes.map(plain).filter(Boolean);
    const uploading = all('busy').length > 0;
    const capabilities = { input: Boolean(input), send: Boolean(button('send',['send','send message','submit','发送','发送消息','提交'])),
      upload: Boolean(fi || button('upload', uploadLabels) || button('uploadMenu',['add files','add files and tools','添加文件','添加文件和工具'])),
      stop: Boolean(stop), regenerate: Boolean(button('regenerate',['regenerate','regenerate response','重新生成','重新回答'])) };
    return { version: cfg.version, requestId: current ? current.requestId : '', revision, status: challenge ? 'HUMAN_VERIFICATION_REQUIRED' : login ? 'LOGIN_REQUIRED' : limited ? 'RATE_LIMITED' : (input || (userConfirmed && last)) ? 'READY' : 'INPUT_NOT_FOUND',
      capabilities, userCount: users.length, answerCount: answers.length, lastAnswerId: last ? (last.getAttribute('data-message-id') || last.id || fingerprint(markdown(last))) : '',
      lastAnswerText: last ? markdown(last) : '', answer, answerId, userConfirmed, generating: Boolean(stop), done,
      lastUserText: users.length ? plain(users[users.length-1]) : '', generationFailed: Boolean(current && fresh && last && last.getAttribute('data-failed') === 'true'),
      uploadReady: !uploading && (uploadExpectation ? uploadExpectation.every(name => fileNames.some(label => label.indexOf(name) >= 0) ||
        (cfg.adapter === 'fixture' && fi && Array.from(fi.files || []).some(file => file.name === name))) : Boolean((fi && fi.files && fi.files.length) || attachmentNodes.length)), fileNames,
      attachmentCount: attachmentNodes.length, changedAt, url: location.href };
  }
  function begin(args) {
    const before = probe();
    if (before.status !== 'READY') return { error: before.status };
    if (before.generating) return { error: 'PAGE_ALREADY_GENERATING' };
    if (current && current.requestId === args.requestId) return { error: 'DUPLICATE_SUBMISSION_BLOCKED' };
    const input = all('input')[0];
    if (!input) return { error: 'INPUT_NOT_FOUND' };
    if (args.operation === 'REGENERATE') {
      const regen = button('regenerate',['regenerate','regenerate response','重新生成','重新回答']);
      if (!regen) return { error: 'REGENERATE_UNSUPPORTED' };
      current = { requestId: args.requestId, text: args.text, userCount: before.userCount, operation: args.operation, answerCount: before.answerCount,
        lastAnswerId: before.lastAnswerId, lastAnswerText: before.lastAnswerText, lastText: '', lastTextAt: Date.now(), sawGenerating: false };
      regen.click(); return { submitted: true };
    }
    if (args.operation !== 'SEND') return { error: 'EDIT_UNSUPPORTED' };
    // A user's manually entered draft must not be silently overwritten.
    if (normalize(input.value === undefined ? plain(input) : input.value)) return { error: 'COMPOSER_HAS_DRAFT' };
    const baselineBubbles = matchingBubbles(args.text);
    input.focus();
    if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
      const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(input, args.text);
    } else {
      // Rich editors (ProseMirror/Lexical) must receive an editing operation, not just a DOM mutation.
      const inserted = typeof document.execCommand === 'function' && document.execCommand('insertText', false, args.text);
      if (!inserted) input.textContent = args.text;
    }
    input.dispatchEvent(typeof InputEvent === 'function' ? new InputEvent('input', {bubbles:true, inputType:'insertText', data:args.text}) : new Event('input', {bubbles:true}));
    input.dispatchEvent(new Event('change', {bubbles:true}));
    if (normalize(input.value === undefined ? plain(input) : input.value) !== normalize(args.text)) return { error:'INPUT_WRITE_UNCONFIRMED' };
    current = { requestId: args.requestId, text: args.text, userCount: before.userCount, operation: args.operation, answerCount: before.answerCount,
      lastAnswerId: before.lastAnswerId, lastAnswerText: before.lastAnswerText, lastText: '', lastTextAt: Date.now(), sawGenerating: false, clicked:false, baselineBubbles };
    return { prepared: true };
  }
  function commit(args) {
    if (!current || current.requestId !== args.requestId || current.clicked) return { error:'DUPLICATE_SUBMISSION_BLOCKED' };
    const input = all('input')[0];
    if (!input || normalize(input.value === undefined ? plain(input) : input.value) !== normalize(current.text)) return { error:'INPUT_CHANGED_BEFORE_SEND' };
    const send = button('send',['send','send message','submit','发送','发送消息','提交']);
    if (!send) return { error:'SEND_CONTROL_NOT_FOUND' };
    current.clicked = true; send.click(); return { submitted:true };
  }
  const api = {
    version: cfg.version,
    command(name,args) {
      if (location.protocol !== 'https:' || cfg.hosts.indexOf(location.hostname) < 0) return { error:'UNTRUSTED_PAGE_ORIGIN' };
      try {
        if (name === 'probe') return probe();
        if (name === 'newConversation') {
          const control = Array.from(document.querySelectorAll('button,[role="button"],a')).find(n => visible(n) &&
            /^(new chat|new conversation|start new chat|新对话|开启新对话|新建对话|新建聊天)$/i.test(label(n)));
          if (!control) return {error:'NEW_CONVERSATION_CONTROL_NOT_FOUND'};
          current = null; control.click(); return {requested:true};
        }
        if (name === 'begin') return begin(args);
        if (name === 'commit') return commit(args);
        if (name === 'observeManualSend') {
          if (!current || current.requestId !== args.requestId || current.clicked) return {error:'DUPLICATE_SUBMISSION_BLOCKED'};
          current.clicked = true; return {observing:true};
        }
        if (name === 'recover') {
          const before = probe();
          current = { requestId:args.requestId, text:args.text, recovery:true, answerCount:Math.max(0,before.answerCount-1),
            lastAnswerId:'', lastAnswerText:'', lastText:'', lastTextAt:Date.now(), sawGenerating:false, clicked:true };
          return probe();
        }
        if (name === 'stop') {
          const stop = button('stop',['stop generating','stop','停止生成','停止回答','停止']);
          if (stop) { stop.click(); return { requested:true }; }
          return { requested:false, generating:false };
        }
        if (name === 'upload') return upload(args);
        if (name === 'disconnect') { observer.disconnect(); delete window[key]; return { disconnected:true }; }
        return { error:'UNKNOWN_ADAPTER_COMMAND' };
      } catch (_) { return { error:'ADAPTER_OPERATION_FAILED' }; }
    }
  };
  Object.defineProperty(window,key,{ value:api, configurable:true });
})(__HARNESS_KEY__, __HARNESS_CONFIG__);
