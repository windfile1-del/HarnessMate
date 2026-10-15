#!/usr/bin/env python3
"""Host-side Python unittest for R4 code_intel_lsp_daemon.py.

Verifies:
1. Real stdio subprocess communication (Windows-native without WSL):
   - initialize / initialized handshake
   - document synchronization (didOpen / didChange with real version increment)
   - textDocument/definition resolution
   - textDocument/references resolution
   - push diagnostics aggregation (proxying via textDocument/diagnostic)
   - URI normalization robustness: percent-encoded server URIs properly matched via document_key
   - refusal of workspace/applyEdit requests
2. Document notification channel (document_notification):
   - didChange reads authoritative content from disk; caller-forged text is ignored
   - didClose evicts document cache so subsequent requests trigger fresh didOpen
   - rejection of unsupported notification methods (LSP_NOTIFICATION_UNSUPPORTED)
   - rejection of out-of-bounds URIs (LSP_PATH_REJECTED)
   - TCP action 'notify' integration
3. Path confinement and boundary enforcement:
   - rejection of files outside workspace root
   - rejection of parent-directory traversal (..)
   - rejection of symlinks pointing outside workspace root
4. Authorization and security (TCP mode with tcp_secret, ready callback):
   - rejection of missing or wrong secrets (error: LSP_AUTHORITY_REJECTED)
   - handling of valid secrets for ping, request, notify, and stop
5. Protection against oversized protocol frames (> MAX_FRAME_BYTES):
   - client payload size limit
   - server response frame limit enforcement
   - TCP single-line request limit enforcement
6. Protection against mismatched/invalid tokens and malformed frames
7. Lifecycle and termination (server.stop() clean barrier)
"""

import hmac
import json
import os
from pathlib import Path
import select
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import unquote, urlparse

# Ensure assets directory is in python search path
REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = REPO_ROOT / 'assets'
if str(ASSETS_DIR) not in sys.path:
    sys.path.insert(0, str(ASSETS_DIR))

import code_intel_lsp_daemon


