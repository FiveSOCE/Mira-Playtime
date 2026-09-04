# MiraPlaytime

MiraPlaytime tracks active, AFK-aware player playtime for the Mira Paper server suite. It separates genuine activity from raw connection time and maintains daily, weekly and all-time totals and leaderboards.

## Download

[**Download MiraPlaytime v0.1.1**](https://github.com/FiveSOCE/Mira-Playtime/releases/download/v0.1.1/MiraPlaytime-0.1.1.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- PlaceholderAPI optional
- MiraLeaderboards optional

## How MiraPlaytime Works

Players accumulate active playtime while online. After the configured AFK timeout, tracking pauses until the player performs activity such as movement, interaction or command use. The plugin keeps separate daily, weekly and all-time counters and can rank players in each period.

v0.1.1 makes day/week boundaries use a configurable IANA timezone, prunes old per-day/per-week history using configurable retention windows, exposes player rank alongside time, and awards configurable MiraCore milestones such as `miraplaytime.hours_100`. Offline players are no longer reported as AFK simply because they have no live activity timestamp.

Playtime is persisted in `plugins/MiraPlaytime/playtime.yml`. When MiraLeaderboards is present, MiraPlaytime publishes stable UUID-backed scores into `playtime_daily`, `playtime_weekly` and `playtime_all`. Daily and weekly boards are cleared exactly when their authoritative playtime period rolls over, while all-time remains persistent. A restart performs an initial backfill from stored playtime so offline players do not disappear from rankings.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/playtime` | None required | Shows your own active playtime totals. |
| `/playtime <player>` | `miraplaytime.others` | Shows another player's active playtime. |
| `/playtimetop daily [page]` | None required | Shows the daily active-playtime leaderboard using MiraCore pagination. |
| `/playtimetop weekly [page]` | None required | Shows the weekly active-playtime leaderboard. |
| `/playtimetop all [page]` | None required | Shows the all-time active-playtime leaderboard. |
| `/playtimetop` | None required | Shows the default playtime leaderboard view. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miraplaytime.others` | OP | Allows viewing another player's playtime with `/playtime <player>`. |


## MiraLeaderboards Integration

When available, MiraPlaytime publishes these boards using each player's UUID as the stable entry ID and current username as the display name:

- `playtime_daily`
- `playtime_weekly`
- `playtime_all`

Board IDs and sync cadence are configurable. MiraLeaderboards remains a display/ranking mirror only; MiraPlaytime's AFK-aware counters in `playtime.yml` remain authoritative.

## Milestones

Configured hour thresholds award idempotent MiraCore milestones using:

`miraplaytime.hours_<hours>`

The default thresholds are 1, 10, 100, 500 and 1000 hours.

## PlaceholderAPI

Existing formatted/raw placeholders remain supported. v0.1.1 adds:

- `%miraplaytime_daily_rank%`
- `%miraplaytime_weekly_rank%`
- `%miraplaytime_all_rank%`
- `%miraplaytime_day_key%`
- `%miraplaytime_week_key%`

## API

`MiraPlaytimeApi` is registered with Bukkit services and MiraCore. In addition to seconds, AFK state and Top N, it exposes rank, last activity, configured timezone and the active day/week keys.
