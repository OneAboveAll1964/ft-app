"""Refresh the existing focused review export from its explicit source manifest."""
from datetime import datetime, timedelta, timezone
import hashlib
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
output = root / "dist/FT-CarLife-Gemini-review-2026-10-28.txt"
text = output.read_text(encoding="utf-8-sig")
additions = {
    "app/src/main/java/app/ft/carlife/CarLifeWriter.kt": "1. ЯДРО BAIDU CARLIFE",
    "app/src/test/java/app/ft/carlife/CarLifeCryptoTest.kt": "4. СУЩЕСТВУЮЩИЕ ТЕСТЫ",
    "app/src/test/java/app/ft/carlife/CarLifeEncryptionSessionTest.kt": "4. СУЩЕСТВУЮЩИЕ ТЕСТЫ",
    "app/src/test/java/app/ft/carlife/CarLifeWriterTest.kt": "4. СУЩЕСТВУЮЩИЕ ТЕСТЫ",
}
for directory, heading in (
    ("app/src/main/java/app/ft/carlife", "1. ЯДРО BAIDU CARLIFE"),
    ("app/src/main/java/app/ft/projection", "2. ОБЩЕЕ ИЗОБРАЖЕНИЕ, ЗВУК И УПРАВЛЕНИЕ"),
    ("app/src/test/java/app/ft/carlife", "4. СУЩЕСТВУЮЩИЕ ТЕСТЫ"),
    ("app/src/test/java/app/ft/projection", "4. СУЩЕСТВУЮЩИЕ ТЕСТЫ"),
):
    for path in sorted((root / directory).glob("*.kt")):
        additions[path.relative_to(root).as_posix()] = heading
for relative, heading in additions.items():
    if f"===== BEGIN FILE: {relative} =====" in text:
        continue
    before, manifest_group, body_group = text.split(heading + "\n")
    text = (before + heading + "\n" + f"{relative} | 0 строк | sha256={'0' * 64}\n" + manifest_group
            + heading + "\n" + f"===== BEGIN FILE: {relative} =====\n===== END FILE: {relative} =====\n\n" + body_group)
record = re.compile(r"^([^\n]+) \| \d+ строк \| sha256=[a-f0-9]{64}$", re.M)
sources = {}
for match in record.finditer(text):
    relative = match[1]
    path = root / relative
    assert path.resolve().is_relative_to(root.resolve())
    if path.is_file():
        data = path.read_bytes()
        sources[relative] = (data.decode("utf-8-sig").splitlines(), hashlib.sha256(data).hexdigest())

def manifest(match):
    source = sources.get(match[1])
    return f"{match[1]} | {len(source[0])} строк | sha256={source[1]}" if source else ""

def body(match):
    relative = match[1]
    source = sources.get(relative)
    if not source:
        return ""
    numbered = "\n".join(f"{i:5} | {line}" for i, line in enumerate(source[0], 1))
    return f"===== BEGIN FILE: {relative} =====\n{numbered}\n===== END FILE: {relative} ====="

text = record.sub(manifest, text)
text = re.sub(r"^===== BEGIN FILE: (.+?) =====\n.*?^===== END FILE: \1 =====", body, text, flags=re.M | re.S)
text = text.replace(
    "- CarIccoaBle.kt включён намеренно: он находится в carlife/ и вызывается\n"
    "  из CarLifeService как путь обнаружения/получения сети.",
    "- Старый CarIccoaBle и обработка его Wi-Fi предложений удалены из CarLife.\n"
    "  ICCOA BLE и подключение к точке доступа машины находятся в отдельном backend."
)
stamp = datetime.now(timezone(timedelta(hours=3))).isoformat(timespec="seconds")
text = re.sub(r"^Снимок: .+$", f"Снимок: {stamp} (Asia/Baghdad)", text, flags=re.M)
total = sum(len(source[0]) for source in sources.values())
text = re.sub(r"^Файлов: .+$", f"Файлов: {len(sources)}; строк исходного текста: {total}.", text, flags=re.M)
status = subprocess.run(["git", "status", "--short", "--", *sources], cwd=root,
                        check=True, capture_output=True, encoding="utf-8").stdout.rstrip()
text = re.sub(r"(?<=СОСТОЯНИЕ ВКЛЮЧЁННЫХ ФАЙЛОВ ОТНОСИТЕЛЬНО HEAD\n).*?(?=\n\nОГЛАВЛЕНИЕ)",
              lambda _: status, text, flags=re.S)
output.write_text(text, encoding="utf-8", newline="\n")
print(f"Refreshed {len(sources)} files, {total} source lines")
