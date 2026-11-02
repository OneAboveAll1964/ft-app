from pathlib import Path

root = Path(__file__).resolve().parent.parent
target = root / '.build-tools/custom-latest'
changes = {}

def edit(name, old, new):
    path = target / name
    text = changes.get(path, path.read_text(encoding='utf-8'))
    assert text.count(old) == 1, (name, old[:100], text.count(old))
    changes[path] = text.replace(old, new, 1)

base = 'app/src/main/java/app/ft/'
edit(base + 'core/Prefs.kt', '    var aaClusterNavigation:', '    var aaCompactReturnButton: Boolean get() = bool("aaCompactReturnButton", false); set(v) = set("aaCompactReturnButton", v)\n    var aaClusterNavigation:')
edit(base + 'core/Backup.kt', '"aaCorner"\n', '"aaCorner", "aaCompactReturnButton"\n')
edit(base + 'ui/car/CarStyle.kt', 'val night: Boolean = false\n', 'val night: Boolean = false,\n    val aaCompactReturnButton: Boolean = false\n')
edit(base + 'ui/car/CarStyle.kt', 'corner = p.aaCorner.coerceIn(0, 3),', 'corner = p.aaCorner.coerceIn(0, 3),\n            aaCompactReturnButton = p.aaCompactReturnButton,')
settings = (root / (base + 'ui/settings/SettingsScreen.kt')).read_text(encoding='utf-8')
start = settings.index('            BoolSetting(stringResource(R.string.aa_compact_return_button)')
end = settings.index('            BoolSetting(stringResource(R.string.aa_cluster_navigation)', start)
edit(base + 'ui/settings/SettingsScreen.kt', '        SettingsPage.ANDROID_AUTO -> {\n', '        SettingsPage.ANDROID_AUTO -> {\n' + settings[start:end])
screen_name = base + 'ui/car/CarScreen.kt'
screen = (root / screen_name).read_text(encoding='utf-8')
imports = '\n'.join(line for line in screen.splitlines() if line.startswith('import androidx.compose.ui.semantics.'))
edit(screen_name, 'import androidx.compose.ui.platform.LocalDensity\n', 'import androidx.compose.ui.platform.LocalDensity\n' + imports + '\n')
edit(screen_name, 'fun Modifier.touchZone(key: String): Modifier', 'fun Modifier.touchZone(key: String, slopPx: Int = 8): Modifier')
edit(screen_name, 'Rect(b.left.toInt() - 8, b.top.toInt() - 8, b.right.toInt() + 8, b.bottom.toInt() + 8)', 'Rect(b.left.toInt() - slopPx, b.top.toInt() - slopPx, b.right.toInt() + slopPx, b.bottom.toInt() + slopPx)')
edit(screen_name, 'CornerPill(style.corner) {\n', 'CornerPill(style.corner, compact = state.aaOverlay && style.aaCompactReturnButton) {\n')
current_screen = changes[target / screen_name]
start = current_screen.index('@Composable\nprivate fun CornerPill(')
end = current_screen.index('@Composable\nprivate fun Launcher(', start)
source_start = screen.index('@Composable\nprivate fun CornerPill(')
source_end = screen.index('@Composable\nprivate fun Launcher(', source_start)
edit(screen_name, current_screen[start:end], screen[source_start:source_end])

for locale in ['values', 'values-ar', 'values-b+ckb', 'values-b+kmr+Arab']:
    name = f'app/src/main/res/{locale}/strings.xml'
    source = (root / name).read_text(encoding='utf-8')
    additions = '\n'.join(line for line in source.splitlines() if any(f'name="{key}"' in line for key in ['aa_compact_return_button', 'aa_compact_return_button_body', 'aa_return_to_ft']))
    assert len(additions.splitlines()) == 3
    edit(name, '    <string name="aa_cluster_navigation">', additions + '\n    <string name="aa_cluster_navigation">')
edit('app/src/main/res/values-ru/strings.xml', '    <string name="aa_cluster_navigation">', '''    <string name="aa_compact_return_button">Компактная кнопка FT</string>
    <string name="aa_compact_return_button_body">Небольшая тёмная круглая кнопка возврата поверх Android Auto. При выключении используется прежний вид.</string>
    <string name="aa_return_to_ft">Вернуться в FT</string>
    <string name="aa_cluster_navigation">''')
note = next(line for line in (root / 'README.md').read_text(encoding='utf-8').splitlines() if line.startswith('**Settings → Android Auto → Compact FT button**'))
edit('README.md', '## Android Auto\n', '## Android Auto\n\n' + note + '\n')
edit('app/build.gradle.kts', 'versionCode = 131', 'versionCode = 132')
edit('app/build.gradle.kts', 'versionName = "3.9.17-custom"', 'versionName = "3.9.18-custom"')
for path, text in changes.items():
    path.write_text(text, encoding='utf-8')
print(f'Prepared {len(changes)} files for 3.9.18-custom')
