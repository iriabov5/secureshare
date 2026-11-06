# Логи backend

Backend использует SLF4J и управляемый Spring Boot Logback. В local-профиле — консольные строки key=value, в остальных — встроенный JSON/ECS. Глобальный DEBUG Spring Security/Hibernate/AWS не нужен: он может раскрыть входные данные. Файлового appender и внешнего сервиса логов нет.

## Как найти запрос

Любой запрос, дошедший до servlet filters, получает серверный UUID в response header X-Request-ID. Посмотреть его можно в DevTools → Network → Response headers. Затем найти этот ID в консоли IDE. Для перенаправленного в файл stdout:

```sh
rg 'нужный-request-id' backend.log
```

Один ID связывает operation events и http.request.completed. Клиентский X-Request-ID игнорируется, session ID в логах нет. TLS/HTTP parsing errors до servlet pipeline не обязаны иметь application requestId. GET assets/favicon/csrf completion находятся на DEBUG; операции API — на штатных INFO/WARN/ERROR.

Для диагностики начала запроса достаточно точечно задать LOGGING_LEVEL_DEV_SECURESHARE_OPERATIONS=DEBUG. Это не включает HTTP bodies, SDK wire logging или SQL bindings. Настройка действует только на logger приложения.

## События

| Event | Уровень и значение |
|---|---|
| http.request.started | DEBUG: запрос вошёл в фильтр; это ещё не результат |
| http.request.completed | INFO 2xx/3xx; WARN ожидаемые 4xx; ERROR 5xx. method, безопасный route, status, durationMs, outcome |
| account.registration.succeeded / rejected | INFO после commit / WARN validation или conflict без введённого login |
| auth.login.succeeded / rejected | INFO подтверждённый вход / WARN общий INVALID_CREDENTIALS |
| auth.logout.succeeded | INFO штатный logout; не означает истечение сессии |
| security.request.rejected | WARN: UNAUTHENTICATED, FORBIDDEN, MISSING_OR_INVALID_CSRF |
| file.upload.started | INFO: создана UPLOADING metadata; файл ещё не сохранён |
| file.upload.succeeded | INFO только после подтверждённых PUT и READY commit |
| file.list.succeeded | INFO: resultCount готовых собственных файлов |
| file.download.succeeded | INFO: сервер закончил выдачу response без ошибки; не доказывает сохранение браузером |
| file.upload.rejected / file.download.rejected | WARN: INVALID_FILE / FILE_TOO_LARGE; содержимое и имя не выводятся |
| operation.failed | ERROR: operation, dependency, stage, reasonCode, error.type/rootCauseType и безопасные stackFrames |

HTTP completion и итог прикладной операции — разные записи. Один operation failure не печатается в каждом catch. Обычный validation/security отказ не содержит stack trace. Непредвиденная ошибка в servlet chain перехватывается после безопасной диагностики: 500 INTERNAL_ERROR, без передачи исходного Throwable в контейнерный logger. При committed response status изменить уже нельзя; outcome остаётся ERROR/CLIENT_DISCONNECTED, а не SUCCESS.

## Как понимать отказ

- dependency=POSTGRES: USER_LOOKUP, REGISTRATION_COMMIT, METADATA_CREATE/LIST/LOAD/READY.
- dependency=S3: S3_PUT, S3_GET, FILE_INTEGRITY.
- reasonCode=DEPENDENCY_TIMEOUT: превышено время ожидания.
- DEPENDENCY_CONNECTION_FAILED: отказ соединения или SQL state класса 08.
- DEPENDENCY_AUTHENTICATION_FAILED: SQL state класса 28; проверить настройки доступа БД.
- DATA_CONSTRAINT_FAILED: SQL state класса 23; значения и сырой SQL не печатаются.
- STORAGE_ACCESS_DENIED / STORAGE_OBJECT_MISSING: S3 вернул 403/404.
- FILE_INTEGRITY_MISMATCH: размер или SHA-256 объекта не соответствует metadata; сам hash и байты не выводятся.
- COMMIT_UNCONFIRMED или outcome=UNKNOWN: результат сохранения не подтверждён; это не повод автоматически повторять upload.
- cleanupOutcome=FAILED: не удалось сохранить служебный FAILED status; исходная ошибка сохранена.

Например:

```text
INFO requestId=<uuid> event="file.upload.started" fileId="<file-uuid>" sizeBytes="512"
ERROR requestId=<uuid> event="operation.failed" outcome="UNKNOWN" operation="FILE_UPLOAD" stage="S3_PUT" reasonCode="DEPENDENCY_TIMEOUT" dependency="S3"
ERROR requestId=<uuid> event="http.request.completed" outcome="ERROR" method="POST" route="/api/v1/files" status="503" durationMs="5000"
```

При таком исходе сначала проверить список файлов: PUT мог выполниться до потери ответа. Между PostgreSQL и S3 по-прежнему нет общей транзакции.

## Какие данные исключены

Пароли/hash, cookie/session/CSRF/Authorization, bodies, raw username, filenames/bytes/hash файлов, query/raw path values, IP/user-agent, credentials/JDBC/S3 URLs, SQL/bind values и непроверенные exception messages. В приложение попадают только безопасные enums, шаблон маршрута, серверные UUID и числовые значения. Raw SDK/SQL Throwable не передаётся logger: сохраняются классы причин и stack frames без messages. Шумные Hibernate SQL/error и Flyway INFO loggers отключены; источником диагностики становятся нормализованные события приложения.

Логи помогают расследовать операции, но не являются неизменяемым аудитом или подтверждением доставки файла на диск пользователя. Retention, централизованный сбор и distributed tracing не добавлены.
