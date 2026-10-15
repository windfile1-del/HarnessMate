"""Trusted persistent mirror RPC helper. Loaded from the APK, not from the project."""
import base64
import contextlib
import fnmatch
import hashlib
import http.client
import io
import json
import os
import re
import shutil
import signal
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import threading
import urllib.request
import urllib.parse
import zipfile

CONTROL_LIMIT = 4 * 1024 * 1024
TEXT_LIMIT = 1024 * 1024  # Bounded RPC; canonical journal has its own stricter limits.
RESERVE = 512 * 1024 * 1024
MAX_FILES = 200_000
MANIFEST_PAGE_LIMIT = 2048
MANIFEST_PAGE_BYTES = 1024 * 1024
MAX_ARCHIVE_EXPANDED_BYTES = 4 * 1024 * 1024 * 1024
MAX_EXPANSION_RATIO = 200


def manifest_digest(entries):
    """Match Kotlin ManifestDigest: stable content identity, never provider mtime."""
    digest = hashlib.sha256()
    def append(value):
        raw = value.encode('utf-8')
        digest.update(len(raw).to_bytes(4, 'big'))
        digest.update(raw)
    for entry in sorted(entries, key=lambda item: item['path']):
        append(entry['path'])
        append('D' if entry['directory'] else 'F')
        append(str(entry['size']))
        append(entry.get('sha256') or '-')
    return digest.hexdigest()


class BoundedArchiveReader:
    """Archive parsers must not allocate a member-sized metadata read."""
    def __init__(self, stream):
        self.stream = stream
    def read(self, size=-1):
        if size < 0 or size > CONTROL_LIMIT:
            raise ValueError('ARCHIVE_METADATA_LIMIT')
        return self.stream.read(size)
    def __getattr__(self, name):
        return getattr(self.stream, name)


def post_sink(base_url, sink_id, source, size, digest):
    """Stream exactly ``size`` bytes to the loopback bridge with fixed framing."""
    parts = urllib.parse.urlsplit(base_url)
    if parts.scheme != 'http' or parts.hostname != '127.0.0.1' or parts.port is None:
        raise ValueError('INVALID_RPC_URL')
    path = parts.path.rstrip('/') + '/sink/' + urllib.parse.quote(sink_id, safe='_-')
    connection = http.client.HTTPConnection(parts.hostname, parts.port, timeout=30)
    sent = 0
    try:
        connection.putrequest('POST', path, skip_accept_encoding=True)
        connection.putheader('Content-Length', str(size))
        connection.putheader('Connection', 'close')
        connection.putheader('Content-Type', 'application/octet-stream')
        connection.endheaders()
        while sent < size:
            block = source.read(min(65536, size - sent))
            if not block:
                raise ValueError('MIRROR_EXPORT_TRUNCATED')
            sent += len(block)
            if sent > size:
                raise ValueError('MIRROR_EXPORT_SIZE_MISMATCH')
            digest.update(block)
            connection.send(block)
        if source.read(1):
            raise ValueError('MIRROR_EXPORT_GREW_DURING_SCAN')
        response = connection.getresponse()
        response.read()
        if response.status != 200:
            raise ValueError('MIRROR_SINK_REJECTED')
        return sent
    finally:
        connection.close()


def clean(path):
    if not isinstance(path, str) or path.startswith('/') or '\\' in path or ':' in path:
        raise ValueError('INVALID_EXECUTION_PATH')
    if any(ord(c) < 32 for c in path) or any(p in ('.', '..', '') for p in path.split('/')):
        if path != '':
            raise ValueError('INVALID_EXECUTION_PATH')
    return path


