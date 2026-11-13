from pathlib import Path
import json
import re

root = Path(__file__).resolve().parent.parent
bundle = root / 'dist' / 'FT-Gemini-review.txt'
content = bundle.read_text(encoding='utf-8-sig')
sections = re.findall(r'^===== BEGIN FILE: (.+?) =====\n(.*?)^===== END FILE: \1 =====$', content, re.M | re.S)
assert len(sections) == 204, len(sections)
paths = set()
for relative, numbered in sections:
    assert relative not in paths, relative
    paths.add(relative)
    filename = Path(relative).name.lower()
    assert filename not in {'keystore.properties', 'local.properties', 'gradle.properties', 'credentials.json', 'auth.json'}
    assert not filename.startswith('.env')
    assert Path(relative).suffix.lower() not in {'.jks', '.keystore', '.pem', '.p12', '.pfx', '.key'}
    original = (root / relative).read_text(encoding='utf-8-sig').splitlines()
    exported = numbered.rstrip('\n').splitlines()
    # The separator after each file can add a blank line to the container.
    while exported and not exported[-1].strip():
        exported.pop()
    assert len(original) == len(exported), (relative, len(original), len(exported))
    for number, (source, packed) in enumerate(zip(original, exported), 1):
        match = re.fullmatch(r'\s*(\d+) \| ?(.*)', packed)
        assert match and int(match.group(1)) == number, (relative, number)
        assert match.group(2) == source, (relative, number)

assert any(p.endswith('/IccoaSession.kt') for p in paths)
assert 'docs/ICCOA-TESTER-RU.md' in paths
assert 'app/build.gradle.kts' in paths
assert 'versionCode = 133' in content or 'versionCode 133' in content
assert content.rstrip().endswith('===== END OF FT REVIEW SNAPSHOT =====')
assert not re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', content)
print(json.dumps({'verified_files': len(paths), 'source_lines_match': True, 'line_numbers_match': True, 'iccoa_included': True, 'excluded_secrets_absent': True, 'complete': True}))
