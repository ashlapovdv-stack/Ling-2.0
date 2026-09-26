from pathlib import Path

path = Path('app/src/main/java/com/ling20/translator/LingApp.kt')
text = path.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f'Pattern not found:\n{old[:300]}')
    text = text.replace(old, new, 1)


replace_once(
    'private enum class SettingsPage { ROOT, TRANSLATION, MODEL, HISTORY }',
    'private enum class SettingsPage { ROOT, TRANSLATION, CAMERA, MODEL, HISTORY }',
)

replace_once(
    'private const val PREF_DEFAULT_TARGET = "default_target_language"\n',
    'private const val PREF_DEFAULT_TARGET = "default_target_language"\n'
    'private const val CAMERA_PREFS = "ling_camera_settings"\n'
    'private const val PREF_CAMERA_DEFAULT_SOURCE = "camera_default_source_language"\n'
    'private const val PREF_CAMERA_DEFAULT_TARGET = "camera_default_target_language"\n',
)

replace_once(
    '    val translationPreferences = remember(context) {\n'
    '        context.applicationContext.getSharedPreferences(TRANSLATION_PREFS, Context.MODE_PRIVATE)\n'
    '    }\n',
    '    val translationPreferences = remember(context) {\n'
    '        context.applicationContext.getSharedPreferences(TRANSLATION_PREFS, Context.MODE_PRIVATE)\n'
    '    }\n'
    '    val cameraPreferences = remember(context) {\n'
    '        context.applicationContext.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE)\n'
    '    }\n',
)

marker = (
    '    val initialDefaultTarget = remember(translationPreferences) {\n'
    '        val saved = translationPreferences.getString(\n'
    '            PREF_DEFAULT_TARGET,\n'
    '            Language.ENGLISH.name,\n'
    '        )\n'
    '        runCatching { Language.valueOf(saved ?: Language.ENGLISH.name) }\n'
    '            .getOrDefault(Language.ENGLISH)\n'
    '    }\n'
)
addition = marker + (
    '    val initialCameraDefaultSource = remember(cameraPreferences, initialDefaultSource) {\n'
    '        val saved = cameraPreferences.getString(\n'
    '            PREF_CAMERA_DEFAULT_SOURCE,\n'
    '            initialDefaultSource.name,\n'
    '        )\n'
    '        runCatching { SourceLanguageOption.valueOf(saved ?: initialDefaultSource.name) }\n'
    '            .getOrDefault(initialDefaultSource)\n'
    '    }\n'
    '    val initialCameraDefaultTarget = remember(cameraPreferences, initialDefaultTarget) {\n'
    '        val saved = cameraPreferences.getString(\n'
    '            PREF_CAMERA_DEFAULT_TARGET,\n'
    '            initialDefaultTarget.name,\n'
    '        )\n'
    '        runCatching { Language.valueOf(saved ?: initialDefaultTarget.name) }\n'
    '            .getOrDefault(initialDefaultTarget)\n'
    '    }\n'
)
replace_once(marker, addition)

replace_once(
    '    var defaultSourceName by remember { mutableStateOf(initialDefaultSource.name) }\n'
    '    var defaultTargetName by remember { mutableStateOf(initialDefaultTarget.name) }\n',
    '    var defaultSourceName by remember { mutableStateOf(initialDefaultSource.name) }\n'
    '    var defaultTargetName by remember { mutableStateOf(initialDefaultTarget.name) }\n'
    '    var cameraDefaultSourceName by remember { mutableStateOf(initialCameraDefaultSource.name) }\n'
    '    var cameraDefaultTargetName by remember { mutableStateOf(initialCameraDefaultTarget.name) }\n',
)

replace_once(
    '    val defaultTarget = runCatching { Language.valueOf(defaultTargetName) }\n'
    '        .getOrDefault(Language.ENGLISH)\n',
    '    val defaultTarget = runCatching { Language.valueOf(defaultTargetName) }\n'
    '        .getOrDefault(Language.ENGLISH)\n'
    '    val cameraDefaultSource = runCatching { SourceLanguageOption.valueOf(cameraDefaultSourceName) }\n'
    '        .getOrDefault(initialCameraDefaultSource)\n'
    '    val cameraDefaultTarget = runCatching { Language.valueOf(cameraDefaultTargetName) }\n'
    '        .getOrDefault(initialCameraDefaultTarget)\n',
)

replace_once(
    '                AppSection.CAMERA -> CameraModeScreen(\n'
    '          defaultSource = defaultSource.language,\n'
    '          defaultTarget = defaultTarget,\n'
    '      )\n',
    '                AppSection.CAMERA -> CameraModeScreen(\n'
    '                    defaultSource = cameraDefaultSource.language,\n'
    '                    defaultTarget = cameraDefaultTarget,\n'
    '                )\n',
)

