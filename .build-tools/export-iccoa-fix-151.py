"""Archive build-151 sources, reference and the preserved build-150 log selection."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
MAIN = 'app/src/main/java/app/ft/'
TEST = 'app/src/test/java/app/ft/'
REF = '.build-tools/iccoa-reference/decompiled-7.62/sources/com/vadym/carprobe/'
OUT = ROOT / 'dist/FT-ICCOA-fix-151-2026-10-28.zip'

paths = list((ROOT / MAIN / 'iccoa').glob('*.kt'))
paths += list((ROOT / TEST / 'iccoa').glob('*.kt'))
for name in ['AaMicrophone', 'AaMicrophoneInput', 'AaSession', 'AaMessages',
             'AaProtocol', 'AaSessionLifecycle', 'AaHeadUnitService']:
    paths.append(ROOT / MAIN / 'aa' / (name + '.kt'))
for name in ['AaMicrophone', 'AaMicrophoneInput', 'AaSessionLifecycle']:
    paths.append(ROOT / TEST / 'aa' / (name + 'Test.kt'))
for name in ['BluetoothCalls', 'CarBluetoothReconnect', 'CarAudioBus', 'ProtoLite']:
    paths.append(ROOT / MAIN / 'core' / (name + '.kt'))
for name in ['CarSession', 'CarVoiceInput', 'CarVoiceChannel', 'CarLinkService',
             'CarConfigInfo', 'Auth', 'Experiments', 'Prefs']:
    paths.append(ROOT / REF / (name + '.java'))
paths += [ROOT / 'app/build.gradle.kts',
          ROOT / 'docs/ICCOA-151-VOICE-FIX-2026-10-28.md',
          ROOT / 'docs/ICCOA-REFERENCE-AUDIT-2026-10-28.md',
          ROOT / 'docs/ICCOA-CALLS-MIC-2026-10-28.md']

reports = sorted((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert reports, 'Unit test results are required'
totals = {key: 0 for key in ['tests', 'failures', 'errors', 'skipped']}
for path in reports:
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, '0'))
assert totals['failures'] == totals['errors'] == 0, totals
apk = ROOT / 'dist/FT-3.9.37-iccoa-test-151.apk'
assert apk.is_file(), 'Copy the successfully assembled APK first'
manifest = {'versionCode': 151, 'versionName': '3.9.37-iccoa-test',
            'logsVersionCode': 150, 'hardwareTest': 'not performed',
            'appDebugUnitTests': totals, 'source': []}
manifest['apk'] = {'name': apk.name, 'bytes': apk.stat().st_size,
                   'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
with ZipFile(OUT, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(set(paths)):
        data = path.read_bytes()
        name = 'source/' + path.relative_to(ROOT).as_posix()
        archive.writestr(name, data)
        manifest['source'].append({'path': name, 'bytes': len(data),
                                   'sha256': hashlib.sha256(data).hexdigest()})
    logs = ROOT / 'dist/FT-ICCOA-review-150-2026-10-28/01-LOGS.txt'
    data = logs.read_bytes()
    archive.writestr('01-LOGS-BUILD-150.txt', data)
    manifest['logs'] = {'path': '01-LOGS-BUILD-150.txt', 'bytes': len(data),
                        'sha256': hashlib.sha256(data).hexdigest()}
    archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
    archive.writestr('README-RU.txt',
        'Исходники — сборка 151. Журналы — предыдущая сборка 150, новых автомобильных тестов нет.\n'
        'Начать с source/docs/ICCOA-151-VOICE-FIX-2026-10-28.md.\n'
        'В референсе смотреть CarVoiceInput.start/stopCarMicrophone, CarLinkService.reportPhoneState,\n'
        'CarSession.notifyPhoneState/notifyMicrophoneState/ucarConn.\n'
        'В FT — IccoaMicrophone, IccoaService, IccoaCarConfig, IccoaLaneServer и тесты этих классов;\n'
        'для AA — AaSession, AaMicrophoneInput, AaMessages; для HFP — BluetoothCalls, CarBluetoothReconnect.\n'
        'Обнаруженное отличие voice-сигнализации исправлено. Связь с отказом ASR ещё не проверена на ГУ.\n'
        'Причина отказа HFP не установлена; метод звонков по одному имени не реализовывать.\n')
with ZipFile(OUT) as archive:
    for item in manifest['source'] + [manifest['logs']]:
        assert hashlib.sha256(archive.read(item['path'])).hexdigest() == item['sha256']
print(json.dumps({'archive': str(OUT), 'bytes': OUT.stat().st_size,
                  'sourceFiles': len(manifest['source']), 'tests': totals,
                  'apk': manifest['apk']}, ensure_ascii=False))
