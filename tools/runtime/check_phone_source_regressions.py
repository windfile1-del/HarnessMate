"""Collect every existing source regression, preserving failures and optional baseline attribution."""
from pathlib import Path
import importlib.util
import json
import sys

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("source_regressions", root / "tests/source_regressions.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
baseline = Path(sys.argv[2]) if len(sys.argv) > 2 else None
original_read = module.read
results = []
for name, function in sorted(vars(module).items()):
    if not name.startswith("test_") or not callable(function):
        continue
    try:
        function()
        results.append({"test": name, "status": "PASS"})
        print("PASS", name)
    except Exception as error:
        failure = {"test": name, "status": "FAIL", "error": f"{type(error).__name__}: {error}"}
        if baseline and baseline.exists():
            module.read = lambda file: baseline.read_text(encoding="utf-8") if file == "AgentEngine.kt" else original_read(file)
            try:
                function()
                failure["baseline_engine"] = "PASS"
            except Exception as previous:
                failure["baseline_engine"] = "FAIL"
                failure["baseline_error"] = f"{type(previous).__name__}: {previous}"
            finally:
                module.read = original_read
        results.append(failure)
        print("FAIL", name, failure["error"])
failures = [result for result in results if result["status"] == "FAIL"]
Path(sys.argv[1]).write_text(json.dumps({"checks": len(results), "passed": len(results)-len(failures),
    "failures": failures, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Source regressions: {len(results)-len(failures)}/{len(results)} passed")
sys.exit(1 if failures else 0)
