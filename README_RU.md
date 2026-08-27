# Android Phone Poster Agent

Это чистый пакет Android-приложения, которое ставится на телефон и само выполняет выкладку через AccessibilityService.

Это не генератор видео и не сервис подготовки контента. Агент получает уже готовую задачу с `media_url`, скачивает картинку или видео на телефон, открывает нужное приложение и нажимает кнопки публикации.

## Готовый APK

```text
apk/phone-poster-agent-video-image-v0.1.35-debug.apk
```

## Что поддерживает этот APK

Targets:

- `instagram_reel`
- `tiktok_post`
- `pinterest_pin`

Media:

- картинки: `jpg`, `jpeg`, `png`, `webp`
- видео: `mp4`, `mov`, `webm`, `mkv`, `3gp`

Картинки сохраняются в:

```text
Pictures/MobilePosterAgent
```

Видео сохраняются в:

```text
Movies/MobilePosterAgent
```

## Где главный код

```text
source/android_agent_app/app/src/main/java/com/elevium/mobileposteragent/service/AgentForegroundService.kt
source/android_agent_app/app/src/main/java/com/elevium/mobileposteragent/service/AgentAccessibilityService.kt
source/android_agent_app/app/src/main/java/com/elevium/mobileposteragent/service/MediaPreparer.kt
source/android_agent_app/app/src/main/java/com/elevium/mobileposteragent/data/HubApi.kt
```

## Как работает

1. Пользователь ставит APK на Android.
2. В приложении вводит `hub url`, `runner token`, `device label`, `account label`.
3. Включает Accessibility Service для `Mobile Poster Agent`.
4. Нажимает `Start agent`.
5. Агент работает foreground service.
6. Агент периодически делает `claim-next` в hub.
7. Если есть задача, агент скачивает `media_url`.
8. Агент открывает Instagram/TikTok/Pinterest.
9. Агент нажимает кнопки через Accessibility.
10. Агент отправляет status/events обратно в hub.

## Пример задачи

```json
{
  "target": "tiktok_post",
  "publish_at": "2026-05-04T12:00:00Z",
  "caption": "Test video caption",
  "media_url": "https://example.com/video.mp4",
  "preferred_device_id": "android-agent-phone-1",
  "account_label": "main_account"
}
```

Для Instagram:

```json
{
  "target": "instagram_reel",
  "caption": "Test reel caption",
  "media_url": "https://example.com/reel.mp4"
}
```

Для Pinterest:

```json
{
  "target": "pinterest_pin",
  "title": "Test Pin",
  "description": "Test pin description",
  "caption": "Test pin description",
  "link": "https://example.com",
  "board": "Test Board",
  "media_url": "https://example.com/image.jpg"
}
```

## Сборка из исходников

```bash
cd source/android_agent_app
gradle assembleDebug
```

APK появится здесь:

```text
source/android_agent_app/app/build/outputs/apk/debug/app-debug.apk
```

## Важно

Это Android-agent MVP. UI приложений Instagram/TikTok/Pinterest часто меняется, поэтому селекторы и шаги в `AgentAccessibilityService.kt` надо донастраивать на реальном телефоне и реальной версии приложения.
