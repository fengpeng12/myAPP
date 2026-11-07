"""Fetch pinned upstream Node runtimes into build output (never into public source)."""
from pathlib import Path
import argparse
import hashlib
import os
import plistlib
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = {
    'windows': ('https://nodejs.org/dist/v22.22.3/node-v22.22.3-win-x64.zip',
                '6c8d54f635feff4df76c2ca80f45332eb2ff57d25226edce36592e51a177ee33'),
    'macos-arm64': ('https://nodejs.org/dist/v22.22.3/node-v22.22.3-darwin-arm64.tar.gz',
                    '0da7ff74ef8611328c8212f17943368713a2ad953fb7d89a8c8a0eae87c23207'),
    'macos-x64': ('https://nodejs.org/dist/v22.22.3/node-v22.22.3-darwin-x64.tar.gz',
                  '45830ba752fa0d892c6dcd640946669801293cac820a33591ded40ac075198ec'),
    'ios': ('https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-ios.zip',
            '8c5ca3a0d1e38de7f182a5642593e82593b820efd375a14b3ecafc4bcfee620e'),
    'android': ('https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip',
                'bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af'),
}


def archive(kind):
    url, expected = ASSETS[kind]
    cache = Path(os.environ.get('MARCOMOVIE_RUNTIME_CACHE', ROOT/'tools/catpaw'))
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache/url.rsplit('/',1)[-1]
    if not destination.exists():
        partial = destination.with_suffix('.partial')
        with urllib.request.urlopen(url,timeout=60) as response, partial.open('wb') as output:
            shutil.copyfileobj(response,output)
        partial.replace(destination)
    if hashlib.sha256(destination.read_bytes()).hexdigest() != expected:
        raise ValueError('Node runtime checksum mismatch: '+kind)
    return destination


def prepare(kind, destination):
    destination = Path(destination)
    destination.mkdir(parents=True,exist_ok=True)
    if kind == 'windows':
        with zipfile.ZipFile(archive(kind)) as source:
            for name in ('node.exe','LICENSE'):
                destination.joinpath(name).write_bytes(source.read('node-v22.22.3-win-x64/'+name))
    elif kind == 'macos':
        inputs=[]
        for arch in ('arm64','x64'):
            with tarfile.open(archive('macos-'+arch)) as source:
                member=source.extractfile(f'node-v22.22.3-darwin-{arch}/bin/node')
                binary=destination/('node-'+arch)
                binary.write_bytes(member.read());inputs.append(binary)
                destination.joinpath('LICENSE').write_bytes(source.extractfile(f'node-v22.22.3-darwin-{arch}/LICENSE').read())
        (destination/'bin').mkdir(exist_ok=True)
        subprocess.run(['lipo','-create',*map(str,inputs),'-output',str(destination/'bin/node')],check=True)
        (destination/'bin/node').chmod(0o755)
        for binary in inputs:binary.unlink()
        entitlements=destination/'node.entitlements'
        entitlements.write_bytes(plistlib.dumps({'com.apple.security.app-sandbox':True,'com.apple.security.inherit':True}))
        subprocess.run(['codesign','--force','--sign','-','--entitlements',str(entitlements),str(destination/'bin/node')],check=True)
    else:
        with zipfile.ZipFile(archive(kind)) as source:
            for member in source.infolist():
                target=(destination/member.filename).resolve()
                if not target.is_relative_to(destination.resolve()):raise ValueError('Unsafe archive path')
                if member.is_dir():target.mkdir(parents=True,exist_ok=True)
                else:
                    target.parent.mkdir(parents=True,exist_ok=True)
                    target.write_bytes(source.read(member))
    print('Prepared pinned CatPaw runtime:',kind)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--platform',choices=['windows','macos','ios','android'],required=True)
    parser.add_argument('--destination',type=Path,required=True)
    args=parser.parse_args()
    prepare(args.platform,args.destination)
