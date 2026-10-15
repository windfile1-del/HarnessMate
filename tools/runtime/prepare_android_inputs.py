#!/usr/bin/env python3
"""Explicit development-machine discovery of the R3 portable Maven seed.

No downloads occur on the phone. Normal bundle builds use android-tools-lock.json.
"""
import json
import shutil
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from build_jvm_bundle import digest

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.build-linux-r3'
REPO = WORK / 'maven'
GOOGLE = 'https://dl.google.com/dl/android/maven2/'
CENTRAL = 'https://repo.maven.apache.org/maven2/'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def fetch(key, optional=False):
    target = REPO / key
    if target.is_file():
        return target
    for base in ((GOOGLE, CENTRAL) if key.startswith(('com/android/', 'androidx/', 'com/google/testing/')) else (CENTRAL, GOOGLE)):
        try:
            with urllib.request.urlopen(base + key, timeout=60) as response:
                data = response.read()
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            return target
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    if not optional:
        raise ValueError('Missing official Maven input: ' + key)

def main():
    REPO.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / '.build-linux-r2/extracted/maven', REPO, dirs_exist_ok=True)
    queue = [('com.android.tools.build', 'gradle', '8.7.2', False),
             ('com.android.application', 'com.android.application.gradle.plugin', '8.7.2', True),
             ('com.android.library', 'com.android.library.gradle.plugin', '8.7.2', True),
             ('org.jetbrains.kotlin.android', 'org.jetbrains.kotlin.android.gradle.plugin', '2.0.21', True),
             ('androidx.annotation', 'annotation', '1.8.2', False)]
    # Exact inherited/version-managed edges confirmed by a cold Gradle resolution.
    queue += [(g, a, v, False) for g, a, v in [
        ('org.bouncycastle', 'bcutil-jdk18on', '1.77'),
        ('jakarta.xml.bind', 'jakarta.xml.bind-api', '2.3.2'),
        ('com.sun.istack', 'istack-commons-runtime', '3.0.8'),
        ('org.jvnet.staxex', 'stax-ex', '1.8.1'),
        ('com.sun.xml.fastinfoset', 'FastInfoset', '1.2.16'),
        ('jakarta.activation', 'jakarta.activation-api', '1.2.1'),
        ('com.google.j2objc', 'j2objc-annotations', '2.8'),
        ('org.checkerframework', 'checker-qual', '3.33.0'),
        ('commons-codec', 'commons-codec', '1.11'),
        ('commons-logging', 'commons-logging', '1.2')]]
    seen = set()
    while queue:
        group, artifact, version, pom_only = queue.pop()
        coordinate = (group, artifact, version)
        if coordinate in seen:
            continue
        seen.add(coordinate)
        prefix = group.replace('.', '/') + '/' + artifact + '/' + version + '/'
        stem = prefix + artifact + '-' + version
        pom = fetch(stem + '.pom')
        model = ET.fromstring(pom.read_bytes())
        def val(element, name, default=''):
            return element.findtext('m:' + name, default, NS).strip()
        props = {'project.version': version, 'project.groupId': group, 'project.artifactId': artifact}
        properties = model.find('m:properties', NS)
        if properties is not None:
            props.update({child.tag.split('}')[-1]: child.text or '' for child in properties})
        def expand(value):
            for _ in range(10):
                old = value
                for key, replacement in props.items():
                    value = value.replace('${' + key + '}', replacement)
                if value == old:
                    break
            return value
        packaging = val(model, 'packaging', 'jar')
        if packaging == 'bundle':
            packaging = 'jar'
        module = fetch(stem + '.module', optional=True)
        if module:
            for variant in json.loads(module.read_text('utf-8'))['variants']:
                platform = variant.get('attributes', {}).get('org.jetbrains.kotlin.platform.type')
                if platform not in (None, 'jvm', 'androidJvm', 'common'):
                    continue
                for item in variant.get('files', []):
                    name = item['url']
                    if '/' in name or '..' in name:
                        raise ValueError('Unsafe module file URL')
                    data = fetch(prefix + name).read_bytes()
                    if item.get('sha256') and digest(data) != item['sha256']:
                        raise ValueError('Maven module checksum mismatch')
                for item in variant.get('dependencies', []):
                    selected = item.get('version', {}).get('requires')
                    if selected and not selected.startswith(('[', '(')):
                        queue.append((item['group'], item['module'], selected, False))
                available = variant.get('available-at')
                if available:
                    queue.append((available['group'], available['module'], available['version'], False))
        elif not pom_only and packaging != 'pom':
            fetch(stem + '.' + packaging)
        parent = model.find('m:parent', NS)
        refs = [(parent, True)] if parent is not None else []
        refs += [(item, val(item, 'type', 'jar') == 'pom') for item in model.findall('m:dependencies/m:dependency', NS)
                 if val(item, 'scope', 'compile') in ('compile', 'runtime') and val(item, 'optional') != 'true']
        refs += [(item, True) for item in model.findall('m:dependencyManagement/m:dependencies/m:dependency', NS)
                 if val(item, 'scope') == 'import']
        for ref, only in refs:
            g, a, v = [expand(val(ref, key)) for key in ('groupId', 'artifactId', 'version')]
            if v and '${' not in v and not v.startswith(('[', '(')):
                queue.append((g, a, v, only))
        print('Collected', ':'.join(coordinate), flush=True)
    print('R3 Maven discovery complete', len(seen), flush=True)

if __name__ == '__main__':
    main()
