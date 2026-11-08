# Локальная разработка

Нужны JDK 25 LTS, Node.js 26 LTS (точная версия в .nvmrc), npm и работающий Docker. Из корня проекта:

```sh
docker compose up -d postgres s3
./gradlew :backend:generateFileKey
export FILE_ENCRYPTION_KEYSET="$PWD/.secrets/files.tink-keyset"
SPRING_PROFILES_ACTIVE=local ./gradlew :backend:bootRun
```

Команда генерации создаёт файл `.secrets/files.tink-keyset` с правами `0600` на POSIX. Выполните её один раз. После рестарта приложению нужен прежний ключ. Для запуска в другой среде передайте путь к тому же набору ключей через файл секрета. Резервную копию ключа храните отдельно от копии базы и бакета.

Для запуска из IDE открыть проект как Gradle project, выбрать JDK 25 и main-класс `dev.secureshare.SecureShareApplicationKt` с classpath модуля `backend.main`. В Spring Boot configuration указать Active profiles: `local`; для обычной Application configuration — VM option `-Dspring.profiles.active=local`. В Run Configuration задайте `FILE_ENCRYPTION_KEYSET` с абсолютным путём к созданному файлу. Настройки DB/S3 берутся из профиля `local`. React dev server запускается отдельно командой `npm run dev`. Задачи `:backend:classes` и `:backend:bootRun` не требуют npm; React собирается и включается только в `bootJar`. Для полного приложения на порту 8080 выполнить `./gradlew bootJar`, затем `SPRING_PROFILES_ACTIVE=local java -jar backend/build/libs/backend-0.1.0.jar`.

Локальные defaults находятся в `backend/src/main/resources/application-local.yaml` и совпадают с Compose:

| Параметр | Значение |
|---|---|
| JDBC | `jdbc:postgresql://localhost:55432/secureshare` |
| DB user | `secureshare` |
| DB password | `secureshare-local-password` |
| S3 endpoint / bucket | `http://localhost:8333` / `secureshare` |
| S3 access key | `secureshare-local` |
| S3 secret key | `secureshare-local-secret` |

Эти известные значения предназначены для local-профиля на loopback. Базовый application.yaml требует внешние DB credentials. Переменные окружения переопределяют local defaults. `.env` необязателен: Compose читает его автоматически, а IDE не читает без дополнительной настройки. При собственных overrides нужно задать одинаковые значения в Compose и IDE; пример — `.env.example`. `.env` исключён из Git.

Изменение POSTGRES_PASSWORD не меняет пароль в уже существующем PostgreSQL volume: для сохранения данных нужно изменить пароль роли SQL-командой. Volumes удалять не требуется.

После запуска Vite открыть [регистрацию](http://127.0.0.1:5173/register), создать аккаунт, войти и перейти в «Мои файлы». На /files доступны загрузка, список и скачивание; максимум файла — 10 MiB, для загрузки нужен JavaScript. Приложение и PostgreSQL слушают loopback. После проверки приложение останавливается Ctrl+C, БД и S3 — `docker compose stop postgres s3`; volumes сохраняются. S3 endpoint: http://localhost:8333, наружу опубликован только loopback port. Административные порты хранилища не публикуются.

По умолчанию cookie Secure включён. local — явное исключение для локального HTTP. Для HTTPS задать SERVER_SSL_ENABLED, SERVER_SSL_KEY_STORE и SERVER_SSL_KEY_STORE_PASSWORD через окружение; секреты и сертификаты не помещать в Git. Готового публичного deployment в этом change нет.

## Разработка интерфейса

```sh
nvm install
nvm use
npm install --global npm@12.2.2
npm ci
npm run dev
```

Если nvm не установлен, выбрать Node 26.13+ другим менеджером. npm 12.2.2 закреплён в packageManager, Node — в .nvmrc; Node 25 не поддерживается.

Vite доступен на http://127.0.0.1:5173 и проксирует /api на http://127.0.0.1:8080. Бэкенд запустить командой выше. API_PROXY_TARGET меняет адрес proxy. CORS на сервере не расширяется; все запросы браузера идут на один origin.

React: api/ — типизированный клиент и CSRF, auth/ — сессия и формы, files/ — сценарии файлов, ui/ — общие элементы. Нет хранения credentials в localStorage; после login/logout клиент обновляет CSRF token. Поиск и сортировка выполняются по списку, полученному из API. Общие принципы и адаптивность — [frontend architecture](frontend.md).

## Проверки

```sh
./gradlew clean check bootJar
```

Root check включает backend tests/coverage, frontend typecheck, ESLint, Prettier и Vitest.

Тесты сами создают PostgreSQL и SeaweedFS Testcontainers, проверяют HTTP и HTTPS и не используют рабочую БД. Отсутствие Docker приводит к ошибке, а не пропуску suite. На macOS при отсутствии системного Docker socket может потребоваться:

```sh
export DOCKER_HOST="unix://${HOME}/.docker/run/docker.sock"
```

JaCoCo проверяет минимум 80% строк и 80% ветвей без исключения security-кода. Отчёты: backend/build/reports/tests/test/index.html и backend/build/reports/jacoco/test/html/index.html. Boot jar: backend/build/libs/backend-0.1.0.jar.

Версии зависимостей фиксирует `backend/gradle.lockfile`. Проверка контрольных сумм зависимостей Gradle **временно отключена во всём проекте**, включая CI. Metadata сохранена как `gradle/verification-metadata.xml.disabled`, поэтому Gradle её не применяет. Переключатель localBuild и специальные init scripts удалены. Это устраняет IDE-отказы при загрузке Kotlin source artifacts с отсутствующими суммами.

Для возврата проверки переименовать backup в `gradle/verification-metadata.xml` и актуализировать суммы новых artifacts. SHA-256 Gradle Wrapper и содержимого загруженных пользователем файлов продолжают проверяться. После изменения настроек выполнить Reload All Gradle Projects в IDE.

Сквозная browser-проверка с отдельными временными PostgreSQL/S3, собранным jar и Chromium в Docker:

```sh
./gradlew bootJar
python3 scripts/browser-check.py
```

Нужны Python 3, curl с поддержкой AWS Signature V4 и выбранный JDK 25 в JAVA_HOME/PATH. Скрипт удаляет свои контейнеры и останавливает приложение; рабочие volumes не используются. Скриншоты находятся в docs/screenshots, результаты Playwright — в frontend/test-results. Подробнее — [архитектура frontend](frontend.md).

Локальный SonarQube поддерживается Gradle-плагином. Передать SONAR_HOST_URL и SONAR_TOKEN через окружение и выполнить `./gradlew clean check bootJar sonar`. Токен не указывать в командной строке и не сохранять в проекте.
