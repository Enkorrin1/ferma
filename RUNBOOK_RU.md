# Запуск «Фермы»

## 1. Запустите hub

```powershell
cd source/mobile_poster_hub
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
```

Задайте в `.env` два разных случайных значения длиной не менее 24 символов: `HUB_RUNNER_TOKEN` и `HUB_ADMIN_TOKEN`. Затем загрузите их в окружение и запустите сервер:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^(HUB_[A-Z_]+)=(.*)$') { Set-Item -Path "Env:$($matches[1])" -Value $matches[2] }
}
uvicorn app:app --host 0.0.0.0 --port 8080
```

Для настоящего телефона hub должен быть доступен по HTTPS-адресу. В приложение передаётся этот адрес без завершающего `/` и значение `HUB_RUNNER_TOKEN`. Не используйте admin-токен на телефоне.

Для развёртывания на сервере задайте в `.env` `HUB_DOMAIN`, направьте DNS на сервер и откройте порты 80/443. Затем в `source/mobile_poster_hub` выполните `docker compose up -d --build`. Caddy выпустит TLS-сертификат автоматически.

## 2. Соберите Android-агент

Требуются JDK 17 и Android SDK с платформой/build tools API 34.

```powershell
cd source/android_agent_app
.\gradlew.bat assembleDebug
```

Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`.

Если Gradle Wrapper не может скачать дистрибутив из-за корпоративной сети, используйте предварительно скачанный Gradle 8.7 либо настройте сетевой прокси для Java. В проекте намеренно включён `android.overridePathCheck=true`, потому что его путь содержит кириллические символы.

## 3. Подготовьте телефон

1. Установите debug APK.
2. Введите HTTPS URL hub, runner token и метку устройства.
3. Включите Accessibility Service и уведомления.
4. Нажмите **Start agent**.

Агент зарегистрирует устройство, будет опрашивать очередь раз в 20 секунд и передавать `X-Device-Id` вместе с runner-токеном. Hub выдаёт задание только устройству, совпадающему с заданными `preferred_device_id` и `account_label`; статусы, события и скриншоты принимаются только от назначенного устройства.

## 4. Создайте тестовое задание

```powershell
$headers = @{ "X-Hub-Token" = $env:HUB_ADMIN_TOKEN }
$body = @{
  target = "tiktok_post"
  caption = "Тест публикации"
  media_url = "https://example.com/video.mp4"
  preferred_device_id = "android-agent-..."
} | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/jobs -Method Post -Headers $headers -ContentType application/json -Body $body
```

Проверьте состояние через `GET /devices`, `GET /jobs` или `GET /jobs/{job_id}` с admin-токеном. Последний endpoint возвращает итоговый статус, журнал событий и пути скриншотов. Интерактивная спецификация доступна по `http://127.0.0.1:8080/docs`.

## Проверки

```powershell
cd source/mobile_poster_hub
.\.venv\Scripts\python -m unittest discover -s tests -v
```

На текущем рабочем окружении hub-тесты проходят, а `assembleDebug` завершается успешно. Для финальной проверки публикации требуется физический Android-телефон с установленной целевой соцсетью и публичным HTTPS URL hub.
