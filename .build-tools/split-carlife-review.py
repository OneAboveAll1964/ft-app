"""Generate bounded, numbered review parts; verify every exported source line."""
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
source = root / "dist/FT-CarLife-Gemini-review-2026-10-28.txt"
target = root / "dist/FT-CarLife-Gemini-2026-10-28-parts"
target.mkdir(parents=True, exist_ok=True)
text = source.read_text(encoding="utf-8-sig")
context = text.split("СОСТОЯНИЕ ВКЛЮЧЁННЫХ ФАЙЛОВ ОТНОСИТЕЛЬНО HEAD", 1)[0].rstrip()
blocks = re.findall(r"^===== BEGIN FILE: (.+?) =====\n(.*?)\n===== END FILE: \1 =====", text, re.M | re.S)
assert blocks and len(blocks) == len({path for path, _ in blocks})
labels = {
    "protocol": "Протокол, сессия и шифрование",
    "transport": "TCP, USB и очереди отправки",
    "service": "CarLifeService: координация и Android lifecycle",
    "discovery": "Поиск магнитолы, Bluetooth и Wi-Fi Direct",
    "audio": "Аудио, навигация и метаданные",
    "video": "Изображение, кодирование и Presentation",
    "integration": "Общие зависимости и интеграция приложения",
    "simulators": "Симуляторы магнитолы",
}
protocol = {"CarLifeSession", "CarLifeCrypto", "CarLifeFraming", "CarLifeProtocol", "Bytes", "ProtoLite", "CarLifeEncryptionSession", "SongInfoSession"}
transport = {"CarLifeLink", "UsbLink", "CarLifeWriter", "WifiChannelLink", "CarWatch"}
audio = {"AaMusicRelay", "AudioCapture", "CarAudioBus", "PcmPacer", "CarVoice", "MusicInfo", "NavigationInfo", "MediaStream", "NavigationRelay", "NavigationIcons", "SongArtwork", "SongInfo", "SoundCheck", "PhoneVolume"}

def group(path):
    name = Path(path).stem.removesuffix("Test")
    if path.startswith("tools/sim/"): return "simulators"
    if name in protocol: return "protocol"
    if name in transport: return "transport"
    if name == "CarLifeService": return "service"
    if name in audio: return "audio"
    if "/projection/" in path or name in {"VideoFeed", "VideoProfileSession"}: return "video"
    if "/carlife/" in path: return "discovery"
    return "integration"

groups = defaultdict(list)
sources = {}
for path, body in blocks:
    raw = (root / path).read_bytes()
    lines = raw.decode("utf-8-sig").splitlines()
    expected = [f"{i:5} | {line}" for i, line in enumerate(lines, 1)]
    assert body.splitlines() == expected, f"Stale source: {path}"
    sources[path] = {"sha256": hashlib.sha256(raw).hexdigest(), "lines": len(lines)}
    groups[group(path)].append((path, expected))

# Reserve space for the per-part index and end marker as well as the body.
MAX_BODY = 54_000
MAX_LINES = 1000
parts = []
for key in labels:
    current, size, count = [], 0, 0
    def flush():
        global current, size, count
        if current:
            parts.append((key, current))
            current, size, count = [], 0, 0
    for path, lines in groups[key]:
        chunks, chunk, chunk_size = [], [], 0
        overhead = len(path.encode("utf-8")) * 2 + 350
        for line in lines:
            length = len((line + "\n").encode("utf-8"))
            assert length + overhead < MAX_BODY, f"Oversized line: {path}"
            if chunk and (chunk_size + length + overhead > MAX_BODY or len(chunk) >= MAX_LINES):
                chunks.append(chunk)
                chunk, chunk_size = [], 0
            chunk.append(line)
            chunk_size += length
        if chunk: chunks.append(chunk)
        for chunk in chunks:
            start = int(chunk[0].split(" | ", 1)[0])
            end = int(chunk[-1].split(" | ", 1)[0])
            header = (f"===== BEGIN FILE RANGE: {path} =====\n"
                      f"Исходные строки: {start}–{end} из {len(lines)}; sha256 полного файла: {sources[path]['sha256']}\n")
            body = header + "\n".join(chunk) + f"\n===== END FILE RANGE: {path} [{start}–{end}] =====\n"
            length = len(body.encode("utf-8"))
            if current and (size + length > MAX_BODY or count + len(chunk) > MAX_LINES): flush()
            current.append({"path": path, "start": start, "end": end, "body": body})
            size += length
            count += len(chunk)
    flush()

