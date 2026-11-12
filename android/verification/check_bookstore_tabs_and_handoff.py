#!/usr/bin/env python3
"""Contract: original 1.0.3 GeckoView download handling + accepted 1.0.7 tab UX.
This checks source, not on-device download behavior.
"""
from pathlib import Path
import hashlib
p=Path(__file__).resolve().parents[1]
g=(p/'app/src/main/java/org/openssreader/android/GeckoBookstore.kt').read_text()
policy=(p/'app/src/main/java/org/openssreader/android/BookstoreNavigationPolicy.kt').read_text()
model=(p/'app/src/main/java/org/openssreader/android/OsrViewModel.kt').read_text()
transfer=(p/'app/src/main/java/org/openssreader/android/InstitutionPdzTransfer.kt').read_bytes()
# Extract ONLY the 1.0.7 tab presentation changes and compare the original
# downloader source as an exact SHA-256 fingerprint.
old=g.replace('if (!title.isNullOrBlank()) tab.title = title',
              'if (!title.isNullOrBlank()) tab.title = title.take(90)')\
     .replace('if (BookstoreNavigationPolicy.shouldShowTabs(state.tabs.size)) {',
              'if (state.tabs.size > 1) {')\
     .replace('Text(tab.title,', 'Text(if (tab.root) "搜索结果" else tab.title,')
expected_gecko='7c187253850b19c64d71eafb41e52a982644d8eabf34061ea7fe7608c264a23e'
expected_transfer='4763b1bac27a777e7ca744c4c1e1f5ed3695f93eca5f059df29f466c96d3e3b6'
assert hashlib.sha256(old.encode()).hexdigest() == expected_gecko, 'original GeckoView download path changed'
assert hashlib.sha256(transfer).hexdigest() == expected_transfer, 'original PDZ transfer path changed'
# The two tested tab improvements must remain.
assert 'Text(tab.title,' in g
assert 'if (BookstoreNavigationPolicy.shouldShowTabs(state.tabs.size)) {' in g
assert 'if (!title.isNullOrBlank()) tab.title = title' in g
assert 'val child = makeSession(context, false)' in g
assert 'state.addChild(child, state.tabFor(session)?.id)' in g
assert 'onBookLink(url)' in g and 'fun acceptBookLink(link: String)' in model
assert 'InstitutionBookProtocol.parse(link)' in model
assert 'PdzCatalogReader.read(partial)' in transfer.decode()
assert 'PdzDownloadEnvelope.inspect(partial)' not in transfer.decode()
assert 'BookstoreDownloadDiagnostics' not in g
print('ORIGINAL_DOWNLOAD_ROLLBACK_AND_1_0_7_TABS=PASS (static)')
