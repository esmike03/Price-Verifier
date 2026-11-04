"""Compares the final packages without unpacking them.

- Ororama Windows zip vs Ororama Linux tar.gz: portal and pos-sync must match.
- Ororama portal vs the main packages' portal: must match.
- Windows zip vs the copy that was installed in the Windows test (win2).
Text files are compared with line endings ignored (the Windows package uses CRLF).
"""
import hashlib
import os
import sys
import tarfile
import zipfile

ORO = r'D:\Projects\Android\Price Verifier\Ororama\server-installer\dist'
MAIN = r'D:\Documents\Price Verifier\server-installer\dist'
TEXT = ('.php', '.js', '.css', '.txt', '.json', '.md', '.ini', '.htaccess', '.template', '.sh', '.ps1', '.cmd', '.mjs', '.cjs')


def digest(name, data):
    if name.lower().endswith(TEXT) or os.path.basename(name).startswith('.'):
        data = data.replace(b'\r\n', b'\n')
    return hashlib.sha256(data).hexdigest()


def from_zip(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if not info.is_dir():
                name = info.filename.split('/', 1)[1]
                out[name] = digest(name, z.read(info))
    return out


def from_tar(path):
    out = {}
    with tarfile.open(path) as t:
        for member in t:
            if member.isfile() and not member.name.endswith(('/frankenphp', '/bin/node')):
                name = member.name.split('/', 1)[1]
                out[name] = digest(name, t.extractfile(member).read())
    return out


def subset(files, prefix):
    return {k[len(prefix):]: v for k, v in files.items() if k.startswith(prefix)}


def report(label, a, b):
    only_a, only_b = sorted(set(a) - set(b)), sorted(set(b) - set(a))
    different = sorted(k for k in set(a) & set(b) if a[k] != b[k])
    same = not (only_a or only_b or different)
    print('%-58s %s (%d files)' % (label, 'IDENTICAL' if same else 'DIFFERENT', len(a)))
    for title, items in (('only in first', only_a), ('only in second', only_b), ('content differs', different)):
        for item in items[:8]:
            print('    %s: %s' % (title, item))
    return same


ow = from_zip(os.path.join(ORO, 'PriceVerifierServer-Ororama-Windows-1.0.0.zip'))
ol = from_tar(os.path.join(ORO, 'PriceVerifierServer-Ororama-Linux-1.0.0.tar.gz'))
mw = from_zip(os.path.join(MAIN, 'PriceVerifierServer-Windows-1.0.0.zip'))
ml = from_tar(os.path.join(MAIN, 'PriceVerifierServer-Linux-1.0.0.tar.gz'))

ok = True
ok &= report('Ororama portal: Windows package vs Linux package', subset(ow, 'files/portal/'), subset(ol, 'files/portal/'))
ok &= report('Ororama pos-sync: Windows package vs Linux package', subset(ow, 'files/pos-sync/'), subset(ol, 'files/pos-sync/'))
ok &= report('Portal: Ororama Windows vs main Windows', subset(ow, 'files/portal/'), subset(mw, 'files/portal/'))
ok &= report('Portal: Ororama Linux vs main Linux', subset(ol, 'files/portal/'), subset(ml, 'files/portal/'))
ok &= report('Main portal: Windows package vs Linux package', subset(mw, 'files/portal/'), subset(ml, 'files/portal/'))
print('config.json in any package: %d' % sum(1 for f in (ow, ol, mw, ml) for k in f if os.path.basename(k) == 'config.json'))
print('pos-sync or node in the main packages: %d' % sum(1 for f in (mw, ml) for k in f if 'pos-sync' in k or '/node' in k))

if len(sys.argv) > 1:      # the folder the Windows test installed from
    tested = {}
    root = chr(92) * 2 + '?' + chr(92) + os.path.abspath(sys.argv[1])    # long-path form
    for folder, _, names in os.walk(root):
        for n in names:
            if n == 'install-undertest.ps1':
                continue
            full = os.path.join(folder, n)
            rel = full[len(root) + 1:].replace(chr(92), '/')
            tested[rel] = digest(rel, open(full, 'rb').read())
    ok &= report('Final Ororama Windows zip vs the copy tested on Windows', ow, tested)
print('ALL IDENTICAL' if ok else 'DIFFERENCES FOUND')
