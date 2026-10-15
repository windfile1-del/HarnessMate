#!/usr/bin/env python3
"""Real upstream LSP workload: initialize, definition, references, error then fix."""
import argparse
import importlib.util
import json
import hashlib
import os
import platform
from pathlib import Path
import shutil
import sys
import tempfile
import time

FIXTURES = {
    'python': ('Main.py', 'def greeting():\n    return "ok"\nvalue: int = "bad"\ngreeting()\n', '"bad"', '1'),
    'typescript': ('Main.ts', 'function greeting() { return "ok"; }\nconst value: number = "bad";\ngreeting();\n', '"bad"', '1'),
    'javascript': ('Main.js', '// @ts-check\nfunction greeting() { return "ok"; }\n/** @type {number} */\nlet value = "bad";\ngreeting();\n', '"bad"', '1'),
    'kotlin': ('Main.kt', 'fun greeting(): String = "ok"\nval value: Int = "bad"\nfun main() { println(greeting()) }\n', '"bad"', '1'),
    'java': ('Main.java', 'public class Main {\n  static String greeting() { return "ok"; }\n  int value = "bad";\n  public static void main(String[] args) { System.out.println(greeting()); }\n}\n', '"bad"', '1'),
}

def source_snapshot(root):
    # Same cache/output exclusions as P3. Keep project metadata visible: a
    # language server must not silently rewrite .project/.classpath/.settings.
    ignored = {'.git', '.gradle', 'build', 'out', 'target', 'node_modules', 'dist', '.idea', '.build-', '__pycache__'}
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in root.rglob('*') if path.is_file()
            and not any(part in ignored or part.endswith(('.class', '.apk', '.tmp')) for part in path.relative_to(root).parts)}

def command_table(tools, java, node, host, work):
    launch = next((tools / 'java/plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    return {
        'kotlin': [[java, '-cp', str(tools / 'kotlin/server/lib/*'), 'org.javacs.kt.MainKt']],
        'java': [[java, '-Declipse.application=org.eclipse.jdt.ls.core.id1', '-Dosgi.bundles.defaultStartLevel=4',
                 '-Djava.import.generatesMetadataFilesAtProjectRoot=false',
                 '-Declipse.product=org.eclipse.jdt.ls.core.product', '-Dlog.level=WARNING', '-Xmx512m',
                 '--add-modules=ALL-SYSTEM', '--add-opens', 'java.base/java.util=ALL-UNNAMED',
                 '--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', str(launch),
                 '-configuration', str(tools / 'java' / ('config_win' if host and os.name == 'nt' else
                     'config_linux_arm' if platform.machine().lower() in ('aarch64', 'arm64') else 'config_linux')),
                 '-data', str(work / 'jdt-data')]],
        'python': [[node, str(tools / 'node_modules/pyright/langserver.index.js'), '--stdio']],
        'typescript': [[node, str(tools / 'node_modules/typescript-language-server/lib/cli.mjs'), '--stdio']],
        'javascript': [[node, str(tools / 'node_modules/typescript-language-server/lib/cli.mjs'), '--stdio']],
    }

def run_probe(tools, daemon_path, host=False, java='java', node='node', languages=None):
    tools = tools.resolve()
    daemon_path = daemon_path.resolve()
    spec = importlib.util.spec_from_file_location('vega_lsp_probe_daemon', daemon_path)
    daemon = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(daemon)
    results = {}
    with tempfile.TemporaryDirectory(prefix='vega-lsp-probe-') as temp:
        work = Path(temp)
        if host:
            daemon.SERVER_ALLOWLIST = command_table(tools, java, node, host, work)
        for language in languages or FIXTURES:
            root = work / language
            root.mkdir()
            filename, broken, wrong, fixed = FIXTURES[language]
            source = root / filename
            source.write_text(broken, encoding='utf-8')
            (root / 'pyrightconfig.json').write_text('{"typeCheckingMode":"basic"}', encoding='utf-8')
            (root / 'tsconfig.json').write_text('{"compilerOptions":{"allowJs":true,"checkJs":true,"strict":true,"skipLibCheck":true}}', encoding='utf-8')
            if language == 'java':
                (root / '.project').write_text('<projectDescription><name>VegaLspProbe</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>', encoding='utf-8')
                (root / '.classpath').write_text('<classpath><classpathentry kind="src" path=""/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/></classpath>', encoding='utf-8')
            initial = source_snapshot(root)
            server = daemon.LanguageServer(language, root.as_uri())
            try:
                ready, detail = server.start()
                if not ready:
                    raise RuntimeError(language + ': initialize: ' + detail)
                index = broken.rfind('greeting')
                prefix = broken[:index]
                params = {'textDocument': {'uri': source.as_uri()},
                          'position': {'line': prefix.count('\n'), 'character': len(prefix.rsplit('\n', 1)[-1])}}
                deadline = time.monotonic() + 90
                while True:
                    definition = server.request('textDocument/definition', params, 20)
                    if definition.get('result'):
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError(language + ': definition: ' + repr(definition))
                    time.sleep(0.25)
                references = server.request('textDocument/references', dict(params, context={'includeDeclaration': True}), 30)
                if not references.get('result'):
                    raise RuntimeError(language + ': references: ' + repr(references))
                diagnostic_params = {'textDocument': {'uri': source.as_uri()}}
                deadline = time.monotonic() + 30
                while True:
                    errors = server.request('textDocument/diagnostic', diagnostic_params, 15)
                    if any(item.get('severity', 1) == 1 for item in errors.get('result', {}).get('items', [])):
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError(language + ': missing real error diagnostics: ' + repr(errors))
                    time.sleep(0.25)
                source.write_text(broken.replace(wrong, fixed), encoding='utf-8')
                initial[filename] = hashlib.sha256(source.read_bytes()).hexdigest()
                deadline = time.monotonic() + 30
                while True:
                    clean = server.request('textDocument/diagnostic', diagnostic_params, 15)
                    if 'result' in clean and not any(item.get('severity', 1) == 1 for item in clean['result'].get('items', [])):
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError(language + ': errors remain after fix: ' + repr(clean))
                final = source_snapshot(root)
                if initial != final:
                    changed = sorted(key for key in set(initial) | set(final) if initial.get(key) != final.get(key))
                    raise RuntimeError(language + ': server mutated project files: ' + repr(changed))
                results[language] = {'initialize': True, 'definition': True, 'references': True,
                                     'errorDiagnostics': True, 'fixedDiagnostics': True, 'sourceUnchanged': True}
                print('PASS', language, json.dumps(results[language]), flush=True)
            except Exception:
                print('SERVER STDERR', language, ''.join(server.handles.stderr_lines), file=sys.stderr, flush=True)
                log = work / 'jdt-data/.metadata/.log'
                if log.exists():
                    print(log.read_text('utf-8', errors='replace')[-20000:], file=sys.stderr, flush=True)
                raise
            finally:
                if not server.stop():
                    raise RuntimeError(language + ': stop not confirmed')
    return results

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tools', type=Path, default=Path('/opt/vega-lsp'))
    parser.add_argument('--daemon', type=Path)
    parser.add_argument('--host', action='store_true')
    parser.add_argument('--java', default='java')
    parser.add_argument('--node', default='node')
    parser.add_argument('--language', action='append')
    parser.add_argument('--result', type=Path)
    args = parser.parse_args()
    results = run_probe(args.tools, args.daemon or args.tools / 'lsp_daemon.py', args.host, args.java, args.node, args.language)
    if args.result:
        args.result.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    print('VEGA_LSP_READY', flush=True)

if __name__ == '__main__':
    main()
