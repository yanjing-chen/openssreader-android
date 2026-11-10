#!/usr/bin/env python3
"""Ensure scoped legacy HTTP exemptions pass Android 37 release lint.

These exemptions preserve exact-host access only; do not broaden to subdomains.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

file = Path(__file__).resolve().parents[1] / "app/src/main/res/xml/institution_network_security.xml"
root = ET.parse(file).getroot()
assert root.tag == "network-security-config"
base = root.find("base-config")
assert base is not None and base.attrib.get("cleartextTrafficPermitted") == "false"
configs = root.findall("domain-config")
assert len(configs) == 1, "Unexpected network-security exception groups"
assert configs[0].attrib.get("cleartextTrafficPermitted") == "true"
domains = configs[0].findall("domain")
expected = {
    "read.sslibrary.com", "bookworld.5read.com", "bfts.5read.com",
    "nfts.5read.com", "d.chaoxing.com",
}
assert {d.text.strip() for d in domains} == expected, "Domain scope unexpectedly changed"
assert len(domains) == len(expected)
for domain in domains:
    assert domain.attrib.get("includeSubdomains") == "false", f"Bad includeSubdomains for {domain.text}"
assert not root.findall("debug-overrides")
print("ANDROID_NETWORK_SECURITY_RELEASE_LINT_CONTRACT=PASS (exact hosts only)")