replace_once(
    '                        defaultSource = defaultSource,\n'
    '                        onTranslation = { settingsPage = SettingsPage.TRANSLATION },\n'
    '                        onModel = { settingsPage = SettingsPage.MODEL },\n',
    '                        defaultSource = defaultSource,\n'
    '                        cameraDefaultSource = cameraDefaultSource,\n'
    '                        cameraDefaultTarget = cameraDefaultTarget,\n'
    '                        onTranslation = { settingsPage = SettingsPage.TRANSLATION },\n'
    '                        onCamera = { settingsPage = SettingsPage.CAMERA },\n'
    '                        onModel = { settingsPage = SettingsPage.MODEL },\n',
)

camera_case = (
    '                    SettingsPage.CAMERA -> CameraSettings(\n'
    '                        defaultSource = cameraDefaultSource,\n'
    '                        defaultTarget = cameraDefaultTarget,\n'
    '                        onBack = { settingsPage = SettingsPage.ROOT },\n'
    '                        onDefaultSourceChanged = { selected ->\n'
    '                            cameraDefaultSourceName = selected.name\n'
    '                            cameraPreferences.edit()\n'
    '                                .putString(PREF_CAMERA_DEFAULT_SOURCE, selected.name)\n'
    '                                .apply()\n'
    '                            if (selected.language == cameraDefaultTarget) {\n'
    '                                cameraDefaultTargetName = Language.entries.first { it != selected.language }.name\n'
    '                                cameraPreferences.edit()\n'
    '                                    .putString(PREF_CAMERA_DEFAULT_TARGET, cameraDefaultTargetName)\n'
    '                                    .apply()\n'
    '                            }\n'
    '                        },\n'
    '                        onDefaultTargetChanged = { selected ->\n'
    '                            cameraDefaultTargetName = selected.name\n'
    '                            cameraPreferences.edit()\n'
    '                                .putString(PREF_CAMERA_DEFAULT_TARGET, selected.name)\n'
    '                                .apply()\n'
    '                            if (cameraDefaultSource.language == selected) {\n'
    '                                cameraDefaultSourceName = SourceLanguageOption.AUTO.name\n'
    '                                cameraPreferences.edit()\n'
    '                                    .putString(PREF_CAMERA_DEFAULT_SOURCE, SourceLanguageOption.AUTO.name)\n'
    '                                    .apply()\n'
    '                            }\n'
    '                        },\n'
    '                    )\n\n'
)
replace_once(
    '                    SettingsPage.MODEL -> ModelSettings(\n',
    camera_case + '                    SettingsPage.MODEL -> ModelSettings(\n',
)

replace_once(
    '    historyCount: Int,\n'
    '    defaultSource: SourceLanguageOption,\n'
    '    onTranslation: () -> Unit,\n'
    '    onModel: () -> Unit,\n',
    '    historyCount: Int,\n'
    '    defaultSource: SourceLanguageOption,\n'
    '    cameraDefaultSource: SourceLanguageOption,\n'
    '    cameraDefaultTarget: Language,\n'
    '    onTranslation: () -> Unit,\n'
    '    onCamera: () -> Unit,\n'
    '    onModel: () -> Unit,\n',
)

translation_row = (
    '        SettingsRow(\n'
    '            icon = { Icon(Icons.Default.Translate, contentDescription = null) },\n'
    '            title = "Перевод",\n'
    '            subtitle = "Язык ввода по умолчанию: ${defaultSource.settingsLabel()}",\n'
    '            onClick = onTranslation,\n'
    '        )\n'
)
camera_row = translation_row + (
    '\n        SettingsRow(\n'
    '            icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },\n'
    '            title = "Камера",\n'
    '            subtitle = "Ввод: ${cameraDefaultSource.settingsLabel()} · Вывод: ${cameraDefaultTarget.displayName}",\n'
    '            onClick = onCamera,\n'
    '        )\n'
)
replace_once(translation_row, camera_row)

