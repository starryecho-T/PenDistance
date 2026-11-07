"""Install/start the tablet-local collector over USB. No root or Shizuku required on tested OPPO Pad 3."""
from pathlib import Path
import argparse
import json
import os
import re
import shlex
import socket
import subprocess
import time

PACKAGE = 'io.github.pendistance'
REMOTE = '/data/local/tmp/pen-distance'
ROOT = Path(__file__).resolve().parent

def request(port, token, command='status'):
    with socket.create_connection(('127.0.0.1', port), timeout=3) as connection:
        connection.sendall((json.dumps({'token': token, 'command': command}) + '\n').encode())
        with connection.makefile('rb') as stream:
            return json.loads(stream.readline(2_000_000))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', type=Path, default=Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe')
    parser.add_argument('--apk', type=Path, default=ROOT / 'dist/PenDistance.apk')
    parser.add_argument('--serial')
    parser.add_argument('--restart', action='store_true')
    parser.add_argument('--skip-install', action='store_true', help='Use an already installed, matching build')
    args = parser.parse_args()
    def adb(*items, private=False, data=None):
        command = [str(args.adb)] + (['-s', args.serial] if args.serial else []) + list(items)
        try:
            result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=40)
        except subprocess.TimeoutExpired:
            raise SystemExit('Device operation timed out. Check the tablet for an installation confirmation; complete it and retry.') from None
        if result.returncode:
            detail = 'Device operation failed.' if private else result.stderr.decode('utf-8', 'replace').strip()
            raise RuntimeError(detail)
        return result.stdout.decode('utf-8', 'replace').strip()
    if not args.serial:
        devices = [line.split()[0] for line in adb('devices').splitlines()[1:] if len(line.split()) > 1 and line.split()[1] == 'device']
        if len(devices) != 1:
            raise SystemExit('Connect one tablet with USB debugging authorized, or specify --serial.')
        args.serial = devices[0]
    caps = adb('shell', 'getevent', '-pl')
    blocks = re.split(r'(?=add device \d+:)', caps)
    matching = [b for b in blocks if 'ABS_X' in b and 'ABS_Y' in b and 'BTN_TOUCH' in b and ('pen' in b.lower() or 'stylus' in b.lower())]
    if len(matching) != 1:
        raise SystemExit('Cannot identify exactly one dedicated pen input device. No settings were changed.')
    block = matching[0]
    node = re.search(r'(/dev/input/event\d+)', block).group(1)
    max_x = int(re.search(r'ABS_X\s*:.*?max (\d+)', block).group(1))
    max_y = int(re.search(r'ABS_Y\s*:.*?max (\d+)', block).group(1))
    dimensions = re.search(r'Physical size:\s*(\d+)x(\d+)', adb('shell', 'wm', 'size'))
    display = adb('shell', 'dumpsys', 'display')
    dpi = re.search(r'density \d+,\s*([\d.]+) x ([\d.]+) dpi', display)
    if dimensions is None or dpi is None:
        raise SystemExit('Physical screen DPI not available; explicit calibration support is needed for this device.')
    width = int(dimensions[1]) / float(dpi[1]) * 25.4
    height = int(dimensions[2]) / float(dpi[2]) * 25.4
    print(f'Pen input verified: {node}; estimated active screen {width:.2f} x {height:.2f} mm.')
    if not args.apk.is_file():
        raise SystemExit('Build the APK first with build.py.')
    if not args.skip_install:
        print(adb('install', '-r', str(args.apk.resolve())))
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    # Only this app's randomly generated local credential is read; it is never printed or published.
    token = ''
    for _ in range(12):
        try:
            token = adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/collector.token', private=True)
            if re.fullmatch(r'[0-9a-f]{64}', token):
                break
        except RuntimeError:
            pass
        time.sleep(0.3)
    if not re.fullmatch(r'[0-9a-f]{64}', token):
        raise SystemExit('Open PenDistance on the tablet once, then run this script again.')
    port = int(adb('forward', 'tcp:0', 'tcp:38761'))
    try:
        current = None
        try:
            current = request(port, token)
        except (OSError, ValueError):
            pass
        if current is not None and args.restart:
            request(port, token, 'pause')
            pid = adb('shell', 'cat', REMOTE + '/collector.pid').strip()
            if not pid.isdigit():
                raise SystemExit('Refusing to stop an unrecognized collector process.')
            process = adb('shell', 'ps', '-p', pid, '-o', 'ARGS')
            if 'io.github.pendistance.Collector' not in process:
                raise SystemExit('Refusing to stop an unrelated process.')
            adb('shell', 'kill', pid)
            time.sleep(0.5)
            current = None
        if current is None:
            adb('shell', 'mkdir', '-p', REMOTE)
            adb('shell', 'chmod', '700', REMOTE)
            adb('push', str(args.apk.resolve()), REMOTE + '/collector.apk')
            # Credentials go through stdin, rather than process command-line arguments.
            script = f'''PEN_DISTANCE_TOKEN={shlex.quote(token)} CLASSPATH={REMOTE}/collector.apk nohup /system/bin/app_process /system/bin io.github.pendistance.Collector {node} {REMOTE}/stats.json {max_x} {max_y} {width} {height} >{REMOTE}/collector.log 2>&1 </dev/null &
echo $! >{REMOTE}/collector.pid
'''
            adb('shell', 'sh', private=True, data=script.encode())
        status = None
        for _ in range(20):
            try:
                status = request(port, token, 'start')
                if status.get('readerAlive'):
                    break
            except (OSError, ValueError):
                pass
            time.sleep(0.25)
        if status is None or not status.get('readerAlive'):
            raise SystemExit('Collector did not start. Inspect /data/local/tmp/pen-distance/collector.log locally.')
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
        print('Recording is active on the tablet. USB may now be disconnected.')
        print('A tablet reboot requires running this starter again; saved daily totals are retained.')
    finally:
        adb('forward', '--remove', 'tcp:' + str(port))

if __name__ == '__main__':
    main()
