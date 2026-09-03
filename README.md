# MiraPlaytime

AFK-aware active playtime tracking for the Mira Paper 1.21.11 / Java 21 ecosystem.

## Download

Current release: **v0.1.0**

[**Download MiraPlaytime v0.1.0**](https://github.com/FiveSOCE/Mira-Playtime/releases/download/v0.1.0/MiraPlaytime-0.1.0.jar)

[View all releases](https://github.com/FiveSOCE/Mira-Playtime/releases)

## Features

- active playtime instead of raw connection time
- configurable AFK timeout
- daily, weekly and all-time counters
- daily/weekly/all-time Top 10 leaderboards
- `/playtime [player]`
- `/playtimetop [daily|weekly|all]`
- PlaceholderAPI support
- public `MiraPlaytimeApi` through Bukkit ServicesManager

Playtime pauses while a player is AFK and resumes when they move, interact or use commands.

## PlaceholderAPI

```text
%miraplaytime_daily%
%miraplaytime_weekly%
%miraplaytime_all%
%miraplaytime_daily_seconds%
%miraplaytime_weekly_seconds%
%miraplaytime_all_seconds%
%miraplaytime_afk%
```

## Data

```text
plugins/MiraPlaytime/playtime.yml
```

## Requirements

- Paper 1.21.11
- Java 21
- PlaceholderAPI optional

## Building

```bash
gradle clean build
```

Output:

```text
build/libs/MiraPlaytime-0.1.0.jar
```