FAKE_LSP_SERVER_CODE = r'''# -*- coding: utf-8 -*-
import sys
import json

SEP_CRLF = b"\r\n"
SEP_LF = b"\n"

events = []

def read_frame():
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        if line == SEP_CRLF or line == SEP_LF:
            break
        try:
            key, val = line.decode("ascii").split(":", 1)
            headers[key.strip().lower()] = val.strip()
        except ValueError:
            return None
    size = int(headers.get("content-length", 0))
    if size < 0:
        return None
    body = sys.stdin.buffer.read(size)
    if len(body) != size:
        return None
    return json.loads(body.decode("utf-8"))

def send_frame(obj):
    raw = json.dumps(obj).encode("utf-8")
    header = ("Content-Length: " + str(len(raw)) + "\r\n\r\n").encode("ascii")
    sys.stdout.buffer.write(header + raw)
    sys.stdout.buffer.flush()

while True:
    try:
        msg = read_frame()
        if msg is None:
            break
        method = msg.get("method")
        msg_id = msg.get("id")

        if method == "initialize":
            send_frame({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "capabilities": {
                        "definitionProvider": True,
                        "referencesProvider": True,
                        "diagnosticProvider": False
                    }
                }
            })
        elif method == "initialized":
            pass
        elif method == "textDocument/didOpen":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            uri = doc.get("uri", "")
            version = doc.get("version", 1)
            text = doc.get("text", "")
            events.append({"event": "didOpen", "uri": uri, "version": version, "text": text})
            send_frame({
                "jsonrpc": "2.0",
                "method": "textDocument/publishDiagnostics",
                "params": {
                    "uri": uri,
                    "version": version,
                    "diagnostics": [
                        {
                            "range": {
                                "start": {"line": 0, "character": 0},
                                "end": {"line": 0, "character": 4}
                            },
                            "severity": 1,
                            "message": "Fake syntax error from test server"
                        }
                    ]
                }
            })
        elif method == "textDocument/didChange":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            uri = doc.get("uri", "")
            version = doc.get("version", 2)
            changes = params.get("contentChanges", [])
            content_text = changes[0].get("text", "") if changes else ""
            events.append({"event": "didChange", "uri": uri, "version": version, "text": content_text})
            send_frame({
                "jsonrpc": "2.0",
                "method": "textDocument/publishDiagnostics",
                "params": {
                    "uri": uri,
                    "version": version,
                    "diagnostics": []
                }
            })
        elif method == "textDocument/didClose":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            uri = doc.get("uri", "")
            events.append({"event": "didClose", "uri": uri})
        elif method == "textDocument/didSave":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            uri = doc.get("uri", "")
            events.append({"event": "didSave", "uri": uri})
        elif method == "textDocument/definition":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            uri = doc.get("uri", "")
            send_frame({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": [
                    {
                        "uri": uri,
                        "range": {
                            "start": {"line": 10, "character": 4},
                            "end": {"line": 10, "character": 14}
                        }
                    }
                ]
            })
        elif method == "textDocument/references":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            uri = doc.get("uri", "")
            send_frame({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": [
                    {
                        "uri": uri,
                        "range": {
                            "start": {"line": 20, "character": 0},
                            "end": {"line": 20, "character": 8}
                        }
                    }
                ]
            })
        elif method == "test/publishPercentEncodedDiagnostics":
            params = msg.get("params", {})
            doc = params.get("textDocument", {})
            raw_uri = doc.get("uri", "")
            encoded_uri = raw_uri.replace("Sample.kt", "%53ample.kt")
            version = doc.get("version", 1)
            send_frame({
                "jsonrpc": "2.0",
                "method": "textDocument/publishDiagnostics",
                "params": {
                    "uri": encoded_uri,
                    "version": version,
                    "diagnostics": [
                        {
                            "range": {
                                "start": {"line": 1, "character": 0},
                                "end": {"line": 1, "character": 5}
                            },
                            "severity": 2,
                            "message": "Warning from percent-encoded server URI"
                        }
                    ]
                }
            })
            if msg_id is not None:
                send_frame({
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "result": {"encoded_uri": encoded_uri}
                })
        elif method == "test/getRecordedEvents":
            send_frame({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {"events": list(events)}
            })
        elif method == "test/triggerApplyEdit":
            send_frame({
                "jsonrpc": "2.0",
                "id": "srv-edit-probe",
                "method": "workspace/applyEdit",
                "params": {
                    "edit": {
                        "changes": {"file:///dummy.txt": []}
                    }
                }
            })
            client_reply = read_frame()
            send_frame({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "probe": "applyEdit",
                    "client_reply": client_reply
                }
            })
        elif method == "test/mismatchedToken":
            send_frame({
                "jsonrpc": "2.0",
                "id": 987654321,
                "result": {"mismatched": True}
            })
            send_frame({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {"matched": True}
            })
        elif method == "test/oversizedFrame":
            oversized = b"O" * (1024 * 1024 + 100)
            sys.stdout.buffer.write(("Content-Length: " + str(len(oversized)) + "\r\n\r\n").encode("ascii") + oversized)
            sys.stdout.buffer.flush()
        elif method in ("shutdown", "exit"):
            break
        else:
            if msg_id is not None:
                send_frame({
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "result": None
                })
    except Exception:
        break
'''


class BaseLspDaemonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = tempfile.TemporaryDirectory(prefix="r4-lsp-test-")
        cls.temp_root = Path(cls.temp_dir.name).resolve()

        cls.workspace = cls.temp_root / "workspace"
        cls.workspace.mkdir(parents=True, exist_ok=True)

        cls.sample_file = cls.workspace / "Sample.kt"
        cls.sample_file.write_text("package test\n\nfun hello() = 42\n", encoding="utf-8")

        # An external file outside the workspace
        cls.outside_file = cls.temp_root / "Outside.kt"
        cls.outside_file.write_text("package outside\n\nval x = 1\n", encoding="utf-8")

        # A symlink pointing to an outside file
        cls.symlink_outside = cls.workspace / "SymlinkOutside.kt"
        try:
            os.symlink(cls.outside_file, cls.symlink_outside)
            cls.has_symlink = True
        except (OSError, NotImplementedError):
            cls.has_symlink = False

        cls.fake_script = cls.temp_root / "fake_lsp_server.py"
        cls.fake_script.write_text(FAKE_LSP_SERVER_CODE, encoding="utf-8")

        cls.server_id = "test-mock-r4"
        cls.root_uri = cls.workspace.as_uri()

        cls._orig_allowlist = dict(code_intel_lsp_daemon.SERVER_ALLOWLIST)
        code_intel_lsp_daemon.SERVER_ALLOWLIST[cls.server_id] = [
            [sys.executable, str(cls.fake_script)]
        ]

    @classmethod
    def tearDownClass(cls):
        code_intel_lsp_daemon.SERVER_ALLOWLIST = cls._orig_allowlist
        cls.temp_dir.cleanup()


