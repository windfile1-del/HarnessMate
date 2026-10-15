#!/usr/bin/env python3
"""Offline tests for the P5 trusted helper and language-server daemon.

These drive real code paths, not shape checks: the daemon is started as a
real process against a fake stdio language server, so partial frames,
timeouts, applyEdit refusal, crash handling and process-group cleanup are
observed rather than asserted about.
"""

import json
import os
import shutil
import sqlite3
import signal
import socket
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "assets")))
import code_intel_helper
import code_intel_lsp_daemon


class StaticIndexTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="code_intel_")
        self.project = os.path.join(self.root, "project")
        self.vega = os.path.join(self.root, ".vega", "code-intel")
        os.makedirs(self.project)
        os.makedirs(self.vega)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, name, text):
        path = os.path.join(self.project, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def test_kotlin_symbols_are_indexed_and_searchable(self):
        self.write("Foo.kt", "package com.example\nclass FooBar {\n    fun doSomething() {\n        val x = 42\n    }\n}\n")
        db = os.path.join(self.vega, "candidate.sqlite3")
        stats = code_intel_helper.build_snapshot(self.project, db, ["Foo.kt"])
        self.assertEqual(stats["files"], 1)
        self.assertGreaterEqual(stats["symbols"], 2)
        self.assertTrue(os.path.exists(db))

    def test_comment_and_string_identifiers_are_not_symbols(self):
        self.write("Noise.kt", "// class NotReal\nval text = \"class AlsoNotReal\"\nclass Real\n")
        db = os.path.join(self.vega, "candidate2.sqlite3")
        code_intel_helper.build_snapshot(self.project, db, ["Noise.kt"])
        connection = sqlite3.connect(db)
        names = {row[0] for row in connection.execute("SELECT name FROM symbols")}
        connection.close()
        self.assertIn("Real", names)
        self.assertNotIn("NotReal", names)
        self.assertNotIn("AlsoNotReal", names)

    def test_oversized_file_is_skipped(self):
        path = os.path.join(self.project, "huge.kt")
        with open(path, "wb") as handle:
            handle.write(b"x" * (code_intel_helper.MAX_FILE_BYTES + 16))
        db = os.path.join(self.vega, "candidate3.sqlite3")
        stats = code_intel_helper.build_snapshot(self.project, db, ["huge.kt"])
        self.assertEqual(stats["files"], 0)

    def test_path_traversal_is_refused(self):
        with self.assertRaises(ValueError):
            code_intel_helper.clean("../escape")
        with self.assertRaises(ValueError):
            code_intel_helper.clean("a/../../b")

    def test_session_key_must_be_hex(self):
        with self.assertRaises(ValueError):
            code_intel_helper.session_dir(self.root, "../../etc")


class DaemonTests(unittest.TestCase):
    """Drives the real daemon process against a fake stdio language server."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="code_intel_daemon_")
        self.socket_path = os.path.join(self.root, "server.sock")
        self.server_script = os.path.join(self.root, "fake_lsp.py")
        self.start_script = os.path.join(self.root, "fake_path", "kotlin-language-server")
        os.makedirs(os.path.dirname(self.start_script))
        self.marker = os.path.join(self.root, "server-stopped")
        self._write_fake_server()
        self.daemon = None

    def tearDown(self):
        if self.daemon is not None:
            # Ask the production daemon to execute its real stop barrier first.
            # SIGKILLing only the daemon group can otherwise strand the fake
            # language server, which deliberately runs in its own session.
            try:
                if os.path.exists(self.socket_path):
                    self._call({"action": "stop"}, timeout=5)
            except Exception:
                pass
            try:
                self.daemon.wait(timeout=5)
            except Exception:
                pass
            if self.daemon.poll() is None:
                try:
                    # Reuse the production helper barrier before the final
                    # fallback.  The daemon and fake language server have
                    # separate sessions, so killing only the daemon group can
                    # otherwise strand a child between tests.
                    code_intel_helper.terminate_process_group(self.daemon.pid)
                except (OSError, AttributeError):
                    pass
            # A failed test must not leak a deliberately hostile fake server;
            # this is test cleanup only and does not affect the assertion made
            # by the production barrier test itself.
            pid_path = self.marker + ".pid"
            try:
                if os.path.exists(pid_path):
                    with open(pid_path, encoding="ascii") as handle:
                        language_pid = int(handle.read().strip())
                    if code_intel_helper.process_alive(language_pid):
                        if hasattr(os, "killpg"):
                            os.killpg(os.getpgid(language_pid), signal.SIGKILL)
                        else:
                            os.kill(language_pid, signal.SIGKILL)
            except (OSError, ValueError):
                pass
            try:
                self.daemon.communicate(timeout=5)
            except Exception:
                pass
            try:
                if self.daemon.stdout is not None:
                    self.daemon.stdout.close()
                if self.daemon.stderr is not None:
                    self.daemon.stderr.close()
            except OSError:
                pass
        shutil.rmtree(self.root, ignore_errors=True)

    def _write_fake_server(self):
        script = textwrap.dedent('''
            import json, os, signal, subprocess, sys, time
            marker = sys.argv[1]
            mode = sys.argv[2] if len(sys.argv) > 2 else "normal"
            child_marker = marker + ".child"
            child_stop_marker = child_marker + ".stopped"
            child_ready_marker = child_marker + ".ready"
            pid_marker = marker + ".pid"
            child = None
            def _record(_signum, _frame):
                with open(marker, "w") as handle:
                    handle.write("stopped")
                os._exit(0)
            signal.signal(signal.SIGTERM, _record)
            with open(pid_marker, "w") as handle:
                handle.write(str(os.getpid()))
            if mode == "ignore_term":
                # This server intentionally survives SIGTERM so the real
                # daemon stop barrier must escalate to SIGKILL and prove the
                # independent language-server process group is gone.
                def _ignore(_signum, _frame):
                    pass
                signal.signal(signal.SIGTERM, _ignore)
            def send(payload):
                body = json.dumps(payload).encode("utf-8")
                header = ("Content-Length: %d\\r\\n\\r\\n" % len(body)).encode("ascii")
                # Keep header and body on one binary stream. Mixing
                # TextIOWrapper and buffer writes can reorder bytes on Linux,
                # making the fake server itself violate the protocol and hide
                # a daemon failure.
                sys.stdout.buffer.write(header + body)
                sys.stdout.buffer.flush()
            def receive():
                length = None
                while True:
                    line = sys.stdin.buffer.readline()
                    if not line:
                        sys.exit(0)
                    if line in (b"\\r\\n", b"\\n"):
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":", 1)[1].strip())
                if length is None:
                    return None
                body = b""
                while len(body) < length:
                    chunk = sys.stdin.buffer.read(length - len(body))
                    if not chunk:
                        sys.exit(0)
                    body += chunk
                return json.loads(body.decode("utf-8"))
            while True:
                message = receive()
                if message is None:
                    continue
                method = message.get("method")
                if method == "initialize":
                    if mode == "init_wait":
                        body_bytes = json.dumps({"jsonrpc": "2.0", "id": message["id"],
                                                 "result": {"capabilities": {}}}).encode("utf-8")
                        header = ("Content-Length: %d\\r\\n\\r\\n" % len(body_bytes)).encode("ascii")
                        # Keep the helper in the pre-publication window.  The
                        # cancellation test must terminate this live LS group
                        # even though no socket/state has been published yet.
                        sys.stdout.buffer.write(header[:7])
                        sys.stdout.buffer.flush()
                        time.sleep(30)
                        continue
                    send({"jsonrpc": "2.0", "id": message["id"], "result": {"capabilities": {}}})
                elif method == "initialized":
                    pass
                elif method == "textDocument/definition":
                    if mode == "applyedit":
                        send({"jsonrpc": "2.0", "id": 9001, "method": "workspace/applyEdit",
                              "params": {"edit": {"changes": {}}}})
                        apply_reply = receive()
                        rejected = bool(
                            apply_reply and apply_reply.get("id") == 9001 and
                            apply_reply.get("result", {}).get("applied") is False
                        )
                    else:
                        rejected = None
                    if mode == "partial":
                        with open(marker + ".request", "w") as handle:
                            handle.write("started")
                        body_bytes = json.dumps({"jsonrpc": "2.0", "id": message["id"],
                                                 "result": None}).encode("utf-8")
                        header = ("Content-Length: %d\\r\\n\\r\\n" % len(body_bytes)).encode("ascii")
                        sys.stdout.buffer.write(header + body_bytes[:5])
                        sys.stdout.buffer.flush()
                        time.sleep(30)
                        continue
                    if mode == "partial_header":
                        with open(marker + ".request", "w") as handle:
                            handle.write("started")
                        body_bytes = json.dumps({"jsonrpc": "2.0", "id": message["id"],
                                                 "result": None}).encode("utf-8")
                        header = ("Content-Length: %d\\r\\n\\r\\n" % len(body_bytes)).encode("ascii")
                        sys.stdout.buffer.write(header[:7])
                        sys.stdout.buffer.flush()
                        time.sleep(30)
                        continue
                    if mode == "crash":
                        sys.exit(3)
                    if mode == "child":
                        child_code = ("import signal,time\\n"
                                      "marker=%r\\n"
                                      "def stop(*_):\\n"
                                      "    open(marker,'w').write('stopped')\\n"
                                      "    open(%r,'w').write('stopped')\\n"
                                      "    raise SystemExit\\n"
                                      "signal.signal(signal.SIGTERM, stop)\\n"
                                      "open(%r,'w').write('ready')\\n"
                                      "time.sleep(60)\\n" % (child_marker, child_stop_marker, child_ready_marker))
                        child = subprocess.Popen([sys.executable, "-c", child_code],
                                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                        with open(child_marker, "w") as handle:
                            handle.write(str(child.pid))
                        ready_deadline = time.time() + 5
                        while time.time() < ready_deadline and not os.path.exists(child_ready_marker):
                            time.sleep(0.01)
                    result = {"uri": "file://" + sys.argv[0]}
                    if rejected is not None:
                        result["applyEditRejected"] = rejected
                    send({"jsonrpc": "2.0", "id": message["id"], "result": result})
                else:
                    send({"jsonrpc": "2.0", "id": message.get("id"), "result": None})
            with open(marker, "w") as handle:
                handle.write("stopped")
        ''')
        with open(self.server_script, "w", encoding="utf-8") as handle:
            handle.write(script)
        launcher = ('#!/bin/sh\nexec %s "%s" "%s" "$FAKE_LSP_MODE"\n'
                    % (sys.executable, self.server_script, self.marker))
        with open(self.start_script, "w", encoding="utf-8") as handle:
            handle.write(launcher)
        os.chmod(self.start_script, 0o755)

    def _require_posix(self):
        # These drive a real daemon process over a real UNIX socket with a
        # POSIX shell launcher. Where that cannot exist the test skips; where
        # it can, a daemon that does not come up is a failure, never a skip.
        if os.name == "nt" or not hasattr(socket, "AF_UNIX"):
            self.skipTest("POSIX UNIX sockets and a shell launcher are required")

    def _start_daemon(self, server_id="kotlin", mode="normal"):
        self._require_posix()
        environment = dict(os.environ)
        environment["PATH"] = os.path.dirname(self.start_script) + os.pathsep + environment.get("PATH", "")
        environment["FAKE_LSP_MODE"] = mode
        self.daemon = subprocess.Popen(
            [sys.executable, "-u", os.path.join(os.path.dirname(__file__), "..", "assets",
                                              "code_intel_lsp_daemon.py"),
             "--socket", self.socket_path, "--root-uri", "file://" + self.root,
             "--server", server_id, "--session", "testsession"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment,
            start_new_session=True)
        deadline = time.time() + 25
        while time.time() < deadline:
            # A socket file appears on bind, which is before listen(); readiness
            # is therefore proven by an answer, exactly as a real client sees it.
            if self._ping_once():
                return True
            if self.daemon.poll() is not None:
                break
            time.sleep(0.1)
        return False

    def _assert_daemon_started(self, **kwargs):
        # Keep diagnostics lazy.  Passing a string expression as assertTrue's
        # message evaluates it even when the assertion succeeds, and the
        # diagnostic helper intentionally terminates a live daemon.
        if not self._start_daemon(**kwargs):
            self.fail("daemon did not start: " + self._daemon_diagnosis())

    def _ping_once(self):
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.settimeout(2)
        try:
            client.connect(self.socket_path)
            client.sendall(b'{"action": "ping"}\n')
            return client.recv(64).strip() == b'pong'
        except OSError:
            return False
        finally:
            client.close()

    def _daemon_diagnosis(self):
        if self.daemon is None:
            return "no daemon was started"
        if self.daemon.poll() is None:
            self.daemon.kill()
        try:
            out, err = self.daemon.communicate(timeout=5)
        except Exception as error:  # pragma: no cover - diagnostics only
            return "no output: %s" % error
        return "exit=%s stderr=%r stdout=%r" % (
            self.daemon.returncode, (err or b"")[-600:], (out or b"")[-300:])

    def _call(self, payload, timeout=15):
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.settimeout(timeout)
        try:
            client.connect(self.socket_path)
            client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
            buffer = b""
            while b"\n" not in buffer:
                chunk = client.recv(65536)
                if not chunk:
                    break
                buffer += chunk
            return buffer.split(b"\n", 1)[0].decode("utf-8", errors="replace")
        finally:
            client.close()

    def test_embedded_fake_server_is_valid_python(self):
        # The double is generated as text, so a stray module-level return or a
        # bad indent would only show up as an inscrutable failure on CI. Compile
        # it here instead.
        with open(self.server_script, encoding='utf-8') as handle:
            compile(handle.read(), self.server_script, 'exec')

    def test_launcher_is_executable(self):
        self.assertTrue(os.access(self.start_script, os.X_OK),
                        'the language server launcher must be executable')

    def test_unknown_server_fails_closed(self):
        self.assertFalse(self._start_daemon(server_id="cobol"))

    def test_request_round_trip(self):
        self._assert_daemon_started()
        self.assertEqual(self._call({"action": "ping"}), "pong")
        reply = json.loads(self._call({"action": "request", "method": "textDocument/definition",
                                       "params": {}, "timeoutSec": 10}))
        self.assertIn("result", reply)

    def test_apply_edit_is_refused(self):
        environment_mode = "applyedit"
        self._assert_daemon_started(mode=environment_mode)
        reply = json.loads(self._call({"action": "request", "method": "textDocument/definition",
                                       "params": {}, "timeoutSec": 10}))
        self.assertIn("result", reply)
        # The fake server waits for the daemon's actual JSON-RPC response before
        # returning the definition.  If workspace/applyEdit were forwarded or
        # the refusal were removed, this assertion (or the request deadline)
        # goes red instead of merely checking source text.
        self.assertTrue(reply.get("result", {}).get("applyEditRejected"),
                        "workspace/applyEdit must be answered with applied=false")

    def test_partial_frame_hits_the_deadline(self):
        self._assert_daemon_started(mode="partial")
        started = time.time()
        reply = json.loads(self._call({"action": "request", "method": "textDocument/definition",
                                       "params": {}, "timeoutSec": 2}, timeout=20))
        elapsed = time.time() - started
        self.assertIn("error", reply)
        self.assertLess(elapsed, 15, "a stalled partial frame must not hang the session")

    def test_partial_header_hits_the_deadline(self):
        self._assert_daemon_started(mode="partial_header")
        started = time.time()
        reply = json.loads(self._call({"action": "request", "method": "textDocument/definition",
                                       "params": {}, "timeoutSec": 2}, timeout=20))
        elapsed = time.time() - started
        self.assertIn("error", reply)
        self.assertLess(elapsed, 15, "a partial header must not block past the request deadline")

    def test_stop_cancels_active_partial_request_before_its_deadline(self):
        # The request worker is deliberately blocked after only part of an LSP
        # header arrived.  A second stop connection must be accepted in
        # parallel, cancel read_frame, and prove the language-server group is
        # gone without waiting for the request's 30-second deadline.
        self._assert_daemon_started(mode="partial_header")
        request_result = {}

        def request_worker():
            try:
                request_result["value"] = self._call({
                    "action": "request",
                    "method": "textDocument/definition",
                    "params": {},
                    "timeoutSec": 30,
                }, timeout=40)
            except Exception as error:  # the stop barrier may close the socket
                request_result["error"] = error

        worker = threading.Thread(target=request_worker, daemon=True)
        worker.start()
        deadline = time.time() + 5
        while time.time() < deadline and not os.path.exists(self.marker + ".request"):
            time.sleep(0.05)
        self.assertTrue(os.path.exists(self.marker + ".request"),
                        "fake server did not enter the stalled request")
        with open(self.marker + ".pid", encoding="ascii") as handle:
            language_pid = int(handle.read().strip())
        self.assertTrue(code_intel_helper.process_alive(language_pid),
                        "the language server must be live while request is blocked")

        started = time.time()
        self.assertEqual(self._call({"action": "stop"}, timeout=15), "stopped")
        elapsed = time.time() - started
        self.assertLess(elapsed, 10,
                        "stop must cancel the active read instead of waiting for its deadline")
        worker.join(timeout=10)
        self.assertFalse(worker.is_alive(), "cancelled request worker must return")
        self.assertFalse(code_intel_helper.process_alive(language_pid),
                         "cancellation barrier must terminate the language-server group")

    def test_crash_is_reported_not_hidden(self):
        self._assert_daemon_started(mode="crash")
        reply = json.loads(self._call({"action": "request", "method": "textDocument/definition",
                                       "params": {}, "timeoutSec": 5}))
        self.assertIn("error", reply)

    def test_stop_tears_down_the_group_and_removes_the_socket(self):
        self._assert_daemon_started()
        self.assertEqual(self._call({"action": "stop"}), "stopped")
        deadline = time.time() + 10
        while time.time() < deadline and os.path.exists(self.socket_path):
            time.sleep(0.1)
        self.assertFalse(os.path.exists(self.socket_path), "socket removed on stop")
        deadline = time.time() + 10
        while time.time() < deadline and not os.path.exists(self.marker):
            time.sleep(0.1)
        self.assertTrue(os.path.exists(self.marker), "the language server was actually terminated")

    def test_stop_kills_language_server_child_process_group(self):
        self._assert_daemon_started(mode="child")
        reply = json.loads(self._call({"action": "request", "method": "textDocument/definition",
                                       "params": {}, "timeoutSec": 10}))
        self.assertIn("result", reply)
        self.assertEqual(self._call({"action": "stop"}), "stopped")
        deadline = time.time() + 10
        while time.time() < deadline and not os.path.exists(self.marker + ".child.stopped"):
            time.sleep(0.1)
        # The child has its own SIGTERM marker.  Seeing it is direct evidence
        # that the daemon killed the complete process group, not just parent.
        self.assertTrue(os.path.exists(self.marker + ".child.stopped"),
                        "the language-server child was not terminated with its group")

    def test_stop_force_kills_language_server_that_ignores_sigterm(self):
        self._assert_daemon_started(mode="ignore_term")
        with open(self.marker + ".pid", encoding="ascii") as handle:
            language_pid = int(handle.read().strip())
        self.assertTrue(code_intel_helper.process_alive(language_pid),
                        "the hostile fake language server must be live before stop")
        self.assertEqual(self._call({"action": "stop"}, timeout=30), "stopped")
        deadline = time.time() + 10
        while time.time() < deadline and code_intel_helper.process_alive(language_pid):
            time.sleep(0.1)
        self.assertFalse(code_intel_helper.process_alive(language_pid),
                         "STOPPED must prove a SIGTERM-resistant language server is gone")

    def test_helper_barrier_catches_an_independent_sigterm_resistant_group(self):
        self._assert_daemon_started(mode="ignore_term")
        with open(self.marker + ".pid", encoding="ascii") as handle:
            language_pid = int(handle.read().strip())
        self.assertTrue(code_intel_helper.terminate_process_group(self.daemon.pid),
                        "helper termination must confirm daemon and LS groups are gone")
        self.assertFalse(code_intel_helper.process_alive(language_pid),
                         "helper barrier must not strand the independent LS group")

    def test_helper_stop_daemon_proves_hostile_group_before_removing_state(self):
        # Exercise the production helper boundary, including its pre-stop
        # process-tree capture and post-ack barrier, rather than only calling
        # the daemon socket directly.
        session_key = "d" * 32
        directory = code_intel_helper.session_dir(self.root, session_key)
        self.socket_path = os.path.join(directory, "server.sock")
        self._assert_daemon_started(mode="ignore_term")
        with open(self.marker + ".pid", encoding="ascii") as handle:
            language_pid = int(handle.read().strip())
        identity = {"rootTaskId": "task", "projectId": "project", "rootGeneration": 1}
        state_path = os.path.join(directory, "state.json")
        with open(state_path, "w", encoding="utf-8") as handle:
            json.dump({"pid": self.daemon.pid, "identity": identity,
                       "attemptId": "a" * 32}, handle)
        result = code_intel_helper.stop_daemon(self.root, session_key, identity, "a" * 32)
        self.assertTrue(result.get("ok") and result.get("stopped"),
                        "helper must acknowledge only after the complete group is gone")
        self.assertFalse(code_intel_helper.process_alive(language_pid),
                         "helper stop must not leave an independent LS process")
        self.assertFalse(os.path.exists(state_path), "state is removed only after the stop barrier")

    def test_helper_prepublication_abort_kills_language_server_group(self):
        # This drives start_daemon/abort_daemon while initialize is still
        # blocked, before socket/state publication.  The retained process-tree
        # snapshot is the only way to find the independent LS after teardown.
        self._require_posix()
        workspace = os.path.join(self.root, "helper-workspace")
        os.makedirs(os.path.join(workspace, "project"))
        session_key = "e" * 32
        attempt = "f" * 32
        identity = {"rootTaskId": "task", "projectId": "project", "rootGeneration": 1}
        directory = code_intel_helper.session_dir(workspace, session_key)
        self.socket_path = os.path.join(directory, "server.sock")
        original_root = code_intel_helper.WORKSPACE_ROOT
        original_path = os.environ.get("PATH", "")
        code_intel_helper.WORKSPACE_ROOT = os.path.dirname(workspace) + os.sep
        # The helper receives the workspace id as the final path component.
        workspace = os.path.join(code_intel_helper.WORKSPACE_ROOT, session_key[:24])
        os.makedirs(os.path.join(workspace, "project"), exist_ok=True)
        directory = code_intel_helper.session_dir(workspace, session_key)
        self.socket_path = os.path.join(directory, "server.sock")
        environment_path = os.path.dirname(self.start_script) + os.pathsep + original_path
        os.environ["PATH"] = environment_path
        self._write_fake_server()
        self.socket_path = os.path.join(directory, "server.sock")
        self._helper_result = None

        def launch():
            with open(os.path.join(os.path.dirname(__file__), "..", "assets",
                                   "code_intel_lsp_daemon.py"), "rb") as handle:
                daemon_source = handle.read()
            self._helper_result = code_intel_helper.start_daemon(
                workspace, session_key, daemon_source, "kotlin", None, "kotlin",
                identity, attempt
            )

        worker = threading.Thread(target=launch, daemon=True)
        try:
            # The generated launcher reads this flag from its environment.
            os.environ["FAKE_LSP_MODE"] = "init_wait"
            worker.start()
            pid_deadline = time.time() + 10
            while time.time() < pid_deadline and not os.path.exists(self.marker + ".pid"):
                time.sleep(0.05)
            self.assertTrue(os.path.exists(self.marker + ".pid"),
                            "prepublication fake language server did not start")
            with open(self.marker + ".pid", encoding="ascii") as handle:
                language_pid = int(handle.read().strip())
            aborted = code_intel_helper.abort_daemon(workspace, session_key, identity, attempt)
            self.assertTrue(aborted.get("ok") and aborted.get("cancelRequested"),
                            "abort marker must be accepted before publication")
            worker.join(timeout=20)
            self.assertFalse(worker.is_alive(), "start_daemon must observe prepublication cancellation")
            self.assertEqual(self._helper_result.get("error"), "LSP_CANCELLED")
            self.assertFalse(code_intel_helper.process_alive(language_pid),
                             "prepublication abort must not strand the LS process group")
        finally:
            os.environ["PATH"] = original_path
            os.environ.pop("FAKE_LSP_MODE", None)
            code_intel_helper.WORKSPACE_ROOT = original_root

    def test_prepublication_attempt_handoff_is_serialized(self):
        """A cannot be replaced by B until its pre-publication group is gone."""
        self._require_posix()
        # Keep the UNIX socket path below Linux's 108-byte AF_UNIX limit; the
        # test root already is an isolated temporary execution workspace.
        workspace = self.root
        os.makedirs(os.path.join(workspace, "project"))
        session_key = "1" * 32
        attempt_a = "2" * 32
        attempt_b = "3" * 32
        identity = {"rootTaskId": "task", "projectId": "project", "rootGeneration": 1}
        directory = code_intel_helper.session_dir(workspace, session_key)
        claim_file = code_intel_helper.claim_path(directory)
        cancel_file = code_intel_helper.cancel_path(directory)
        original_path = os.environ.get("PATH", "")
        original_mode = os.environ.get("FAKE_LSP_MODE")
        original_remove_cancel = code_intel_helper.remove_cancel
        remove_entered = threading.Event()
        release_remove = threading.Event()
        start_result = {}
        abort_result = {}

        with open(os.path.join(os.path.dirname(__file__), "..", "assets",
                               "code_intel_lsp_daemon.py"), "rb") as handle:
            daemon_source = handle.read()

        def gated_remove_cancel(target):
            # Hold A exactly between observing its cancellation and removing
            # the marker.  This makes the ownership handoff interleaving
            # deterministic while still exercising the production boundary.
            marker = code_intel_helper.read_cancel(target)
            if (marker and str(marker.get("attemptId") or "") == attempt_a and
                    not release_remove.is_set()):
                remove_entered.set()
                release_remove.wait(10)
            return original_remove_cancel(target)

        def launch_a():
            start_result["value"] = code_intel_helper.start_daemon(
                workspace, session_key, daemon_source, "kotlin", None, "kotlin",
                identity, attempt_a)

        def abort_a():
            abort_result["value"] = code_intel_helper.abort_daemon(
                workspace, session_key, identity, attempt_a)

        environment_path = os.path.dirname(self.start_script) + os.pathsep + original_path
        os.environ["PATH"] = environment_path
        os.environ["FAKE_LSP_MODE"] = "init_wait"
        code_intel_helper.remove_cancel = gated_remove_cancel
        worker = threading.Thread(target=launch_a, daemon=True)
        abort_worker = threading.Thread(target=abort_a, daemon=True)
        try:
            worker.start()
            deadline = time.time() + 10
            while time.time() < deadline and not os.path.exists(self.marker + ".pid"):
                time.sleep(0.05)
            self.assertTrue(os.path.exists(self.marker + ".pid"),
                            "pre-publication A language server did not start")
            abort_worker.start()
            deadline = time.time() + 10
            while time.time() < deadline and not remove_entered.is_set():
                time.sleep(0.01)
            self.assertTrue(remove_entered.is_set(),
                            "A did not reach the pre-publication cancellation handoff")
            with open(claim_file, encoding="utf-8") as handle:
                self.assertEqual(json.load(handle)["attemptId"], attempt_a)
            with open(cancel_file, encoding="utf-8") as handle:
                self.assertEqual(json.load(handle)["attemptId"], attempt_a)

            # B races while A's marker and durable claim are both present.  It
            # must fail closed and must not clear or replace either record.
            b_result = code_intel_helper.start_daemon(
                workspace, session_key, daemon_source, "kotlin", None, "kotlin",
                identity, attempt_b)
            self.assertFalse(b_result.get("ok"))
            self.assertEqual(b_result.get("error"), "LSP_ATTEMPT_OWNERSHIP_MISMATCH")
            with open(claim_file, encoding="utf-8") as handle:
                self.assertEqual(json.load(handle)["attemptId"], attempt_a)
            with open(cancel_file, encoding="utf-8") as handle:
                self.assertEqual(json.load(handle)["attemptId"], attempt_a)

            release_remove.set()
            worker.join(timeout=20)
            abort_worker.join(timeout=20)
            self.assertFalse(worker.is_alive(), "A start must finish after cancellation")
            self.assertFalse(abort_worker.is_alive(), "A abort must wait for confirmed teardown")
            self.assertEqual(start_result.get("value", {}).get("error"), "LSP_CANCELLED")
            self.assertTrue(abort_result.get("value", {}).get("ok"))
            with open(self.marker + ".pid", encoding="ascii") as handle:
                language_pid = int(handle.read().strip())
            self.assertFalse(code_intel_helper.process_alive(language_pid),
                             "A language-server group must be gone before B can own the session")
            self.assertFalse(os.path.exists(claim_file), "A claim must be removed only after teardown")

            # Only after A's claim is gone may B become the sole published owner.
            os.environ["FAKE_LSP_MODE"] = "normal"
            started_b = code_intel_helper.start_daemon(
                workspace, session_key, daemon_source, "kotlin", None, "kotlin",
                identity, attempt_b)
            self.assertTrue(started_b.get("ok"),
                            "B may start after A is fully confirmed gone: %r" % started_b)
            stopped_b = code_intel_helper.stop_daemon(
                workspace, session_key, identity, attempt_b)
            self.assertTrue(stopped_b.get("ok") and stopped_b.get("stopped"))
        finally:
            release_remove.set()
            worker.join(timeout=20)
            abort_worker.join(timeout=20)
            code_intel_helper.remove_cancel = original_remove_cancel
            os.environ["PATH"] = original_path
            if original_mode is None:
                os.environ.pop("FAKE_LSP_MODE", None)
            else:
                os.environ["FAKE_LSP_MODE"] = original_mode
            # Keep a failed assertion from leaking a helper-owned session.
            try:
                if os.path.exists(claim_file) or os.path.exists(os.path.join(directory, "state.json")):
                    code_intel_helper.abort_daemon(workspace, session_key, identity,
                                                   attempt_b)
            except Exception:
                pass


class BridgeHelperTests(unittest.TestCase):
    """Operation-level checks against a real temporary execution root."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="code_intel_bridge_")
        self.identity = "a" * 24
        self.workspace = os.path.join(code_intel_helper.WORKSPACE_ROOT, self.identity)
        self._original = code_intel_helper.WORKSPACE_ROOT
        code_intel_helper.WORKSPACE_ROOT = self.root + os.sep
        self.workspace = os.path.join(self.root, self.identity)
        os.makedirs(os.path.join(self.workspace, "project"))

    def tearDown(self):
        code_intel_helper.WORKSPACE_ROOT = self._original
        shutil.rmtree(self.root, ignore_errors=True)

    def test_runtime_dependencies_are_actually_bound(self):
        # The daemon and the helper only ever run on a phone, so a missing import
        # there is a crash nothing else would catch. Asserting the modules they
        # rely on are real attributes of the loaded module is exactly the check a
        # missing import fails.
        for module, names in (
            (code_intel_lsp_daemon, ('socket', 'select', 'signal', 'subprocess',
                                    'threading', 'json', 'os', 'sys', 'time')),
            (code_intel_helper, ('socket', 'subprocess', 'sqlite3', 'urllib', 'json',
                                 'os', 're', 'sys', 'time')),
        ):
            for name in names:
                self.assertTrue(hasattr(module, name),
                                module.__name__ + ' uses ' + name + ' but never binds it')

        # A language server must never be able to mutate source through us.
        with open(os.path.join(os.path.dirname(__file__), '..', 'assets',
                               'code_intel_lsp_daemon.py'),
                  encoding='utf-8') as handle:
            daemon_source = handle.read()
        self.assertIn('workspace/applyEdit', daemon_source)
        self.assertIn('applied', daemon_source)

        # The allowlist is the only way a server binary is chosen.
        self.assertEqual(sorted(code_intel_lsp_daemon.SERVER_ALLOWLIST.keys()),
                         ['java', 'javascript', 'kotlin', 'python', 'typescript'])

    def test_probe_reports_the_workspace(self):
        result = code_intel_helper.run_operation(
            {"operation": "probe", "workspaceId": self.identity, "args": {}}, None)
        self.assertTrue(result["ok"])
        self.assertTrue(result["pythonOk"])

    def test_missing_mirror_fails_closed(self):
        with self.assertRaises(ValueError):
            code_intel_helper.run_operation(
                {"operation": "probe", "workspaceId": "b" * 24, "args": {}}, None)

    def test_unknown_operation_is_refused(self):
        result = code_intel_helper.run_operation(
            {"operation": "rm_rf", "workspaceId": self.identity, "args": {}}, None)
        self.assertFalse(result["ok"])
        self.assertIn("UNKNOWN_OPERATION", result["error"])

    def test_abort_marker_blocks_the_matching_start_attempt(self):
        session = "b" * 32
        attempt = "c" * 32
        identity = {"rootTaskId": "task", "projectId": "project", "rootGeneration": 1}
        aborted = code_intel_helper.abort_daemon(
            self.workspace, session, identity, attempt
        )
        self.assertTrue(aborted["ok"] and aborted["cancelRequested"])
        started = code_intel_helper.start_daemon(
            self.workspace, session, b"", "kotlin", None, "kotlin",
            identity, attempt
        )
        self.assertFalse(started["ok"])
        self.assertEqual(started["error"], "LSP_CANCELLED")

    def test_stale_attempt_cannot_stop_abort_or_replace_new_owner(self):
        """A delayed A cleanup/start must never target already-published B."""
        session = "c" * 32
        old_attempt = "d" * 32
        new_attempt = "e" * 32
        identity = {"rootTaskId": "task", "projectId": "project", "rootGeneration": 1}
        directory = code_intel_helper.session_dir(self.workspace, session)
        state_path = os.path.join(directory, "state.json")
        with open(state_path, "w", encoding="utf-8") as handle:
            json.dump({"pid": 2147483647, "server": "kotlin",
                       "attemptId": new_attempt, "identity": identity}, handle)

        started = code_intel_helper.start_daemon(
            self.workspace, session, b"", "kotlin", None, "kotlin", identity, old_attempt
        )
        self.assertFalse(started["ok"])
        self.assertEqual(started["error"], "LSP_ATTEMPT_OWNERSHIP_MISMATCH")

        stopped = code_intel_helper.stop_daemon(
            self.workspace, session, identity, old_attempt
        )
        self.assertFalse(stopped["ok"])
        self.assertEqual(stopped["error"], "LSP_SESSION_IDENTITY_MISMATCH")

        aborted = code_intel_helper.abort_daemon(
            self.workspace, session, identity, old_attempt
        )
        self.assertFalse(aborted["ok"])
        self.assertEqual(aborted["error"], "LSP_SESSION_IDENTITY_MISMATCH")
        self.assertFalse(os.path.exists(os.path.join(directory, "cancel.requested")),
                         "a stale attempt must not leave a cancellation marker for B")
        with open(state_path, encoding="utf-8") as handle:
            self.assertEqual(json.load(handle)["attemptId"], new_attempt)

    def test_index_build_writes_only_inside_the_mirror(self):
        with open(os.path.join(self.workspace, "project", "A.kt"), "w", encoding="utf-8") as handle:
            handle.write("class Alpha\n")
        result = code_intel_helper.run_operation(
            {"operation": "index_build", "workspaceId": self.identity,
             "args": {"files": ["A.kt"]}}, None)
        self.assertTrue(result["ok"])
        self.assertEqual(result["files"], 1)
        self.assertTrue(os.path.exists(os.path.join(self.workspace, ".vega", "code-intel", "candidate.sqlite3")))

    def test_lsp_status_without_daemon_is_false(self):
        result = code_intel_helper.run_operation(
            {"operation": "lsp_status", "workspaceId": self.identity,
             "args": {"sessionKey": "deadbeef"}}, None)
        self.assertTrue(result["ok"])
        self.assertFalse(result["alive"])

    def test_stale_state_without_an_owner_fails_closed(self):
        session = "d" * 32
        directory = code_intel_helper.session_dir(self.workspace, session)
        state_path = os.path.join(directory, "state.json")
        with open(state_path, "w", encoding="utf-8") as handle:
            json.dump({"pid": 2147483647, "identity": {
                "rootTaskId": "task", "projectId": "project", "rootGeneration": 1
            }, "attemptId": "a" * 32}, handle)
        result = code_intel_helper.stop_daemon(
            self.workspace, session,
            {"rootTaskId": "task", "projectId": "project", "rootGeneration": 1},
            "a" * 32
        )
        self.assertFalse(result.get("ok"), "an ownerless state record cannot be called stopped")
        self.assertTrue(os.path.exists(state_path), "unconfirmed state must be retained")


if __name__ == "__main__":
    unittest.main()

