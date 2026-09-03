# MiraPlaytime

MiraPlaytime tracks active, AFK-aware player playtime for the Mira Paper server suite. It separates genuine activity from raw connection time and maintains daily, weekly and all-time totals and leaderboards.

## Download

[**Download MiraPlaytime v0.1.0**](https://github.com/FiveSOCE/Mira-Playtime/releases/download/v0.1.0/MiraPlaytime-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- PlaceholderAPI optional

## How MiraPlaytime Works

Players accumulate active playtime while online. After the configured AFK timeout, tracking pauses until the player performs activity such as movement, interaction or command use. The plugin keeps separate daily, weekly and all-time counters and can rank players in each period.

Playtime is persisted in `plugins/MiraPlaytime/playtime.yml`. A public `MiraPlaytimeApi` and PlaceholderAPI values expose formatted time, raw seconds and AFK state to other systems.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/playtime` | None required | Shows your own active playtime totals. |
| `/playtime <player>` | `miraplaytime.others` | Shows another player's active playtime. |
| `/playtimetop daily` | None required | Shows the daily playtime leaderboard. |
| `/playtimetop weekly` | None required | Shows the weekly playtime leaderboard. |
| `/playtimetop all` | None required | Shows the all-time playtime leaderboard. |
| `/playtimetop` | None required | Shows the default playtime leaderboard view. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miraplaytime.others` | OP | Allows viewing another player's playtime with `/playtime <player>`. |