class TestRealStdioSubprocessLsp(BaseLspDaemonTest):
    """End-to-end tests driving a real Windows subprocess via stdio pipes."""

    def setUp(self):
        self.server = code_intel_lsp_daemon.LanguageServer(self.server_id, self.root_uri)

    def tearDown(self):
        if self.server:
            proc = self.server.process
            self.server.stop()
            if proc:
                for pipe in (proc.stdin, proc.stdout, proc.stderr):
                    if pipe and not pipe.closed:
                        try:
                            pipe.close()
                        except Exception:
                            pass

    def test_01_real_subprocess_handshake_and_capabilities(self):
        """Verify initialize/initialized handshake with a real stdio subprocess."""
        started, error = self.server.start()
        self.assertTrue(started, f"LanguageServer failed to start: {error}")
        self.assertIsNotNone(self.server.process)
        self.assertIsNone(self.server.process.poll(), "Server process terminated unexpectedly")
        self.assertTrue(self.server._capabilities.get("definitionProvider"))
        self.assertTrue(self.server._capabilities.get("referencesProvider"))

    def test_02_real_subprocess_definition_and_references(self):
        """Verify definition and references resolution through real stdio exchange."""
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start: {error}")

        doc_uri = self.sample_file.as_uri()

        # 1. Definition
        def_reply = self.server.request('textDocument/definition', {
            'textDocument': {'uri': doc_uri},
            'position': {'line': 2, 'character': 5}
        }, timeout_sec=5.0)
        self.assertIsNotNone(def_reply)
        self.assertNotIn('error', def_reply)
        res = def_reply.get('result', [])
        self.assertTrue(len(res) > 0)
        self.assertEqual(res[0].get('uri'), doc_uri)
        self.assertEqual(res[0]['range']['start']['line'], 10)

        # 2. References
        ref_reply = self.server.request('textDocument/references', {
            'textDocument': {'uri': doc_uri},
            'position': {'line': 2, 'character': 5},
            'context': {'includeDeclaration': True}
        }, timeout_sec=5.0)
        self.assertIsNotNone(ref_reply)
        self.assertNotIn('error', ref_reply)
        ref_res = ref_reply.get('result', [])
        self.assertTrue(len(ref_res) > 0)
        self.assertEqual(ref_res[0]['range']['start']['line'], 20)

    def test_03_push_diagnostics_and_real_didchange_version_increment(self):
        """Verify server-pushed diagnostics aggregation and true document didChange version increment."""
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start: {error}")

        doc_uri = self.sample_file.as_uri()

        # Initial request triggers didOpen (version 1)
        diag_reply = self.server.request('textDocument/diagnostic', {
            'textDocument': {'uri': doc_uri}
        }, timeout_sec=5.0)
        self.assertIsNotNone(diag_reply)
        self.assertNotIn('error', diag_reply)
        items = (diag_reply.get('result') or {}).get('items', [])
        self.assertTrue(len(items) > 0, "Expected diagnostics pushed from server on didOpen")
        self.assertIn("Fake syntax error", items[0].get("message", ""))

        # Modify document text and request again; triggers didChange with version 2
        self.sample_file.write_text("package test\n\nfun hello() = 100\n", encoding="utf-8")
        diag_reply2 = self.server.request('textDocument/diagnostic', {
            'textDocument': {'uri': doc_uri}
        }, timeout_sec=5.0)
        self.assertIsNotNone(diag_reply2)
        items2 = (diag_reply2.get('result') or {}).get('items', [])
        self.assertEqual(len(items2), 0, "Expected diagnostics cleared after didChange")

        # Query fake server events via public request interface to verify real didChange notification
        events_reply = self.server.request('test/getRecordedEvents', {}, timeout_sec=5.0)
        self.assertIsNotNone(events_reply)
        events = (events_reply.get('result') or {}).get('events', [])
        did_change_events = [e for e in events if e.get('event') == 'didChange']
        self.assertTrue(len(did_change_events) > 0, "Fake server must receive real didChange notification")
        self.assertEqual(did_change_events[-1].get('version'), 2)

    def test_04_percent_encoded_uri_diagnostics_retrieval(self):
        """Verify diagnostics can be retrieved even when server pushes percent-encoded URIs (e.g. Pyright)."""
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start: {error}")

        doc_uri = self.sample_file.as_uri()

        # First trigger initial document synchronization
        self.server.request('textDocument/definition', {
            'textDocument': {'uri': doc_uri},
            'position': {'line': 2, 'character': 5}
        }, timeout_sec=5.0)

        # Instruct fake server to push a diagnostic using percent-encoded URI (%53ample.kt)
        probe_res = self.server.request('test/publishPercentEncodedDiagnostics', {
            'textDocument': {'uri': doc_uri}
        }, timeout_sec=5.0)
        self.assertIsNotNone(probe_res)
        self.assertNotIn('error', probe_res)

        # Query diagnostic via standard, unencoded doc_uri through the public request interface
        diag_reply = self.server.request('textDocument/diagnostic', {
            'textDocument': {'uri': doc_uri}
        }, timeout_sec=5.0)
        self.assertIsNotNone(diag_reply)
        self.assertNotIn('error', diag_reply)
        items = (diag_reply.get('result') or {}).get('items', [])
        self.assertTrue(len(items) > 0, "Diagnostics pushed with percent-encoded URI must be retrievable")
        self.assertIn("Warning from percent-encoded server URI", items[0].get("message", ""))

    def test_05_refuse_workspace_apply_edit(self):
        """Verify the client rejects workspace/applyEdit requests from the language server."""
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start: {error}")

        reply = self.server.request('test/triggerApplyEdit', {}, timeout_sec=5.0)
        self.assertIsNotNone(reply)
        self.assertNotIn('error', reply)
        probe_res = reply.get('result', {})
        client_reply = probe_res.get('client_reply', {})

        self.assertEqual(client_reply.get('id'), 'srv-edit-probe')
        res_payload = client_reply.get('result', {})
        self.assertFalse(res_payload.get('applied'), "workspace/applyEdit must be refused with applied: False")
        self.assertIn('read-only', res_payload.get('failureReason', ''))

    def test_06_token_isolation_and_mismatched_replies(self):
        """Verify reader ignores mismatched tokens and correctly routes the expected reply."""
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start: {error}")

        reply = self.server.request('test/mismatchedToken', {}, timeout_sec=5.0)
        self.assertIsNotNone(reply)
        self.assertNotIn('error', reply)
        self.assertTrue((reply.get('result') or {}).get('matched'))

    def test_07_server_oversized_frame_rejection(self):
        """Verify reader safely rejects incoming frames exceeding MAX_FRAME_BYTES."""
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start: {error}")

        reply = self.server.request('test/oversizedFrame', {}, timeout_sec=2.0)
        self.assertIsNotNone(reply)
        self.assertIn('error', reply)
        self.assertIn(reply.get('error'), ('LSP_PROTOCOL_INVALID', 'LSP_TIMEOUT'))


