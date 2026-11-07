"""Build with an installed Android SDK/JDK, without downloading Gradle or dependencies."""
from pathlib import Path
import argparse
import os
import secrets
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk')
    parser.add_argument('--jdk', type=Path, default=Path(r'C:\Program Files\Android\Android Studio\jbr'))
    parser.add_argument('--work', type=Path, default=ROOT / '.build')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/PenDistance.apk')
    args = parser.parse_args()
    sdk, jdk, work, output = args.sdk.resolve(), args.jdk.resolve(), args.work.resolve(), args.output.resolve()
    tools = sdk / 'build-tools/35.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    env = os.environ.copy()
    env['JAVA_HOME'] = str(jdk)
    env['PATH'] = str(jdk / 'bin') + os.pathsep + env.get('PATH', '')
    def run(argv):
        subprocess.run([str(x) for x in argv], check=True, env=env, cwd=ROOT)
    for path in (android, tools / 'aapt2.exe', jdk / 'bin/javac.exe'):
        if not path.is_file():
            raise SystemExit(f'Required build tool missing: {path}')
    for folder in (work / 'classes', work / 'test-classes', work / 'dex', output.parent):
        folder.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / 'src').rglob('*.java'))
    javac = jdk / 'bin/javac.exe'
    run([javac, '--release', '8', '-encoding', 'UTF-8', '-d', work / 'test-classes', ROOT / 'src/io/github/pendistance/PenTracker.java', ROOT / 'tests/PenTrackerTest.java'])
    run([jdk / 'bin/java.exe', '-cp', work / 'test-classes', 'io.github.pendistance.PenTrackerTest'])
    run([javac, '--release', '8', '-encoding', 'UTF-8', '-classpath', android, '-d', work / 'classes', *sources])
    # Invoke D8's main class directly so Windows batch quoting does not alter paths.
    run([jdk / 'bin/java.exe', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android, '--min-api', '26', '--output', work / 'dex', *sorted((work / 'classes').rglob('*.class'))])
    base = work / 'base.apk'
    run([tools / 'aapt2.exe', 'link', '-I', android, '--manifest', ROOT / 'AndroidManifest.xml', '--min-sdk-version', '26', '--target-sdk-version', '35', '--version-code', '1', '--version-name', '0.1.0', '-o', base])
    with zipfile.ZipFile(base, 'a', zipfile.ZIP_DEFLATED) as archive:
        for dex in (work / 'dex').glob('*.dex'):
            archive.write(dex, dex.name)
    aligned = work / 'aligned.apk'
    run([tools / 'zipalign.exe', '-f', '-p', '4', base, aligned])
    keystore, password = work / 'local-signing.p12', work / 'signing-password.txt'
    if not keystore.exists():
        password.write_text(secrets.token_hex(24), encoding='utf-8')
        run([jdk / 'bin/keytool.exe', '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12', '-storepass:file', password, '-alias', 'pendistance', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-dname', 'CN=PenDistance Local Build'])
    run([jdk / 'bin/java.exe', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', keystore, '--ks-pass', 'file:' + str(password), '--ks-key-alias', 'pendistance', '--out', output, aligned])
    run([jdk / 'bin/java.exe', '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', output])
    print(f'APK built and signature verified: {output}')

if __name__ == '__main__':
    main()
