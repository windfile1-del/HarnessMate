#!/usr/bin/env python3
"""Strict verification tool for matching APK signer certificate SHA-256 digests.

Used by the Vega Safe release and CI pipelines to prove that the host APK
(dev.wind.agent) and the companion runtime APK (dev.wind.agent.runtime) share the
exact same signing certificate, enforcing Android's signature-level
BIND_RUNTIME permission boundary.
"""
import re
import subprocess
import sys

SHA256_HEX = re.compile(r"^[0-9a-fA-F]{64}$")
CERT_DIGEST_PATTERN = re.compile(
    r"certificate\s+SHA-256\s+digest:\s*([0-9a-fA-F]{64})\b",
    re.IGNORECASE,
)


def extract_signer_digests(output: str) -> list[str]:
    """Extract all 64-hex certificate SHA-256 digests from apksigner output."""
    digests = []
    for line in output.splitlines():
        match = CERT_DIGEST_PATTERN.search(line)
        if match:
            digest = match.group(1).lower()
            if SHA256_HEX.match(digest) and digest not in digests:
                digests.append(digest)
    return digests


def verify_signer_output(host_output: str, companion_output: str) -> str:
    """Verify that both outputs contain valid signers and share identical primary digest."""
    host_digests = extract_signer_digests(host_output)
    if not host_digests:
        raise ValueError("No valid 64-hex SHA-256 signer certificate digest found in Host APK output")

    companion_digests = extract_signer_digests(companion_output)
    if not companion_digests:
        raise ValueError("No valid 64-hex SHA-256 signer certificate digest found in Companion APK output")

    host_primary = host_digests[0]
    companion_primary = companion_digests[0]

    if host_primary != companion_primary:
        raise ValueError(
            f"Signer digest mismatch!\nHost:      {host_primary}\nCompanion: {companion_primary}"
        )

    return host_primary


def run_apksigner(apksigner_bin: str, apk_path: str) -> str:
    """Run apksigner verify and return stdout+stderr."""
    cmd = [apksigner_bin, "verify", "--verbose", "--print-certs", apk_path]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"apksigner failed for {apk_path} (exit {proc.returncode}):\n{proc.stdout}")
    return proc.stdout


def self_test():
    """Unit tests ensuring parser correctly handles all formats and fails closed on mismatches."""
    out1 = """
Verifies
Number of signers: 1
Signer #1 certificate DN: CN=Vega CI
Signer #1 certificate SHA-256 digest: c3c2a413d6851ae4c3906e02a9081e41f1a225fdcea92966f4340c7d8a43774c
Signer #1 certificate SHA-1 digest: 5ed869c28d5cf2d279c0dd72aecfb1cf65b3c056
"""
    out2 = """
Verifies
Number of signers: 1
V3.0 Signer: certificate DN: CN=Vega CI
V3.0 Signer: certificate SHA-256 digest: c3c2a413d6851ae4c3906e02a9081e41f1a225fdcea92966f4340c7d8a43774c
V3.0 Signer: certificate SHA-1 digest: 5ed869c28d5cf2d279c0dd72aecfb1cf65b3c056
"""
    digest = verify_signer_output(out1, out2)
    assert digest == "c3c2a413d6851ae4c3906e02a9081e41f1a225fdcea92966f4340c7d8a43774c", f"Unexpected digest {digest}"

    out_diff = """
V3.0 Signer: certificate SHA-256 digest: 1111111111111111111111111111111111111111111111111111111111111111
"""
    try:
        verify_signer_output(out1, out_diff)
        raise AssertionError("Expected mismatch error but passed!")
    except ValueError as e:
        assert "Signer digest mismatch!" in str(e)

    out_malformed = """
V3.0 Signer: certificate SHA-256 digest: not-a-hash
"""
    try:
        verify_signer_output(out1, out_malformed)
        raise AssertionError("Expected malformed error but passed!")
    except ValueError as e:
        assert "No valid 64-hex SHA-256 signer certificate digest" in str(e)

    try:
        verify_signer_output("", "")
        raise AssertionError("Expected empty error but passed!")
    except ValueError:
        pass

    print("verify_apk_signer_match: self-test OK")


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
        return

    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <apksigner-bin> <host.apk> <companion.apk>", file=sys.stderr)
        print(f"       {sys.argv[0]} --self-test", file=sys.stderr)
        sys.exit(2)

    apksigner_bin, host_apk, companion_apk = sys.argv[1], sys.argv[2], sys.argv[3]
    host_out = run_apksigner(apksigner_bin, host_apk)
    companion_out = run_apksigner(apksigner_bin, companion_apk)

    try:
        matched_digest = verify_signer_output(host_out, companion_out)
        print(f"VERIFIED: Host and Companion share exact signer SHA-256 digest: {matched_digest}")
    except ValueError as err:
        print(f"FAILED: {err}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
