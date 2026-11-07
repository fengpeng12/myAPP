"""Build the small app-owned JNI bridge and package pinned Node libraries."""
from pathlib import Path
import os
import platform
import shutil
import subprocess
from prepare_catpaw_runtime import ROOT, prepare


def toolchain():
    custom=os.environ.get('MARCOMOVIE_NDK_TOOLCHAIN')
    if custom:return Path(custom)
    ndk=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_LATEST_HOME')
    if not ndk:
        sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        choices=sorted((Path(sdk)/'ndk').glob('*')) if sdk else []
        if choices:ndk=str(choices[-1])
    if not ndk:raise RuntimeError('CatPaw native build requires an Android NDK toolchain')
    host={'Windows':'windows-x86_64','Darwin':'darwin-x86_64','Linux':'linux-x86_64'}[platform.system()]
    return Path(ndk)/'toolchains/llvm/prebuilt'/host


def build():
    ndk=toolchain()
    clang=ndk/'bin'/('clang.exe' if os.name=='nt' else 'clang')
    unpack=ROOT/'tools/catpaw/android'
    prepare('android',unpack)
    files={}
    for abi,target,triple in [('armeabi-v7a','armv7a-linux-androideabi23','arm-linux-androideabi'),
                              ('arm64-v8a','aarch64-linux-android23','aarch64-linux-android')]:
        library=unpack/'bin'/abi/'libnode.so'
        out=ROOT/'android/build/catpaw'/abi/'libmarco_node.so'
        out.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run(list(map(str,[clang,'--target='+target,'--sysroot='+str(ndk/'sysroot'),
            '-shared','-fPIC','-fno-stack-protector','-fno-builtin','-nostdlib','-O2',
            '-Wl,--no-undefined','-Wl,-z,max-page-size=16384','-Wl,-soname,libmarco_node.so',
            ROOT/'shared/catpaw/node_bridge.c',library,'-o',out])),check=True)
        cpp=ndk/'sysroot/usr/lib'/triple/'libc++_shared.so'
        for name,file in [('libnode.so',library),('libmarco_node.so',out),('libc++_shared.so',cpp)]:
            files['lib/'+abi+'/'+name]=file.read_bytes()
    assets=ROOT/'android/build/catpaw/assets/catpaw'
    assets.mkdir(parents=True,exist_ok=True)
    for name in ['host.cjs','fetch.cjs','fetch-LICENSE.txt','node-mobile-LICENSE.txt']:
        shutil.copy2(ROOT/'shared/catpaw'/name,assets/name)
    return files,assets.parent


if __name__=='__main__':build()
