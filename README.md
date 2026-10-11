# Chat Notifier

Small client-side Fabric mod that sends a Windows notification when a chat or game message matches a regex.

## Features

- Multiple notification rules
- Separate message and cooldown for each rule
- Notification history in `config/chat-notifier-notifications.txt`

## Setup

Press **N** in-game to open the settings. Add a trigger regex, notification text, and cooldown for each rule.

Settings are saved to:

```text
config/chat-notifier.properties
```

Triggers use Java regex syntax. Minecraft color codes (`§`) are supported.
The settings screen supports up to 50 rules and scrolls when they do not fit on screen.

Avoid complex nested patterns such as `(a+)+` or `(.+)+`; they can use a lot of CPU on long messages. For copied text, use `\Q...\E` instead.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API
- Fabric Language Kotlin 1.14.0 or newer
- Java 25

## Build

```text
gradlew.bat build
```

The mod JAR is written to `build/libs/`.