manifest = {"snapshot": context.splitlines()[1], "source": source.name, "sources": sources, "parts": []}
coverage = defaultdict(list)
index = []
total = len(parts)
for number, (key, entries) in enumerate(parts, 1):
    filename = f"{number:02d}-{key}.txt"
    marker = f"END-OF-PART-{number:02d}-OF-{total:02d}"
    listing = "\n".join(f"- {entry['path']}:{entry['start']}–{entry['end']}" for entry in entries)
    header = (f"FT BAIDU CARLIFE — ЧАСТЬ {number:02d}/{total:02d}\nТема: {labels[key]}\n"
              f"{context.splitlines()[1]}\n"
              "Контекст и запрос: 00-START-HERE.txt. Номера строк относятся к исходникам.\n"
              f"Ожидаемый последний маркер: {marker}\n\nСОДЕРЖИМОЕ ЭТОЙ ЧАСТИ\n{listing}\n\n")
    content = header + "\n".join(entry["body"] for entry in entries) + f"\n{marker}\n"
    data = content.encode("utf-8")
    assert len(data) <= 64_000, filename
    (target / filename).write_bytes(data)
    ranges = [{k: entry[k] for k in ("path", "start", "end")} for entry in entries]
    manifest["parts"].append({"file": filename, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "ranges": ranges})
    index.append(f"{filename} — {labels[key]} ({len(data) / 1024:.1f} КиБ)")
    for entry in entries: coverage[entry["path"]].extend(range(entry["start"], entry["end"] + 1))
for path, info in sources.items():
    assert coverage[path] == list(range(1, info["lines"] + 1)), f"Lost or duplicate lines: {path}"

instructions = f"""FT — BAIDU CARLIFE: НАБОР ДЛЯ GEMINI

Это одна версия кода, разделённая на {total} небольших частей (каждая до 64 КБ).
Большой файл в предыдущем ревью обрезался на FrameGate.kt:34. Используй эти части.
Полный экспорт оставлен рядом как архив снимка; загружать его вместе с частями не нужно.

КАК ПЕРЕДАВАТЬ
1. Начни новый чат Gemini и загрузи этот 00-START-HERE.txt.
2. Загружай части по порядку, по одной или две на сообщение. Не вставляй все сразу.
3. Проси подтвердить последний END-OF-PART-XX-OF-{total:02d}. Если Gemini его не видит,
   повтори конкретную часть. Подтверждение маркера помогает обнаружить обрезку.
4. Когда получены все части, отправь заключительный запрос ниже.
5. Если весь набор не помещается в контекст модели, делай отдельные ревью по темам
   и добавляй явно запрошенные зависимости. Не считать отсутствие файла дефектом.

ПЕРВОЕ СООБЩЕНИЕ ДЛЯ КОПИРОВАНИЯ
Я передам актуальные исходники Baidu CarLife в {total} частях. Пока собирай контекст.
После каждой загрузки сообщай номера полученных частей и их последние маркеры.
Если маркера нет, явно скажи, что часть обрезана. Не выноси окончательные выводы
по отсутствующим зависимостям. Область ревью и ограничения приведены ниже.

ЗАКЛЮЧИТЕЛЬНОЕ СООБЩЕНИЕ ДЛЯ КОПИРОВАНИЯ
Все {total} частей переданы. Сначала перечисли полученные части и пропуски.
Затем выполни запрос «ЗАПРОС ДЛЯ GEMINI» ниже по доступному коду.
Проверяй сценарии по реализации и тестам; гипотезы не называй доказанными багами.
Для находок укажи исходный путь и номер строки, цепочку вызовов и минимальное
исправление. Не заявляй о мёртвом коде во всём приложении только потому, что его
потребитель не включён в этот предметный набор. Уже внесённые правки тоже проверяй.

{context}

ПОРЯДОК ЧАСТЕЙ
{chr(10).join(index)}

manifest.json содержит SHA-256 файлов и частей и полную карту диапазонов строк.
При генерации проверено: каждый исходный файл совпадает с рабочим деревом,
каждая исходная строка включена ровно один раз; пропусков между частями нет.

END-OF-INTRODUCTION
"""
(target / "00-START-HERE.txt").write_text(instructions, encoding="utf-8", newline="\n")
(target / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
# Only retire earlier generated part names from this exact task-owned directory.
expected = {part["file"] for part in manifest["parts"]}
for old in target.glob("[0-9][0-9]-*.txt"):
    if old.name != "00-START-HERE.txt" and old.name not in expected:
        assert old.resolve().parent == target.resolve()
        old.unlink()
print(f"Verified {len(sources)} files, {sum(s['lines'] for s in sources.values())} lines; {total} parts; largest {max(p['bytes'] for p in manifest['parts'])} bytes")
print(target)
