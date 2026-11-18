# Переносимость и рефакторинг 0.15.0

База: 0.14.0, main `706c1ea`. Плагин остаётся одним Gradle-модулем; Java records и компактные парсеры не переписываются ради языка.

## Границы

- `OperationConfig` — полный неизменяемый снимок исполнения. `OperationKeys` — отдельные ключи результата страницы, Sources и индекса. `SettingsImpact` использует те же ключи. Имя подключения и Web URL не запускают повторный индекс при пагинации; mappings не инвалидируют Sources.
- `ConnectionUpdates` планирует credentials и проверяет ожидаемую версию настроек. Commit выполняется внутри защищённой транзакции `CredentialUpdate`: конфликт после записи OS Password Safe восстанавливает затронутые ключи. `ConnectionManager` сериализует фоновые изменения из Settings и Explorer без удержания монитора persistent state во время блокирующего OS I/O. Результат сохраняется в фоне независимо от callback закрытого UI.
- `StreamCatalog` владеет загрузкой Streams по подключениям, отменой и поздними ответами. `ExplorerCoordinator` связывает отправленное окно поиска с Sources и индексом и применяет избирательную политику Settings. Оба не зависят от IntelliJ/Swing.
- `LogEvent` защищает JSON и LogFormat копиями; разобранная цепочка исключения вычисляется один раз и переиспользуется. Структурированный GELF/Yii JSON, ECS error object и текстовые стеки — отдельные адаптеры общей модели. Текстовый стек не заменяется нормализованным render, поэтому native/omitted-фреймы остаются в инспекторе.
- Settings привязывают native ColorPanel напрямую: скрытые текстовые поля и мост синхронизации цветов удалены.
- UI-сценарии находят именованные компоненты и используют действия/табличную модель. Бизнес-правила проверяются без IDE. Приватные поля production UI не читаются через reflection. Независимый reload-controller обращается к публичному снимку состояния через границу classloader; platform-specific dropdown inspection и bootstrap Password Safe в fixture остаются особыми интеграционными механизмами.

## Парсеры

Синтетические примеры и ожидаемые фреймы: `src/test/resources/stacks/`. Покрыты PHP, Java, Kotlin, Python cause chains, V8 JS/TS, Go panic, Ruby, .NET и Rust panic/backtrace; отдельно — ECS, file URLs, native/unknown source, виртуальные URL, нулевые/переполненные номера строк, изменение входного JSON после публикации и выход пути за корень.

JVM-фреймы дают package-relative путь относительно настроенного source root. Не выполняется неоднозначный поиск по basename. Колонки, source maps, PSI-разрешение JVM-классов, Python exception groups и реконструкция `... N more` не реализованы. Языковые парсеры не предоставляют debugger values: эта возможность остаётся отдельным PHP `VariableHintProvider` с прежними консервативными правилами.

Форматы сверены с первичными источниками: [Python traceback](https://docs.python.org/3/library/traceback.html), [V8 stack traces](https://v8.dev/docs/stack-trace-api), [.NET Exception.StackTrace](https://learn.microsoft.com/en-us/dotnet/api/system.exception.stacktrace), [Rust panic/backtrace](https://doc.rust-lang.org/stable/book/ch09-01-unrecoverable-errors-with-panic.html). Примеры написаны для тестов, реальные логи или персональные данные не включены.

## Проверки

На локальном PhpStorm `263.7307.215`:

- `check`: 725 контрактных проверок. Новые сценарии проверяют языковые fixtures, неизменяемость/переиспользование цепочки, разделение ключей операций, пагинацию после переименования, отмену Streams, координацию настроек и откат credentials при конфликте. Отдельно проверены сохранение своей версии Apply при последующей конкурентной записи и безопасное сообщение об ошибке Password Safe.
- Отдельные loopback HTTP-тесты проводят все девять языковых примеров через CSV export. ECS проверяется как native error object с восстановлением legacy Map.toString через JSON и как плоские dotted-поля `error.*`; результат полного индекса сохраняет позиции и типы исходного JSON.
- `buildPlugin`, `verifyPluginStructure`, `verifyPlugin`: успешны; Plugin Verifier — Compatible.
- Полные UI-сценарии светлой и тёмной тем прошли, по 39 проверок Settings. Проверены исходные PHP values/инвалидация source proof, поиск, пагинация, Sources, история, mappings, нативные действия, цветовой контрол и очистка промежуточного credential key. Снимки Settings и Explorer просмотрены визуально.
- ZIP `0.15.0`: одна библиотека, 187 классов, без повторов, Kotlin stdlib и smoke/reload fixtures. SHA256: `de2518dbe4f9332cda8f99cdd352eab2c05193cbf87efd81aee9a60e4d485341`.
- Независимый reload-controller выполнил два обновления production-плагина в той же JVM. Оба прежних classloader собраны после явной очистки platform icon cache; проверены окно, Live, popup Sources, индекс, PHP hints, действия и сохранённый запрос. Очистка кеша — условие теста, а не гарантия немедленного GC в каждой IDE.

Production-код: 57 файлов / 4794 строки → 69 файлов / 5093 строки (+6,2%). Panel: 643 → 630 строк; его роль UI-координатора сохранена, а владение Streams и правила между операциями вынесены. Увеличение общего объёма — явные границы форматов/ключей/транзакции, а не механическая миграция всех Java-классов.

При проверках типов marker/inlay владения остаётся узкая package-scoped диагностика `ExceptionHighlights`; это специализированные интеграционные проверки самого механизма. Пользовательские сценарии не читают приватные поля UI. Языковые парсеры проверяются контрактными fixtures; полные UI-прогоны используют PHP fixture и не подменяют проверку всех IDE или языковых PSI.

Локальные UI-отчёты создаются в `build/reports/smoke/{light,dark,reload}`; в основном checkout результаты этой версии копируются в `build/reports/portability-0.15.0`. CI проверяет закреплённые PhpStorm/IDEA SDK. UI CI на чистом EAP SDK требует авторизованного JetBrains Account login и в эту проверку не включён.