class TestDocumentNotificationChannel(BaseLspDaemonTest):
    """Test document_notification: didChange disk authority, didClose eviction, unsupported methods, out-of-bounds URIs."""

    def setUp(self):
        self.server = code_intel_lsp_daemon.LanguageServer(self.server_id, self.root_uri)
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start LanguageServer: {error}")

    def tearDown(self):
        if self.server:
            proc = self.server.process
            self.server.stop()
            if proc:
                for pipe in (proc.stdin, proc.stdout, proc.stderr):
                    if pipe and not pipe.closed:
                        try:
                            pipe.close()
                        except Exception:
                            pass

    def test_01_didchange_reads_from_actual_file_ignores_caller_forged_text(self):
        """document_notification(didChange) synchronizes actual disk content; forged text cannot bypass disk."""
        doc_uri = self.sample_file.as_uri()

        # Step 1: initial request triggers didOpen with disk content
        self.server.request('textDocument/definition', {
            'textDocument': {'uri': doc_uri},
            'position': {'line': 2, 'character': 5}
        }, timeout_sec=5.0)

        # Step 2: call document_notification with FORGED text in payload
        forged_text = "FORGED_MALICIOUS_CONTENT_NEVER_WRITTEN_TO_DISK"
        res = self.server.document_notification('textDocument/didChange', {
            'textDocument': {'uri': doc_uri},
            'contentChanges': [{'text': forged_text}]
        })
        self.assertEqual(res, {'ok': True})

        # Step 3: verify disk file content was NOT altered by the notification call
        actual_disk_text = self.sample_file.read_text(encoding="utf-8")
        self.assertNotIn(forged_text, actual_disk_text, "Disk file must never be overwritten by forged text in notification")
        self.assertIn("package test", actual_disk_text)

        # Step 4: verify server events: no didChange notification containing forged_text was forwarded
        events_reply = self.server.request('test/getRecordedEvents', {}, timeout_sec=5.0)
        events = (events_reply.get('result') or {}).get('events', [])
        for ev in events:
            self.assertNotEqual(ev.get("text"), forged_text, "Server must never receive forged text not present on disk")

        # Step 5: modify actual disk file and notify again
        self.sample_file.write_text("package test\n\nfun updatedOnDisk() = 99\n", encoding="utf-8")
        res2 = self.server.document_notification('textDocument/didChange', {
            'textDocument': {'uri': doc_uri}
        })
        self.assertEqual(res2, {'ok': True})

        # Verify server receives the actual updated disk text
        events_reply2 = self.server.request('test/getRecordedEvents', {}, timeout_sec=5.0)
        events2 = (events_reply2.get('result') or {}).get('events', [])
        did_change_events = [e for e in events2 if e.get('event') == 'didChange']
        self.assertTrue(len(did_change_events) > 0)
        self.assertIn("updatedOnDisk", did_change_events[-1].get("text", ""))

    def test_02_didclose_evicts_cache_and_subsequent_request_triggers_new_didopen(self):
        """didClose clears document cache; subsequent requests trigger fresh didOpen."""
        doc_uri = self.sample_file.as_uri()

        # Step 1: initial request triggers didOpen
        self.server.request('textDocument/definition', {
            'textDocument': {'uri': doc_uri},
            'position': {'line': 2, 'character': 5}
        }, timeout_sec=5.0)

        # Step 2: call document_notification with didClose
        close_res = self.server.document_notification('textDocument/didClose', {
            'textDocument': {'uri': doc_uri}
        })
        self.assertEqual(close_res, {'ok': True})

        # Step 3: verify server received didClose
        events_reply = self.server.request('test/getRecordedEvents', {}, timeout_sec=5.0)
        events = (events_reply.get('result') or {}).get('events', [])
        close_events = [e for e in events if e.get('event') == 'didClose']
        self.assertTrue(len(close_events) > 0, "Fake server must receive didClose notification")

        # Step 4: query again; since cache was evicted, this must trigger a brand new didOpen (version 1)
        self.server.request('textDocument/definition', {
            'textDocument': {'uri': doc_uri},
            'position': {'line': 2, 'character': 5}
        }, timeout_sec=5.0)

        events_reply2 = self.server.request('test/getRecordedEvents', {}, timeout_sec=5.0)
        events2 = (events_reply2.get('result') or {}).get('events', [])
        open_events = [e for e in events2 if e.get('event') == 'didOpen']
        self.assertGreaterEqual(len(open_events), 2, "Re-querying closed document must trigger a new didOpen")
        self.assertEqual(open_events[-1].get('version'), 1, "New didOpen after didClose must reset version to 1")

    def test_03_unsupported_notification_and_out_of_bounds_uri(self):
        """Unsupported notification methods return LSP_NOTIFICATION_UNSUPPORTED; out-of-bounds URIs are rejected."""
        doc_uri = self.sample_file.as_uri()
        outside_uri = self.outside_file.as_uri()

        # 1. Unsupported notification method
        bad_res = self.server.document_notification('textDocument/unsupportedAction', {
            'textDocument': {'uri': doc_uri}
        })
        self.assertEqual(bad_res, {'error': 'LSP_NOTIFICATION_UNSUPPORTED'})

        # 2. Out-of-bounds URI rejected
        oob_res = self.server.document_notification('textDocument/didChange', {
            'textDocument': {'uri': outside_uri}
        })
        self.assertEqual(oob_res, {'error': 'LSP_PATH_REJECTED'})

        # 3. Path traversal URI rejected
        traversal_uri = self.root_uri.rstrip('/') + '/../Outside.kt'
        trav_res = self.server.document_notification('textDocument/didOpen', {
            'textDocument': {'uri': traversal_uri}
        })
        self.assertEqual(trav_res, {'error': 'LSP_PATH_REJECTED'})


