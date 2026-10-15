# Host rendering verification

No APK, Android emulator or handset is needed. These tests do not establish
Android WebView or OEM acceptance.

`run-browser.cjs` uses an installed Edge/Chrome in headless mode and loads the
actual App assets at the production HTTPS origin through local Playwright
routes. Other network destinations are aborted and counted. It checks
Markdown, four TeX delimiters, actual MathML, local fonts, exact copying,
untrusted content, narrow layouts and dynamic height. Screenshots and summary
are written to `.build-chat-render/test-artifacts`.

Prepare the pinned host browser driver inside the ignored workspace scratch
directory, then run the test:

```powershell
npm install --prefix .build-chat-render --cache .build-chat-render/npm-cache --ignore-scripts --save-dev playwright-core@1.64.0
node tests/chat-rich-render/run-browser.cjs
```

`HostKotlinRegressionTest.java` reflects into the actual compiled
`RichResponseView` and `MarkdownRenderer` classes. It does not reproduce their
implementations. Compile production with `tools/runtime/verify_p7_runtime_skills.py`
first, compile this Java file, then put its output directory, the current
production classes, production Java classes, Android 35 jar and upstream
Kotlin runtime jars on the Java classpath. If using a validated compilation
delta, put it before the baseline.

The latest verified results and limits are in
`docs/CHAT-RENDER-AND-VPN-DOWNLOAD-2026-10-08.md`.
