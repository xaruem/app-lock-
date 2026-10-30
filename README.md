# sim-menu v2

## Первая сборка (один раз): ключ подписи
1. Репозиторий должен быть ПРИВАТНЫМ.
2. GitHub -> Actions -> "Create keystore (run once)" -> Run workflow.
3. Скачай артефакт keystore-secrets, открой secrets.txt.
4. Settings -> Secrets and variables -> Actions -> New repository secret. Создай 4 секрета
   с именами и значениями из файла: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
5. Сохрани secrets.txt в надёжном месте (без ключа обновить приложение нельзя), удали артефакт в Actions.

## Сборка APK
Actions -> Build APK -> Run workflow -> артефакт sim-menu-apk -> sim-menu-release.apk.
Каждая следующая версия, собранная с теми же секретами, ставится поверх старой, сейф сохраняется.
