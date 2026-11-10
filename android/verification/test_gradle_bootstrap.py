#!/usr/bin/env python3
"""Offline bootstrap test: verifies the real gradlew resolves Gradle 9.3.1. No network."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
root=Path(__file__).resolve().parents[1]
props=(root/'gradle/wrapper/gradle-wrapper.properties').read_text()
wrapper=(root/'gradlew').read_text()
assert re.search(r'gradle-9\.3\.1-bin\.zip', props)
assert re.search(r'^distributionSha256Sum=[0-9a-f]{64}$', props, re.M)
assert 'VERSION=8.11.1' not in wrapper
with tempfile.TemporaryDirectory(prefix='gecko-gradle-bootstrap-') as tmp:
    fake=Path(tmp)/'osr-bootstrap/gradle-9.3.1/bin/gradle'
    fake.parent.mkdir(parents=True)
    fake.write_text('#!/usr/bin/env bash\nprintf "Gradle 9.3.1\\n"\n')
    fake.chmod(0o755)
    env=dict(os.environ, GRADLE_USER_HOME=tmp)
    p=subprocess.run(['bash',str(root/'gradlew'),'--version'], cwd=root, env=env,
                     text=True,capture_output=True,timeout=10)
    assert p.returncode == 0, p.stderr
    assert 'Gradle 9.3.1' in p.stdout, (p.stdout,p.stderr)
print('GRADLE_BOOTSTRAP_BEHAVIOR=PASS (offline, version 9.3.1)')