marker2 = (
    'private fun SourceLanguageOption.settingsLabel(): String = when (this) {\n'
    '    SourceLanguageOption.AUTO -> "Автоопределение"\n'
    '    else -> displayName\n'
    '}\n'
)
camera_settings = (
    '@Composable\n'
    'private fun CameraSettings(\n'
    '    defaultSource: SourceLanguageOption,\n'
    '    defaultTarget: Language,\n'
    '    onBack: () -> Unit,\n'
    '    onDefaultSourceChanged: (SourceLanguageOption) -> Unit,\n'
    '    onDefaultTargetChanged: (Language) -> Unit,\n'
    ') {\n'
    '    Column(\n'
    '        modifier = Modifier\n'
    '            .fillMaxSize()\n'
    '            .verticalScroll(rememberScrollState())\n'
    '            .padding(20.dp),\n'
    '        verticalArrangement = Arrangement.spacedBy(16.dp),\n'
    '    ) {\n'
    '        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n'
    '            IconButton(onClick = onBack) {\n'
    '                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")\n'
    '            }\n'
    '            Text(\n'
    '                "Камера",\n'
    '                style = MaterialTheme.typography.headlineMedium,\n'
    '                fontWeight = FontWeight.Bold,\n'
    '            )\n'
    '        }\n\n'
    '        Card(\n'
    '            modifier = Modifier.fillMaxWidth(),\n'
    '            shape = RoundedCornerShape(18.dp),\n'
    '            colors = CardDefaults.cardColors(containerColor = Color.White),\n'
    '        ) {\n'
    '            Column(\n'
    '                modifier = Modifier.padding(16.dp),\n'
    '                verticalArrangement = Arrangement.spacedBy(12.dp),\n'
    '            ) {\n'
    '                Text(\n'
    '                    "Язык ввода по умолчанию",\n'
    '                    style = MaterialTheme.typography.titleMedium,\n'
    '                    fontWeight = FontWeight.SemiBold,\n'
    '                )\n'
    '                SourceLanguagePicker(\n'
    '                    source = defaultSource,\n'
    '                    enabled = true,\n'
    '                    onSelected = onDefaultSourceChanged,\n'
    '                    modifier = Modifier.fillMaxWidth(),\n'
    '                )\n'
    '                Text(\n'
    '                    "Доступны: Автоопределение, Русский, English и 中文. Этот язык используется при открытии камеры.",\n'
    '                    style = MaterialTheme.typography.bodyMedium,\n'
    '                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n'
    '                )\n'
    '            }\n'
    '        }\n\n'
    '        Card(\n'
    '            modifier = Modifier.fillMaxWidth(),\n'
    '            shape = RoundedCornerShape(18.dp),\n'
    '            colors = CardDefaults.cardColors(containerColor = Color.White),\n'
    '        ) {\n'
    '            Column(\n'
    '                modifier = Modifier.padding(16.dp),\n'
    '                verticalArrangement = Arrangement.spacedBy(12.dp),\n'
    '            ) {\n'
    '                Text(\n'
    '                    "Язык вывода по умолчанию",\n'
    '                    style = MaterialTheme.typography.titleMedium,\n'
    '                    fontWeight = FontWeight.SemiBold,\n'
    '                )\n'
    '                LanguagePicker(\n'
    '                    language = defaultTarget,\n'
    '                    enabled = true,\n'
    '                    onSelected = onDefaultTargetChanged,\n'
    '                    modifier = Modifier.fillMaxWidth(),\n'
    '                )\n'
    '                Text(\n'
    '                    "Доступны: Русский, English и 中文. Этот язык используется как язык результата в режиме камеры.",\n'
    '                    style = MaterialTheme.typography.bodyMedium,\n'
    '                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n'
    '                )\n'
    '            }\n'
    '        }\n'
    '    }\n'
    '}\n\n'
)
replace_once(marker2, camera_settings + marker2)
path.write_text(text)

camera = Path('app/src/main/java/com/ling20/translator/CameraModeScreen.kt')
ctext = camera.read_text()
old = (
    '    var sourceName by rememberSaveable {\n'
    '        mutableStateOf(\n'
    '            if (defaultSource == defaultTarget) CameraSourceLanguage.AUTO.name\n'
    '            else CameraSourceLanguage.from(defaultSource).name,\n'
    '        )\n'
    '    }\n'
    '    var targetName by rememberSaveable { mutableStateOf(defaultTarget.name) }\n'
)
new = (
    '    var sourceName by rememberSaveable(defaultSource, defaultTarget) {\n'
    '        mutableStateOf(\n'
    '            if (defaultSource == defaultTarget) CameraSourceLanguage.AUTO.name\n'
    '            else CameraSourceLanguage.from(defaultSource).name,\n'
    '        )\n'
    '    }\n'
    '    var targetName by rememberSaveable(defaultTarget) { mutableStateOf(defaultTarget.name) }\n'
)
if old not in ctext:
    raise SystemExit('CameraModeScreen defaults block not found')
camera.write_text(ctext.replace(old, new, 1))

gradle = Path('app/build.gradle.kts')
gtext = gradle.read_text()
if 'versionCode = 13' not in gtext or 'versionName = "0.1.12"' not in gtext:
    raise SystemExit('Unexpected current version')
gtext = gtext.replace('versionCode = 13', 'versionCode = 14', 1)
gtext = gtext.replace('versionName = "0.1.12"', 'versionName = "0.1.13"', 1)
gradle.write_text(gtext)