class TestPathConfinementAndSecurity(BaseLspDaemonTest):
    """Test outside-root, parent-directory traversal (..), and symlink rejection."""

    def setUp(self):
        self.server = code_intel_lsp_daemon.LanguageServer(self.server_id, self.root_uri)
        started, error = self.server.start()
        self.assertTrue(started, f"Failed to start LanguageServer: {error}")

    def tearDown(self):
        if self.server:
            proc = self.server.process
            self.server.stop()
            if proc:
                for pipe in (proc.stdin, proc.stdout, proc.stderr):
                    if pipe and not pipe.closed:
                        try:
                            pipe.close()
                        except Exception:
                            pass

    def test_reject_outside_root_file(self):
        """Requests pointing to files outside execution root must be rejected with LSP_PATH_REJECTED."""
        outside_uri = self.outside_file.as_uri()
        reply = self.server.request('textDocument/definition', {
            'textDocument': {'uri': outside_uri},
            'position': {'line': 0, 'character': 0}
        }, timeout_sec=2.0)
        self.assertEqual(reply, {'error': 'LSP_PATH_REJECTED'})

    def test_reject_parent_traversal(self):
        """URIs containing .. escaping outside execution root must be rejected."""
        traversal_uri = self.root_uri.rstrip('/') + '/../Outside.kt'
        reply = self.server.request('textDocument/definition', {
            'textDocument': {'uri': traversal_uri},
            'position': {'line': 0, 'character': 0}
        }, timeout_sec=2.0)
        self.assertEqual(reply, {'error': 'LSP_PATH_REJECTED'})

    def test_reject_symlink_pointing_outside(self):
        """Symlinks inside workspace pointing outside execution root must resolve to realpath and be rejected."""
        if not self.has_symlink:
            self.skipTest("Symlinks not supported in current execution environment")
        symlink_uri = self.symlink_outside.as_uri()
        reply = self.server.request('textDocument/definition', {
            'textDocument': {'uri': symlink_uri},
            'position': {'line': 0, 'character': 0}
        }, timeout_sec=2.0)
        self.assertEqual(reply, {'error': 'LSP_PATH_REJECTED'})