class Mirror:
    def __init__(self, root, config, fetch):
        self.root = root
        self.fetch = fetch
        self.base_url = config.get('_baseUrl', '')
        self.sinks = {item['id']: item for item in config.get('sinks', [])}
        self.ignored_segments = set(config['ignoredSegments'])
        self.ignored_suffixes = tuple(config['ignoredSuffixes'])
        # Every ancestor must be a real directory. Do not follow project-created symlinks.
        current = '/'
        for part in os.path.abspath(root).split('/')[1:]:
            current = os.path.join(current, part)
            try:
                os.mkdir(current, 0o700)
            except FileExistsError:
                pass
            if not stat.S_ISDIR(os.lstat(current).st_mode):
                raise ValueError('MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY')
        self.project = os.path.join(root, 'project')
        for name in ('project', 'home', 'tmp', 'cache', '.vega', 'imports'):
            target = os.path.join(root, name)
            os.makedirs(target, exist_ok=True)
            if not stat.S_ISDIR(os.lstat(target).st_mode):
                raise ValueError('MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY')

    def ignored(self, path):
        return any(p in self.ignored_segments or p.endswith(self.ignored_suffixes) for p in path.split('/'))

    @contextlib.contextmanager
    def parent(self, path):
        parts = clean(path).split('/')
        if not path:
            raise ValueError('ROOT_MUTATION_FORBIDDEN')
        fd = os.open(self.project, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for part in parts[:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = child
            yield fd, parts[-1]
        finally:
            os.close(fd)

    def info(self, path):
        if not path:
            return {'path': '', 'directory': True, 'size': 0, 'modified': 0, 'sha256': None}
        with self.parent(path) as (fd, name):
            s = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if not (stat.S_ISREG(s.st_mode) or stat.S_ISDIR(s.st_mode)):
                raise ValueError('MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE: ' + path)
            return {'path': path, 'directory': stat.S_ISDIR(s.st_mode), 'size': s.st_size if stat.S_ISREG(s.st_mode) else 0,
                    'modified': max(0, s.st_mtime_ns // 1_000_000), 'sha256': None}

    def open(self, path):
        with self.parent(path) as (fd, name):
            handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
            if not stat.S_ISREG(os.fstat(handle).st_mode):
                os.close(handle)
                raise ValueError('NOT_REGULAR_FILE')
            return os.fdopen(handle, 'rb')

    def listing(self, path):
        clean(path)
        if path:
            with self.parent(path) as (parent, name):
                fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        else:
            fd = os.open(self.project, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for name in sorted(os.listdir(fd)):
                yield self.info('/'.join(filter(None, (path, name))))
        finally:
            os.close(fd)

    def manifest(self):
        entries = []
        pending = ['']
        while pending:
            for entry in self.listing(pending.pop()):
                path = entry['path']
                if self.ignored(path):
                    continue
                if len(entries) >= MAX_FILES:
                    raise ValueError('WORKSPACE_FILE_LIMIT')
                if entry['directory']:
                    pending.append(path)
                else:
                    digest = hashlib.sha256()
                    size = 0
                    with self.open(path) as source:
                        while True:
                            block = source.read(65536)
                            if not block:
                                break
                            digest.update(block)
                            size += len(block)
                    if self.info(path) != entry or size != entry['size']:
                        raise ValueError('MIRROR_CHANGED_DURING_SCAN')
                    entry['sha256'] = digest.hexdigest()
                entries.append(entry)
        return entries

    def manifest_page(self, args):
        """Bound the JSON response independently of the total project size."""
        offset = max(0, int(args.get('offset', 0)))
        limit = min(MANIFEST_PAGE_LIMIT, max(1, int(args.get('limit', MANIFEST_PAGE_LIMIT))))
        entries = self.manifest()
        digest = manifest_digest(entries)
        expected_digest = args.get('expectedDigest', '')
        if expected_digest and expected_digest != digest:
            raise ValueError('MIRROR_MANIFEST_CHANGED_DURING_PAGING')
        page = entries[offset:offset + limit]
        # Metadata is normally tiny, but enforce the byte budget even for very
        # long provider names. Reduce the page before returning instead of
        # allowing the bridge's result cap to turn a valid project into an
        # ambiguous callback failure.
        while page and len(json.dumps(page, separators=(',', ':')).encode('utf-8')) > MANIFEST_PAGE_BYTES:
            page = page[:-1]
        if not page and offset < len(entries):
            raise ValueError('MANIFEST_ENTRY_TOO_LARGE')
        next_offset = offset + len(page)
        return {'entries': page, 'offset': offset, 'nextOffset': next_offset if next_offset < len(entries) else -1,
                'totalEntries': len(entries), 'digest': digest}

    def export_file(self, args):
        path = clean(args['path'])
        sink_id = args['sink']
        sink = self.sinks.get(sink_id)
        if sink is None:
            raise ValueError('MIRROR_SINK_UNKNOWN')
        info = self.info(path)
        if info['directory']:
            raise ValueError('MIRROR_EXPORT_DIRECTORY')
        expected_size = int(args['size'])
        expected_hash = args['sha256']
        if expected_size < 0 or expected_size > sink['maxBytes'] or expected_size != info['size']:
            raise ValueError('MIRROR_EXPORT_SIZE_MISMATCH')
        if sink.get('expectedSize', -1) not in (-1, expected_size):
            raise ValueError('MIRROR_SINK_EXPECTED_SIZE')
        if sink.get('expectedSha256', '') not in ('', expected_hash):
            raise ValueError('MIRROR_SINK_EXPECTED_HASH')
        digest = hashlib.sha256()
        count = 0
        with self.open(path) as source:
            count = post_sink(self.base_url, sink_id, source, expected_size, digest)
        if count != expected_size or digest.hexdigest() != expected_hash:
            raise ValueError('MIRROR_EXPORT_HASH_MISMATCH')
        if self.info(path) != info:
            raise ValueError('MIRROR_CHANGED_DURING_EXPORT')
        return {'size': count, 'sha256': digest.hexdigest()}

    def reserve(self, size):
        if size < 0 or shutil.disk_usage(self.root).free < size + RESERVE:
            raise ValueError('INSUFFICIENT_EXECUTION_STORAGE')

    def put(self, path, source, size, expected=None):
        self.reserve(size)
        with self.parent(path) as (fd, name):
            try:
                mode = os.stat(name, dir_fd=fd, follow_symlinks=False).st_mode
                if not stat.S_ISREG(mode):
                    raise ValueError('TARGET_NOT_REGULAR_FILE')
            except FileNotFoundError:
                pass
            temporary = '.vega-transfer-' + os.urandom(12).hex()
            handle = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd)
            try:
                digest = hashlib.sha256()
                count = 0
                with os.fdopen(handle, 'wb') as output:
                    while True:
                        block = source.read(min(65536, size - count + 1))
                        if not block:
                            break
                        count += len(block)
                        if count > size:
                            raise ValueError('PAYLOAD_SIZE_MISMATCH')
                        digest.update(block)
                        output.write(block)
                    if count != size or expected and digest.hexdigest() != expected:
                        raise ValueError('PAYLOAD_HASH_MISMATCH')
                    output.flush()
                    os.fsync(output.fileno())
                os.rename(temporary, name, src_dir_fd=fd, dst_dir_fd=fd)
            finally:
                try:
                    os.unlink(temporary, dir_fd=fd)
                except FileNotFoundError:
                    pass

    def mkdir(self, path):
        with self.parent(path) as (fd, name):
            try:
                os.mkdir(name, 0o700, dir_fd=fd)
            except FileExistsError:
                if not stat.S_ISDIR(os.stat(name, dir_fd=fd, follow_symlinks=False).st_mode):
                    raise ValueError('TARGET_NOT_DIRECTORY')

    def delete(self, path):
        info = self.info(path)
        with self.parent(path) as (fd, name):
            if info['directory']:
                os.rmdir(name, dir_fd=fd)  # never recursively remove caches or unknown children
            else:
                os.unlink(name, dir_fd=fd)

    def text(self, path):
        with self.open(path) as source:
            content = source.read(TEXT_LIMIT + 1)
        if len(content) > TEXT_LIMIT or b'\0' in content:
            raise ValueError('UNSUPPORTED_BINARY_OR_TEXT_LIMIT')
        return content.decode('utf-8', errors='strict')

    def sync(self, args):
        expected_remote = args.get('expectedRemoteDigest', '')
        if expected_remote:
            if not re.fullmatch(r'[0-9a-f]{64}', expected_remote):
                raise ValueError('MIRROR_REMOTE_DIGEST_INVALID')
            actual_remote = manifest_digest(self.manifest())
            if actual_remote != expected_remote:
                raise ValueError('STALE_REMOTE_BEFORE_SYNC')
        plan_sources = args.get('planSources')
        if plan_sources is None:
            # Compatibility for pre-P3 callers and the POSIX helper harness.
            entries = args['entries']
            self.reserve(sum(e['size'] for e in entries if not e['directory']))
            deleted_paths = sorted(args['deleted'], key=lambda p: p.count('/'), reverse=True)
            for path in deleted_paths:
                if self.ignored(path):
                    raise ValueError('IGNORED_SYNC_PATH')
                self.delete(path)
            for entry in entries:
                path = entry['path']
                if self.ignored(path):
                    raise ValueError('IGNORED_SYNC_PATH')
                if entry['directory']:
                    self.mkdir(path)
                else:
                    with self.fetch('file/' + str(entry['source'])) as source:
                        self.put(path, source, entry['size'], entry['sha256'])
            return {'transferredFiles': sum(not e['directory'] for e in entries),
                    'transferredBytes': sum(e['size'] for e in entries if not e['directory']),
                    'deletedPaths': len(deleted_paths)}

        if (not isinstance(plan_sources, list) or len(plan_sources) > MAX_FILES or
                len(set(plan_sources)) != len(plan_sources)):
            raise ValueError('MIRROR_SYNC_PLAN_INVALID')

        def pages():
            for raw_index in plan_sources:
                if isinstance(raw_index, bool) or not isinstance(raw_index, int) or raw_index < 0:
                    raise ValueError('MIRROR_SYNC_PLAN_SOURCE_INVALID')
                with self.fetch('file/' + str(raw_index)) as source:
                    payload = source.read(MANIFEST_PAGE_BYTES + 1)
                if len(payload) > MANIFEST_PAGE_BYTES:
                    raise ValueError('MIRROR_SYNC_PLAN_PAGE_LIMIT')
                try:
                    page = json.loads(payload.decode('utf-8'))
                except (UnicodeError, ValueError):
                    raise ValueError('MIRROR_SYNC_PLAN_PAGE_INVALID')
                if not isinstance(page, dict):
                    raise ValueError('MIRROR_SYNC_PLAN_PAGE_INVALID')
                keys = set(page.keys())
                if keys == {'deleted'}:
                    values = page['deleted']
                elif keys == {'entries'}:
                    values = page['entries']
                else:
                    raise ValueError('MIRROR_SYNC_PLAN_PAGE_KIND')
                if not isinstance(values, list) or not values or len(values) > MANIFEST_PAGE_LIMIT:
                    raise ValueError('MIRROR_SYNC_PLAN_PAGE_COUNT')
                yield page

        record_count = 0
        for page in pages():
            values = page['deleted'] if 'deleted' in page else page['entries']
            record_count += len(values)
            if record_count > MAX_FILES:
                raise ValueError('WORKSPACE_FILE_LIMIT')

        deleted_count = 0
        for page in pages():
            if 'deleted' not in page:
                continue
            deleted_paths = page['deleted']
            if any(not isinstance(path, str) for path in deleted_paths):
                raise ValueError('MIRROR_SYNC_PLAN_PATH_INVALID')
            for path in sorted(deleted_paths, key=lambda value: (value.count('/'), len(value)), reverse=True):
                clean(path)
                if self.ignored(path):
                    raise ValueError('IGNORED_SYNC_PATH')
                self.delete(path)
                deleted_count += 1

        transferred_files = 0
        transferred_bytes = 0
        for page in pages():
            if 'entries' not in page:
                continue
            entries = page['entries']
            page_bytes = 0
            for entry in entries:
                if not isinstance(entry, dict):
                    raise ValueError('MIRROR_SYNC_PLAN_ENTRY_INVALID')
                path = entry.get('path')
                directory = entry.get('directory')
                size = entry.get('size')
                if (not isinstance(path, str) or not isinstance(directory, bool) or
                        isinstance(size, bool) or not isinstance(size, int) or size < 0):
                    raise ValueError('MIRROR_SYNC_PLAN_ENTRY_INVALID')
                clean(path)
                if not path or self.ignored(path):
                    raise ValueError('IGNORED_SYNC_PATH')
                if directory:
                    self.mkdir(path)
                    continue
                digest = entry.get('sha256')
                source_index = entry.get('source')
                if (not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest) or
                        isinstance(source_index, bool) or not isinstance(source_index, int) or source_index < 0):
                    raise ValueError('MIRROR_SYNC_PLAN_ENTRY_INVALID')
                page_bytes += size
            self.reserve(page_bytes)
            for entry in entries:
                if entry['directory']:
                    continue
                with self.fetch('file/' + str(entry['source'])) as source:
                    self.put(entry['path'], source, entry['size'], entry['sha256'])
                transferred_files += 1
                transferred_bytes += entry['size']
        return {'transferredFiles': transferred_files, 'transferredBytes': transferred_bytes,
                'deletedPaths': deleted_count}

    def execute(self, args):
        cwd_rel = clean(args.get('cwd', ''))
        if cwd_rel:
            self.info(cwd_rel)
            with self.parent(cwd_rel) as (fd, name):
                s = os.stat(name, dir_fd=fd, follow_symlinks=False)
                if not stat.S_ISDIR(s.st_mode):
                    raise ValueError('INVALID_CWD_DIRECTORY')
            exec_cwd = os.path.join(self.project, cwd_rel)
        else:
            exec_cwd = self.project
        executable = args['executable']
        if '/' in executable:
            raw_exe = executable.removeprefix('./')
            path = clean(raw_exe)
            target_path = os.path.join(cwd_rel, path) if cwd_rel else path
            self.info(target_path)
            with self.parent(target_path) as (fd, name):
                handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
                try:
                    os.fchmod(handle, os.fstat(handle).st_mode | stat.S_IXUSR)
                finally:
                    os.close(handle)
            executable = os.path.join(self.project, target_path)
        else:
            if not re.fullmatch(r'[A-Za-z0-9_.+-]+', executable):
                raise ValueError('INVALID_EXECUTABLE')
            executable = '/data/data/com.termux/files/usr/bin/' + executable
        env = dict(os.environ)
        for key in list(env):
            if any(word in key.upper() for word in ('TOKEN', 'SECRET', 'API_KEY')) or key == 'SSH_AUTH_SOCK':
                del env[key]
        env.update(HOME=self.root + '/home', TMPDIR=self.root + '/tmp',
                   GRADLE_USER_HOME=self.root + '/cache/gradle', npm_config_cache=self.root + '/cache/npm',
                   PIP_CACHE_DIR=self.root + '/cache/pip')
        started = time.monotonic()
        cancelled = timed_out = False
        process = subprocess.Popen([executable] + args['args'], cwd=exec_cwd, env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        stdout, stderr = bytearray(), bytearray()
        def drain(stream, buffer):
            with stream:
                while True:
                    block = stream.read(65536)
                    if not block:
                        break
                    buffer.extend(block[:max(0, 65536 - len(buffer))])
        readers = [threading.Thread(target=drain, args=(stream, buffer), daemon=True)
                   for stream, buffer in ((process.stdout, stdout), (process.stderr, stderr))]
        for reader in readers:
            reader.start()
        try:
            while process.poll() is None:
                timed_out = time.monotonic() - started > args['timeoutMs'] / 1000
                try:
                    with self.fetch('cancel') as response:
                        cancelled = response.read(2) != b'0'
                except Exception:
                    cancelled = True
                if timed_out or cancelled:
                    break
                time.sleep(0.2)
        finally:
            # Kill any surviving descendants even after the leader exited normally.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
        for reader in readers:
            reader.join(2)
        return dict(exitCode=process.returncode, stdout=stdout.decode('utf-8', 'replace'),
                    stderr=stderr.decode('utf-8', 'replace'), timedOut=timed_out,
                    cancelled=cancelled, elapsedMs=int((time.monotonic() - started) * 1000))

    def tool(self, operation, args):
        path = clean(args.get('path', ''))
        if operation == 'execution_file_info':
            return {'entry': self.info(path)}
        if operation == 'execution_list_dir':
            entries = []
            for entry in self.listing(path):
                if len(entries) == 1000:
                    return {'entries': entries, 'truncated': True}
                entries.append(entry)
            return {'entries': entries}
        if operation == 'execution_read_file':
            start = max(1, args.get('start_line', 1))
            limit = min(400, max(1, args.get('max_lines', 200)))
            max_bytes = min(65536, max(1, args.get('max_bytes', 65536)))
            lines = []
            count = 0
            with self.open(path) as stream:
                for number in range(1, start + limit):
                    raw = stream.readline(max_bytes + 1)
                    if not raw:
                        break
                    count += len(raw)
                    if count > 16 * 1024 * 1024:
                        raise ValueError('READ_SCAN_LIMIT')
                    if len(raw) > max_bytes or b'\0' in raw:
                        raise ValueError('UNSUPPORTED_BINARY_OR_LINE_LIMIT')
                    if number >= start:
                        lines.append('%4d | %s' % (number, raw.decode('utf-8').rstrip('\n')))
                        if sum(len(line.encode('utf-8')) for line in lines) > max_bytes:
                            lines.pop()
                            break
            return {'text': '\n'.join(lines)}
        if operation in ('execution_search_files', 'execution_glob'):
            pending, matches, visited = [path], [], 0
            query = args.get('query', '')
            limit = min(200, max(1, args.get('max_results', 50)))
            deadline = time.monotonic() + 10
            while pending and len(matches) < limit:
                for entry in self.listing(pending.pop()):
                    visited += 1
                    if visited > MAX_FILES or time.monotonic() > deadline:
                        return {'matches': matches, 'truncated': True}
                    if self.ignored(entry['path']):
                        continue
                    if entry['directory']:
                        pending.append(entry['path'])
                    match = fnmatch.fnmatchcase(entry['path'], args.get('pattern', '*')) if operation.endswith('glob') else query in entry['path']
                    if operation.endswith('search_files') and not match and not entry['directory'] and entry['size'] <= TEXT_LIMIT:
                        try:
                            match = query in self.text(entry['path'])
                        except (UnicodeError, ValueError):
                            pass
                    if match:
                        matches.append(entry['path'])
                        if len(matches) == limit:
                            break
            return {'matches': matches, 'truncated': bool(pending)}
        if self.ignored(path):
            raise ValueError('IGNORED_EXECUTION_WRITE_PATH')
        if operation == 'execution_make_dir':
            self.mkdir(path)
        elif operation == 'execution_delete_file':
            if self.info(path)['directory']:
                raise ValueError('DIRECTORY_DELETE_FORBIDDEN')
            self.delete(path)
        elif operation == 'execution_write_file':
            if not args.get('overwrite', False):
                try:
                    self.info(path)
                except FileNotFoundError:
                    pass
                else:
                    raise ValueError('FILE_EXISTS')
            data = args['content'].encode('utf-8')
            if len(data) > TEXT_LIMIT or b'\0' in data:
                raise ValueError('TEXT_LIMIT')
            self.put(path, io.BytesIO(data), len(data))
        else:
            raise ValueError('UNKNOWN_OPERATION')
        return {'message': 'Mirror changed; SAF unchanged. Review execution diff before apply.'}

    def import_archive(self, args):
        destination = clean(args.get('destination', ''))
        if destination:
            self.info(destination)
        archive_size = int(args['size'])
        if archive_size < 0:
            raise ValueError('ARCHIVE_SIZE_INVALID')
        self.reserve(archive_size)
        with tempfile.TemporaryFile(dir=self.root + '/imports') as archive:
            total = 0
            with self.fetch('file/0') as source:
                while True:
                    block = source.read(65536)
                    if not block:
                        break
                    total += len(block)
                    if total > archive_size:
                        raise ValueError('ARCHIVE_SIZE_MISMATCH')
                    archive.write(block)
            if total != archive_size:
                raise ValueError('ARCHIVE_SIZE_MISMATCH')
            archive.seek(0)
            if args['format'] == 'zip':
                archive.seek(max(0, total - 65557))
                tail = archive.read(65557)
                end = tail.rfind(b'PK\x05\x06')
                if end < 0 or len(tail) - end < 22:
                    raise ValueError('INVALID_ZIP_DIRECTORY')
                fields = struct.unpack('<4s4H2LH', tail[end:end + 22])
                if fields[1] or fields[2] or fields[4] == 65535 or fields[5] > CONTROL_LIMIT:
                    raise ValueError('ARCHIVE_METADATA_LIMIT_OR_ZIP64_UNSUPPORTED')
                archive.seek(0)
                reader = zipfile.ZipFile(archive)
                items = reader.infolist()
                records = [(item.filename.rstrip('/'), item.is_dir(), item.file_size, item) for item in items]
                for item in items:
                    mode = item.external_attr >> 16
                    if item.flag_bits & 1 or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                        raise ValueError('ARCHIVE_UNSUPPORTED_ENTRY')
                opener = reader.open
            else:
                reader = tarfile.open(fileobj=BoundedArchiveReader(archive), mode='r:*')
                items = []
                for item in reader:
                    if len(items) >= MAX_FILES:
                        raise ValueError('ARCHIVE_FILE_LIMIT')
                    items.append(item)
                if any(not (item.isfile() or item.isdir()) for item in items):
                    raise ValueError('ARCHIVE_UNSUPPORTED_ENTRY')
                records = [(item.name.rstrip('/'), item.isdir(), item.size, item) for item in items]
                opener = reader.extractfile
            with reader:
                if len(records) > MAX_FILES:
                    raise ValueError('ARCHIVE_FILE_LIMIT')
                expanded = sum(size for _, directory, size, _ in records if not directory)
                if expanded > MAX_ARCHIVE_EXPANDED_BYTES:
                    raise ValueError('ARCHIVE_EXPANDED_SIZE_LIMIT')
                if expanded > max(1, archive_size) * MAX_EXPANSION_RATIO:
                    raise ValueError('ARCHIVE_EXPANSION_RATIO_LIMIT')
                self.reserve(archive_size + expanded)
                paths = set()
                for name, directory, size, item in records:
                    clean(name)
                    target = '/'.join(filter(None, (destination, name)))
                    if not target or target in paths:
                        raise ValueError('ARCHIVE_DUPLICATE_OR_ROOT_ENTRY')
                    paths.add(target)
                    try:
                        existing = self.info(target)
                        if not directory or not existing['directory']:
                            raise ValueError('ARCHIVE_TARGET_EXISTS')
                    except FileNotFoundError:
                        pass
                for name, directory, size, item in records:
                    target = '/'.join(filter(None, (destination, name)))
                    parts = target.split('/')
                    for end in range(1, len(parts)):
                        self.mkdir('/'.join(parts[:end]))
                    if directory:
                        self.mkdir(target)
                    else:
                        with opener(item) as source:
                            self.put(target, source, size)
        return {'message': 'Archive imported into execution://; SAF unchanged.'}


def main():
    import fcntl
    url = sys.argv[1]
    if not re.fullmatch(r'http://127\.0\.0\.1:[0-9]+/[a-f0-9]{64}', url):
        raise ValueError('INVALID_RPC_URL')
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def fetch(route):
        return http.open(url + '/' + route, timeout=30)
    with fetch('control') as response:
        payload = response.read(CONTROL_LIMIT + 1)
    if len(payload) > CONTROL_LIMIT:
        raise ValueError('CONTROL_LIMIT')
    config = json.loads(payload)
    config['_baseUrl'] = url
    identity = config['workspaceId']
    if not re.fullmatch('[0-9a-f]{24}', identity):
        raise ValueError('INVALID_WORKSPACE_ID')
    root = '/data/data/com.termux/files/home/.vega-safe/workspaces/' + identity
    mirror = Mirror(root, config, fetch)
    lock_path = root + '/.vega/lock'
    lock = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        operation, args = config['operation'], config['args']
        if operation == 'probe':
            result = {'protocolVersion': 2}
        elif operation == 'manifest':
            result = {'entries': mirror.manifest()}
        elif operation == 'manifest_page':
            result = mirror.manifest_page(args)
        elif operation == 'sync':
            result = mirror.sync(args)
        elif operation == 'exec':
            result = mirror.execute(args)
        elif operation == 'fetch_text':
            result = {'text': mirror.text(clean(args['path']))}
        elif operation == 'execution_import_archive':
            result = mirror.import_archive(args)
        elif operation == 'export_file':
            result = mirror.export_file(args)
        else:
            result = mirror.tool(operation, args)
        result['ok'] = True
    except Exception as error:
        result = {'ok': False, 'error': str(error)}
    finally:
        os.close(lock)
    encoded = json.dumps(result).encode('utf-8')
    if len(encoded) > CONTROL_LIMIT:
        encoded = b'{"ok":false,"error":"MIRROR_CONTROL_LIMIT"}'
    with http.open(urllib.request.Request(url + '/result', data=encoded, method='POST'), timeout=30) as response:
        response.read()


if __name__ == '__main__':
    main()
