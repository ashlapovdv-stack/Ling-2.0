from pathlib import Path


def replace_required(text: str, old: str, new: str, name: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise RuntimeError(f"Missing patch marker: {name}")
    return text.replace(old, new, 1)


# Proper launcher mipmap + version bump
gradle_path = Path("app/build.gradle.kts")
gradle = gradle_path.read_text()
gradle = replace_required(
    gradle,
    'val outputIcon = layout.projectDirectory.file("src/main/res/drawable-nodpi/ic_launcher.jpg")',
    'val outputIcon = layout.projectDirectory.file("src/main/res/mipmap-nodpi/ling_launcher.jpg")',
    "launcher output",
)
gradle = replace_required(gradle, "versionCode = 10", "versionCode = 11", "versionCode")
gradle = replace_required(gradle, 'versionName = "0.1.9"', 'versionName = "0.1.10"', "versionName")
gradle_path.write_text(gradle)

manifest_path = Path("app/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text()
manifest = replace_required(
    manifest,
    'android:icon="@drawable/ic_launcher"',
    'android:icon="@mipmap/ling_launcher"',
    "manifest icon",
)
manifest = replace_required(
    manifest,
    'android:roundIcon="@drawable/ic_launcher"',
    'android:roundIcon="@mipmap/ling_launcher"',
    "manifest round icon",
)
manifest_path.write_text(manifest)

# Default output language setting
app_path = Path("app/src/main/java/com/ling20/translator/LingApp.kt")
app = app_path.read_text()

if "PREF_DEFAULT_TARGET" not in app:
    app = replace_required(
        app,
        'private const val PREF_DEFAULT_SOURCE = "default_source_language"\n',
        'private const val PREF_DEFAULT_SOURCE = "default_source_language"\n'
        'private const val PREF_DEFAULT_TARGET = "default_target_language"\n',
        "target pref constant",
    )

source_init = '''    val initialDefaultSource = remember(translationPreferences) {
        val saved = translationPreferences.getString(
            PREF_DEFAULT_SOURCE,
            SourceLanguageOption.RUSSIAN.name,
        )
        runCatching { SourceLanguageOption.valueOf(saved ?: SourceLanguageOption.RUSSIAN.name) }
            .getOrDefault(SourceLanguageOption.RUSSIAN)
    }
'''
if "val initialDefaultTarget = remember(translationPreferences)" not in app:
    app = replace_required(
        app,
        source_init,
        source_init + '''    val initialDefaultTarget = remember(translationPreferences) {
        val saved = translationPreferences.getString(
            PREF_DEFAULT_TARGET,
            Language.ENGLISH.name,
        )
        runCatching { Language.valueOf(saved ?: Language.ENGLISH.name) }
            .getOrDefault(Language.ENGLISH)
    }
''',
        "initial target",
    )

old_state = '''    var defaultSourceName by remember { mutableStateOf(initialDefaultSource.name) }

    var sourceOptionName by rememberSaveable { mutableStateOf(initialDefaultSource.name) }
    var targetName by rememberSaveable { mutableStateOf(Language.ENGLISH.name) }'''
new_state = '''    var defaultSourceName by remember { mutableStateOf(initialDefaultSource.name) }
    var defaultTargetName by remember { mutableStateOf(initialDefaultTarget.name) }

    var sourceOptionName by rememberSaveable {
        mutableStateOf(
            if (initialDefaultSource.language == initialDefaultTarget) {
                SourceLanguageOption.AUTO.name
            } else {
                initialDefaultSource.name
            },
        )
    }
    var targetName by rememberSaveable { mutableStateOf(initialDefaultTarget.name) }'''
if "defaultTargetName" not in app:
    app = replace_required(app, old_state, new_state, "target state")

old_defaults = '''    val defaultSource = runCatching { SourceLanguageOption.valueOf(defaultSourceName) }
        .getOrDefault(SourceLanguageOption.RUSSIAN)
'''
new_defaults = old_defaults + '''    val defaultTarget = runCatching { Language.valueOf(defaultTargetName) }
        .getOrDefault(Language.ENGLISH)
'''
if "val defaultTarget = runCatching" not in app:
    app = replace_required(app, old_defaults, new_defaults, "default target value")

old_call = '''                    SettingsPage.TRANSLATION -> TranslationSettings(
                        defaultSource = defaultSource,
                        onBack = { settingsPage = SettingsPage.ROOT },
                        onDefaultSourceChanged = { selected ->
                            defaultSourceName = selected.name
                            translationPreferences.edit()
                                .putString(PREF_DEFAULT_SOURCE, selected.name)
                                .apply()
                            if (input.isBlank() && output.isBlank()) {
                                sourceOptionName = selected.name
                            }
                        },
                    )'''
new_call = '''                    SettingsPage.TRANSLATION -> TranslationSettings(
                        defaultSource = defaultSource,
                        defaultTarget = defaultTarget,
                        onBack = { settingsPage = SettingsPage.ROOT },
                        onDefaultSourceChanged = { selected ->
                            defaultSourceName = selected.name
                            translationPreferences.edit()
                                .putString(PREF_DEFAULT_SOURCE, selected.name)
                                .apply()
                            if (input.isBlank() && output.isBlank()) {
                                sourceOptionName = if (selected.language == target) {
                                    SourceLanguageOption.AUTO.name
                                } else {
                                    selected.name
                                }
                            }
                        },
                        onDefaultTargetChanged = { selected ->
                            defaultTargetName = selected.name
                            translationPreferences.edit()
                                .putString(PREF_DEFAULT_TARGET, selected.name)
                                .apply()
                            if (input.isBlank() && output.isBlank()) {
                                targetName = selected.name
                                if (sourceOption.language == selected) {
                                    sourceOptionName = SourceLanguageOption.AUTO.name
                                }
                            }
                        },
                    )'''
if "onDefaultTargetChanged = { selected ->" not in app:
    app = replace_required(app, old_call, new_call, "translation settings call")

old_sig = '''private fun TranslationSettings(
    defaultSource: SourceLanguageOption,
    onBack: () -> Unit,
    onDefaultSourceChanged: (SourceLanguageOption) -> Unit,
)'''
new_sig = '''private fun TranslationSettings(
    defaultSource: SourceLanguageOption,
    defaultTarget: Language,
    onBack: () -> Unit,
    onDefaultSourceChanged: (SourceLanguageOption) -> Unit,
    onDefaultTargetChanged: (Language) -> Unit,
)'''
if "defaultTarget: Language" not in app:
    app = replace_required(app, old_sig, new_sig, "translation settings signature")

input_card = '''        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Язык ввода по умолчанию",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SourceLanguagePicker(
                    source = defaultSource,
                    enabled = true,
                    onSelected = onDefaultSourceChanged,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Доступны: Автоопределение, Русский, English и 中文. Выбранный язык используется при следующем запуске приложения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
'''
output_card = '''
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Язык вывода по умолчанию",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LanguagePicker(
                    language = defaultTarget,
                    enabled = true,
                    onSelected = onDefaultTargetChanged,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Доступны: Русский, English и 中文. Выбранный язык используется как язык результата при следующем запуске приложения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
'''
if '"Язык вывода по умолчанию"' not in app:
    app = replace_required(app, input_card, input_card + output_card, "target settings card")

app_path.write_text(app)

checks = {
    "mipmap launcher": '@mipmap/ling_launcher' in manifest_path.read_text(),
    "v0.1.10": 'versionName = "0.1.10"' in gradle_path.read_text(),
    "target pref": "PREF_DEFAULT_TARGET" in app,
    "target settings UI": '"Язык вывода по умолчанию"' in app,
    "target callback": "onDefaultTargetChanged" in app,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Patch validation failed: " + ", ".join(failed))

print("v0.1.10 patch applied successfully")