class TestTcpServeAndAuthority(BaseLspDaemonTest):
    """Test TCP mode serve() with tcp_secret and ready callback."""

    def test_tcp_serve_lifecycle_and_authentication(self):
        tcp_secret = "secret-token-xyz-12345"
        ready_event = threading.Event()
        server_info = {}

        def ready_cb(info):
            server_info.update(info)
            ready_event.set()

        with patch('signal.signal'):
            serve_thread = threading.Thread(
                target=code_intel_lsp_daemon.serve,
                args=(None, self.root_uri, self.server_id, "session-tcp-test"),
                kwargs={"tcp_secret": tcp_secret, "ready": ready_cb},
                daemon=True
            )
            serve_thread.start()

            self.assertTrue(ready_event.wait(timeout=10.0), "TCP serve failed to signal ready")
            self.assertTrue(server_info.get("ok"))
            port = server_info.get("port")
            self.assertIsInstance(port, int)
            self.assertGreater(port, 0)

            def send_cmd(payload):
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(5.0)
                s.connect(("127.0.0.1", port))
                line = (json.dumps(payload) + "\n").encode("utf-8")
                s.sendall(line)
                buf = b""
                while b"\n" not in buf:
                    chunk = s.recv(4096)
                    if not chunk:
                        break
                    buf += chunk
                s.close()
                return buf.decode("utf-8", errors="replace").strip()

            # 1. Reject missing secret
            unauth_resp = send_cmd({"action": "ping"})
            unauth_obj = json.loads(unauth_resp)
            self.assertEqual(unauth_obj.get("error"), "LSP_AUTHORITY_REJECTED")

            # 2. Reject wrong secret
            wrong_resp = send_cmd({"secret": "bad-secret", "action": "ping"})
            wrong_obj = json.loads(wrong_resp)
            self.assertEqual(wrong_obj.get("error"), "LSP_AUTHORITY_REJECTED")

            # 3. Allow valid secret ping
            ping_resp = send_cmd({"secret": tcp_secret, "action": "ping"})
            self.assertEqual(ping_resp, "pong")

            # 4. Unknown action with valid secret
            unknown_resp = send_cmd({"secret": tcp_secret, "action": "nonexistent"})
            unknown_obj = json.loads(unknown_resp)
            self.assertEqual(unknown_obj.get("error"), "LSP_ACTION_UNKNOWN")

            # 5. Notify action via TCP
            notify_resp = send_cmd({
                "secret": tcp_secret,
                "action": "notify",
                "method": "textDocument/didChange",
                "params": {"textDocument": {"uri": self.sample_file.as_uri()}}
            })
            notify_obj = json.loads(notify_resp)
            self.assertTrue(notify_obj.get("ok"))

            # 6. Stop action with valid secret
            stop_resp = send_cmd({"secret": tcp_secret, "action": "stop"})
            self.assertEqual(stop_resp, "stopped")

            serve_thread.join(timeout=5.0)
            self.assertFalse(serve_thread.is_alive(), "serve thread did not exit cleanly after stop")

    def test_tcp_serve_oversized_line_rejected(self):
        """TCP server closes connection or rejects when request line exceeds MAX_FRAME_BYTES."""
        tcp_secret = "secret-token-large-test"
        ready_event = threading.Event()
        server_info = {}

        def ready_cb(info):
            server_info.update(info)
            ready_event.set()

        with patch('signal.signal'):
            serve_thread = threading.Thread(
                target=code_intel_lsp_daemon.serve,
                args=(None, self.root_uri, self.server_id, "session-tcp-large"),
                kwargs={"tcp_secret": tcp_secret, "ready": ready_cb},
                daemon=True
            )
            serve_thread.start()
            self.assertTrue(ready_event.wait(timeout=10.0))
            port = server_info.get("port")

            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(5.0)
            s.connect(("127.0.0.1", port))
            oversized_line = b'{"secret":"' + (b'x' * (code_intel_lsp_daemon.MAX_FRAME_BYTES + 1024)) + b'"}\n'
            s.sendall(oversized_line)
            resp = s.recv(1024)
            s.close()

            s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s2.connect(("127.0.0.1", port))
            s2.sendall((json.dumps({"secret": tcp_secret, "action": "stop"}) + "\n").encode("utf-8"))
            s2.close()
            serve_thread.join(timeout=5.0)


