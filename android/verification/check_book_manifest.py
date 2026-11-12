#!/usr/bin/env python3
from pathlib import Path
import sys
from xml.etree import ElementTree as E
root=Path(sys.argv[1]); ns='{http://schemas.android.com/apk/res/android}'
manifest=E.parse(root/'app/src/main/AndroidManifest.xml').getroot()
app=manifest.find('application')
assert app is not None and app.attrib[ns+'networkSecurityConfig']=='@xml/institution_network_security'
activity=app.find('activity')
filters=activity.findall('intent-filter')
book=[f for f in filters if any(d.attrib.get(ns+'scheme')=='book' for d in f.findall('data'))]
assert len(book)==1
hosts={d.attrib[ns+'host'] for d in book[0].findall('data')}
assert hosts=={'read.sslibrary.com','bookworld.5read.com'}
assert all(d.attrib[ns+'path']=='/ss2path/ss2path.dll' for d in book[0].findall('data'))
for c in ('android.intent.action.VIEW','android.intent.category.BROWSABLE','android.intent.category.DEFAULT'):
    assert any(n.attrib.get(ns+'name')==c for n in list(book[0]))
network=E.parse(root/'app/src/main/res/xml/institution_network_security.xml').getroot()
assert network.find('base-config').attrib['cleartextTrafficPermitted']=='false'
allow={x.text for x in network.find('domain-config').findall('domain')}
assert allow=={'read.sslibrary.com','bookworld.5read.com','bfts.5read.com','nfts.5read.com','d.chaoxing.com'}
assert not any(d.attrib.get('includeSubdomains')=='true' for d in network.findall('.//domain'))
text=(root/'app/src/main/java/org/openssreader/android/OsrViewModel.kt').read_text()
assert 'InstitutionPdzTransfer(store.bookFolder' in text and 'engine.renameDownloaded(incoming, book)' in text
assert 'predecode(admitted, task.id)' in text and 'downloadTabNonce' in text
print('BOOK_MANIFEST_ROUTE_PASS')
