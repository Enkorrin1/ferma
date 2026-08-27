# Mobile Poster Hub

Минимальный серверный контур для Android Phone Poster Agent. Он хранит устройства и задания в SQLite, безопасно выдаёт очередное доступное задание одному устройству и принимает статусы, события и PNG-скриншоты.

## Запуск

```powershell
cd source/mobile_poster_hub
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
# Задайте в .env два разных случайных токена длиной не менее 24 символов.
Get-Content .env | ForEach-Object { if ($_ -match '^(HUB_[A-Z_]+)=(.*)$') { Set-Item -Path "Env:$($matches[1])" -Value $matches[2] } }
uvicorn app:app --host 0.0.0.0 --port 8080
```

Для телефона hub должен быть доступен по публичному HTTPS URL. В приложение передаётся URL без завершающего `/` и значение `HUB_RUNNER_TOKEN`. Административные запросы используют `HUB_ADMIN_TOKEN` в заголовке `X-Hub-Token`.

## Создание задания

```powershell
$headers = @{ "X-Hub-Token" = $env:HUB_ADMIN_TOKEN }
$body = @{ target = "tiktok_post"; caption = "Тест"; media_url = "https://example.com/video.mp4" } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/jobs -Method Post -Headers $headers -ContentType application/json -Body $body
```

Интерактивная спецификация API доступна по `/docs`.

Администратор может наблюдать работу через `GET /devices`, `GET /jobs` и `GET /jobs/{job_id}` с `X-Hub-Token: <HUB_ADMIN_TOKEN>`. Последний endpoint возвращает статус, события и защищённые пути загруженных скриншотов; их скачивание тоже требует admin-токен.

## HTTPS-развёртывание на сервере

Укажите в `.env` публичный домен `HUB_DOMAIN`, направьте его DNS A/AAAA-запись на сервер и откройте входящие порты 80 и 443. Затем запустите:

```powershell
docker compose up -d --build
```

Caddy автоматически выпустит и продлит TLS-сертификат, а hub сохранит SQLite-данные и скриншоты в Docker volume. Проверьте `https://<HUB_DOMAIN>/health`, прежде чем указывать этот URL в Android-агенте.
