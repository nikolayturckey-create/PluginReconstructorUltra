# Архитектура 0.2.0

## Pipeline

```text
Input JAR
 ├─ безопасная инвентаризация и SHA-256
 ├─ сохранение исходного JAR
 ├─ сохранение каждого физического .class
 ├─ чтение plugin.yml / paper-plugin.yml / bungee.yml / velocity-plugin.json
 ├─ собственный JVM parser
 │   ├─ constant pool
 │   ├─ поля, методы и attributes
 │   ├─ Code + exception table
 │   └─ disassembly
 ├─ анализ зависимостей и обфускации
 ├─ Vineflower + CFR + Procyon
 ├─ синтаксическая оценка кандидатов
 ├─ выбор лучшего Java-файла
 ├─ coverage: .class groups → Java source units
 ├─ fallback для пропусков
 ├─ Java-only Gradle project
 └─ HTML / JSON / CSV reports
```

## Отсутствие молчаливых потерь

`recovery/original-classes` содержит отдельную копию каждой физической class-записи. Точные дубликаты и пути, конфликтующие на case-insensitive файловых системах, получают безопасный путь `__duplicates__/...`.

Один Java-файл может представлять несколько JVM-классов: например, `Outer.class`, `Outer$Inner.class` и анонимные классы обычно восстанавливаются в `Outer.java`. Поэтому отчёт разделяет:

- количество физических `.class`;
- количество ожидаемых Java source units;
- количество восстановленных source units;
- количество физических классов, представленных этими исходниками.

Если обычный Java отсутствует, класс всё равно остаётся в:

- `recovery/original-classes`;
- `recovery/bytecode`;
- `recovery/pseudo-source` или `recovery/unparsed-classes`;
- `MISSING_SOURCES.txt` и `missing-sources.csv`.

## Выбор исходника

Каждый кандидат получает оценку по структуре, размеру, наличию типа/package, балансу скобок, маркерам ошибок декомпиляции и результату парсинга через JDK compiler API. Ненулевой exit code движка не уничтожает уже созданные пригодные `.java`.

## Граница

Версия 0.2.0 выполняет статическое восстановление предоставленного JAR. Она не запускает неизвестный плагин, не перехватывает runtime-классы, не обходит лицензии/активации и не получает код с удалённых серверов.
