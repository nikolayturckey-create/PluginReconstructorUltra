# Plugin Reconstructor Ultra

**Plugin Reconstructor Ultra** — настольный Java-инструмент для извлечения максимально полного Java-исходника из JVM JAR, включая Minecraft-плагины и обфусцированные архивы.

Текущая версия: **0.2.0** · Требуется **Java 21+** · Лицензия: **MIT**

> Инструмент не обещает математически невозможное восстановление проекта автора 1-в-1. Он пытается получить нормальный `.java` для каждого source unit, сохраняет все физические `.class` и явно сообщает о том, что восстановить не удалось.

## Возможности

- простой GUI: выбрать или перетащить JAR → нажать **«Извлечь Java-исходники»**;
- автоматический запуск **Vineflower**, **CFR** и **Procyon**;
- сравнение результатов декомпиляторов и выбор лучшего Java-кода;
- повторная обработка пропущенных классов;
- сохранение каждого исходного `.class` байт-в-байт;
- анализ JVM bytecode и fallback-представления для проблемных классов;
- определение признаков обфускации;
- поддержка обычных JVM JAR и Minecraft-плагинов;
- извлечение `plugin.yml`, `paper-plugin.yml`, конфигов и остальных ресурсов;
- сохранение вложенных JAR, подписей, duplicate entries и multi-release entries;
- создание обычной структуры Gradle-проекта, которую можно открыть в IntelliJ IDEA;
- HTML/JSON/CSV-отчёты о восстановлении.

## Быстрый запуск

### Windows

1. Установите Java 21 или новее.
2. Запустите `START_WINDOWS.bat` или `PluginReconstructorUltra-0.2.0.jar`.
3. Перетащите JAR в окно.
4. Нажмите **«Извлечь Java-исходники»**.
5. Откройте созданную папку `Recovered_<имя JAR>`.

### Linux / macOS

```bash
chmod +x START_LINUX_MAC.sh
./START_LINUX_MAC.sh
```

При первом использовании программа может скачать декомпиляторы в пользовательский кэш.

## CLI

```bash
java -jar PluginReconstructorUltra-0.2.0.jar MyPlugin.jar
java -jar PluginReconstructorUltra-0.2.0.jar MyPlugin.jar -o RecoveredPlugin
java -jar PluginReconstructorUltra-0.2.0.jar setup-tools
java -jar PluginReconstructorUltra-0.2.0.jar --help
```

Поддерживаемые режимы движков: `auto`, `vineflower`, `cfr`, `procyon`, `both`, `all`, `none`.

## Результат

```text
Recovered_MyJar/
├── src/main/java/                  выбранные Java-исходники
├── src/main/resources/             ресурсы исходного JAR
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── HOW_TO_BUILD.md
├── MISSING_SOURCES.txt
├── recovery/
│   ├── original/                   исходный JAR
│   ├── original-classes/           все физические .class
│   ├── decompiler-candidates/      варианты декомпиляторов
│   ├── bytecode/
│   ├── pseudo-source/
│   ├── duplicate-entries/
│   ├── nested-jars/
│   ├── signatures/
│   └── unparsed-classes/
└── reports/
    ├── report.html
    ├── report.json
    ├── sources.csv
    ├── missing-sources.csv
    └── logs/
```

## Что означает «без потери классов»

Если `.class` физически существует в JAR, программа сохраняет его в `recovery/original-classes/`, даже когда получить корректный Java-код не удалось. Отсутствующий source unit попадает в `MISSING_SOURCES.txt` вместо того, чтобы молча исчезнуть.

Таким образом, **сохранность исходных class-файлов** и **успешное восстановление компилируемого Java** — это разные показатели.

## Обфускация

Plugin Reconstructor Ultra пытается обработать JAR с переименованными символами, усложнённым control flow и другими преобразованиями, используя несколько декомпиляторов и собственный анализ class-файлов.

Однако компиляция/обфускация может необратимо уничтожить:

- комментарии;
- исходное форматирование;
- оригинальные имена локальных переменных;
- часть generic/debug metadata;
- точную первоначальную структуру исходного проекта.

Код, которого физически нет в JAR, также невозможно извлечь из этого JAR.

## Сборка восстановленного проекта

Сам Plugin Reconstructor Ultra **не обязан собирать восстановленный проект**. Его задача — выдать структуру и Java-файлы для дальнейшей работы.

Откройте результат в IntelliJ IDEA, укажите нужные версии внешних API в `gradle.properties`, исправьте пункты из `MISSING_SOURCES.txt` и собирайте проект отдельно.

## Сборка Plugin Reconstructor Ultra

Linux/macOS:

```bash
./scripts/build.sh
```

Windows:

```bat
scripts\build.bat
```

Для сборки требуется JDK 21.

## Безопасное и разрешённое использование

Используйте инструмент для собственных JAR, восстановления потерянных исходников, совместимости, обучения и аудита с разрешением владельца. Проект не предназначен для обхода лицензий, активаций, внешних ключей или удалённого контроля доступа.

## Third-party software

Инструмент интегрируется с внешними декомпиляторами. Подробности и их лицензии/ссылки смотрите в [`THIRD_PARTY.md`](THIRD_PARTY.md).

## License

Plugin Reconstructor Ultra распространяется по лицензии [MIT](LICENSE).