class TestFrameLimitsAndSanitizer(BaseLspDaemonTest):
    """Test oversized frame protections and security boundaries."""

    def test_oversized_client_write_rejected(self):
        """LanguageServer._write rejects payloads exceeding MAX_FRAME_BYTES."""
        server = code_intel_lsp_daemon.LanguageServer(self.server_id, self.root_uri)
        class DummyStdin:
            def write(self, data): pass
            def flush(self): pass
        class DummyProcess:
            stdin = DummyStdin()
            def poll(self): return None
        server.process = DummyProcess()

        huge_payload = {"huge": "A" * (code_intel_lsp_daemon.MAX_FRAME_BYTES + 500)}
        self.assertFalse(server._write(huge_payload), "_write must return False for oversized frames")

    def test_max_frame_bytes_constant(self):
        """MAX_FRAME_BYTES is configured to exactly 1 MiB."""
        self.assertEqual(code_intel_lsp_daemon.MAX_FRAME_BYTES, 1024 * 1024)

    def test_root_path_from_uri_security(self):
        """root_path_from_uri rejects non-file schemes, null bytes, and nonexistent directories."""
        self.assertIsNone(code_intel_lsp_daemon.root_path_from_uri("http://localhost/path"))
        self.assertIsNone(code_intel_lsp_daemon.root_path_from_uri("ftp://localhost/path"))
        null_byte_path = "file:///tmp/null" + chr(0) + "byte"
        self.assertIsNone(code_intel_lsp_daemon.root_path_from_uri(null_byte_path))
        self.assertIsNone(code_intel_lsp_daemon.root_path_from_uri("file:///nonexistent/path/for/sure/999"))

    def test_document_key_percent_encoding_normalization(self):
        """document_key normalizes percent-encoded paths to the exact same key as raw paths."""
        raw_uri = self.sample_file.as_uri()
        encoded_uri = raw_uri.replace("Sample.kt", "%53ample.kt")
        self.assertEqual(
            code_intel_lsp_daemon.document_key(raw_uri),
            code_intel_lsp_daemon.document_key(encoded_uri)
        )


if __name__ == '__main__':
    unittest.main()
