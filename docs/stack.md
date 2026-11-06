# Закреплённый стек

Версии закреплены в Gradle и npm manifests/lockfiles.

| Компонент | Версия |
|---|---|
| JDK / bytecode | 25 LTS (проверено 25.0.5.0.3) |
| Spring Boot | 4.1.2 |
| Spring Framework / Security | 7.0.10 / 7.1.2 |
| Kotlin plugins, stdlib и reflect | 2.4.21 |
| Gradle Wrapper | 9.7.0 (full-support KGP range; latest 9.8.2 вне этого диапазона) |
| Jackson / JUnit / Testcontainers | 3.1.7 / 6.0.3 / 2.0.5 по Boot BOM |
| JaCoCo / AWS SDK BOM | 0.8.16 / 2.57.9 |
| Sonar Gradle plugin | 7.5.0.8588 |
| Dependency management plugin | 1.1.7 |
| PostgreSQL / SeaweedFS image | 17.6-alpine / 4.52; volumes без миграции |
| Node / npm | 26.13.0 LTS / 12.2.2 |

Kotlin явно согласован выше версии Boot BOM; остальные Spring/Jackson/Data/JUnit/driver версии управляются Boot. Dependency verification временно выключена (backup .xml.disabled); locks и Wrapper SHA-256 сохранены.

Источники: [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Boot 4 migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [Node releases](https://nodejs.org/en/about/previous-releases), [npm metadata](https://registry.npmjs.org/npm/latest).

## Frontend

Node.js 26 LTS закреплён в .nvmrc. React 19.3.1, React Router 7.18.5, Vite 8.3.6 и TypeScript 6.0.3; точные версии инструментов находятся в frontend/package.json, разрешённое дерево и integrity — в корневом package-lock.json. TypeScript 6.0 выбран по peer range typescript-eslint; Node 25 не входит в engine range Vitest 5. Внешних runtime CDN нет.

Документация: [React](https://react.dev/learn), [Vite](https://vite.dev/guide/), [Vitest](https://vitest.dev/guide/), [Spring CSRF SPA integration](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html). Форматирование — Prettier; проверки поведения — React Testing Library и Playwright 1.64.1. Версии и сценарии воспроизводит root check.

Playwright 1.65.0 не выбран: registry публикует @playwright/test, но playwright-core@1.65.0 недоступен (ETARGET). До полного релиза package/image остаются 1.64.1. Browser test image использует Node 26.13.0/npm 12.2.2 поверх официального Playwright image.

minimatch закреплён override 11.0.1 для GHSA-g3hv-75wg-m249. Проверяются все ESLint config patterns через настоящий lint, TS/build/Vitest и browser e2e; это dev/build dependency, не runtime React bundle. TS 6.0.3 остаётся из-за peer range typescript-eslint <6.1, хотя TS 7.0.2 stable новее. npm install-scripts для optional fsevents явно запрещён; Vite использует доступный fallback watcher.
