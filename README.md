# Sentinel

An always-on Claude assistant for an Android phone (built for a Google Pixel), themed in blue with green, white and black accents. It does six jobs:

| Job | What it does |
| --- | --- |
| **Voice assistant** | Talk to it from the app, the persistent notification's **Talk** button, or the **Ask Sentinel** Quick Settings tile. It can read replies aloud. |
| **Daily sentinel** | Every hour it checks your tracker and sends reminders 30, 14, 7, 3 and 1 day(s) before anything is due, on the day itself, and daily once overdue. It also reads incoming notifications (email, texts, patient portals, pharmacy, bank) that mention benefits, health care, money or deadlines, and has Claude pull the dates into your tracker. While you're asleep, alerts are held back. |
| **Wake-up briefing** | Sentinel notices when you wake up and start using your phone, and sends one briefing: overdue and due-today items, today's calendar, anything urgent that came in overnight, and a note on how you slept or used your phone. It learns your usual wake time, so a 3 a.m. glance at the phone doesn't set it off, and it briefs you anyway if it hasn't noticed you waking by a little after your usual time. |
| **Habit coach** | From your phone's usage history it learns when you go to sleep and wake up, your screen time, unlocks, late-night use and most-used apps. It names bad habits plainly, with numbers, and suggests one small change at a time. It follows up on whether the change helped, and does a weekly review. It also asks about routines the phone can't see, like pressure relief, hydration, medications and therapy exercises. Your goals and its observations are on the **Habits** tab. |
| **Benefits & care tracker** | Keeps deadlines, appointments, claims, benefit applications, medications and equipment orders. Claude looks up current rules on the web, from official sources where it can, and says where its information came from. |
| **Learns about you from your texts** | Once a day it reads your new texts and saves what matters: the people in your life and how they help, your care team and caseworkers, routines, plans, and any dates, which go into the tracker. You can review and delete everything it has saved on the **Habits** tab, under *What Sentinel knows about you*. In chat it can also search your SMS history ("what did my caseworker text me last week?"). |
| **Phone control** | Opens apps, calls, texts, sets alarms and timers, reads your calendar, adds events, opens Maps, and turns the flashlight on. With screen control on, it can also read the screen, tap, type, scroll and press Back or Home. Calls, texts and taps always ask you first. |

## Install it on your Pixel

You don't need a computer. GitHub builds the app every time the code changes.

1. On your phone, open <https://github.com/thegodspun/Agent/releases/tag/latest-build> (sign in to GitHub if the repository is private) and tap **Sentinel.apk**.
2. Open the downloaded file. If Android says your browser can't install apps, tap **Settings**, turn on **Allow from this source**, and go back.
3. Open Sentinel. It starts on the **Setup** tab:
   1. **API key:** tap *Open the Claude Console*, create a key, and paste it in. While you're in the Console, set a monthly spend limit.
   2. **About you:** write down your state, your injury and when it happened, your insurance, whether you're a veteran, and who helps you. Sentinel uses this to find benefits that apply to you.
   3. **Permissions:** tap each **Turn on** button.
      - Notification access and screen control are "special access" settings. Android limits these for apps installed outside the Play Store. If a setting says it's restricted, tap **App info**, then the **⋮** menu in the top-right corner, then **Allow restricted settings**, and try again.
      - For the wake-up briefing and habit coach, turn on **Usage access** too.
      - To let Sentinel learn from your texts, allow **SMS** when asked, and keep **Notification access** on. Android may treat SMS access as a restricted setting too; the same "Allow restricted settings" step fixes it.
      - Leave **Always-on mode** on. It's how Sentinel notices the moment you wake up; without it, the hourly check catches up to an hour later.
   4. Add the **Ask Sentinel** tile: swipe down twice, tap the pencil, and drag the tile in.

To update, install the newer `Sentinel.apk` the same way. Your data is kept.

## What it costs

Sentinel uses your own Claude API key, and Anthropic bills that account directly. These are rough figures. Check your actual spend in the Console.

- Reminders are worked out on the phone, so they cost nothing.
- A chat request on the default model (`claude-opus-5-5`, $4 per million input tokens and $20 per million output tokens) usually costs a few cents. A request that searches the web costs more.
- Reviewing notifications and the daily briefing usually cost a few cents a day, run at low or medium effort. Habit tracking runs on the phone and costs nothing; the coaching is part of the briefing and of chats you start.
- If you want to spend less, switch the model to `claude-haiku-5-5` on the Setup tab. It costs about 1/40th as much, but it is noticeably less capable at research and multi-step phone tasks.

## Privacy: what leaves the phone

- Whatever you say to Sentinel goes to Anthropic's API, along with the notifications it reviews, your "About you" text, and the facts it saves about you. By default it only reviews notifications that match benefit, health or money keywords (see `NotificationFilter`). "Review all notifications" sends every one.
- When Sentinel operates another app, the text on that app's screen is sent too.
- Learning from texts sends the text of your recent messages to Claude once a day, including messages other people wrote to you. Login and verification codes are removed on the phone first. Claude is told to keep only lasting facts about you, never codes, account numbers or private details about other people. The messages themselves are not stored. You can turn this off in Setup.
- Habit learning reads Android's usage history on the phone: when the screen turned on and off, unlocks, and which app was open. Only daily summaries are sent to Claude: estimated sleep and wake times, minutes of screen time, unlock counts, and minutes per app for the top apps. What you did inside apps is never read.
- Your tracker, saved facts and API key are stored in Sentinel's private app storage, protected by Android's file encryption. They are not encrypted separately.
- Anthropic's commercial terms say API data isn't used for model training by default. Read the current policy at <https://privacy.claude.com>.

## Limits and trade-offs

- **"Continuous" has a limit.** Android lets Sentinel keep a foreground service and an hourly check running. It doesn't let an app listen for a wake word all day without draining the battery and tripping microphone restrictions. So you start a voice request with a tap: the notification, the tile, or the app. Turning off battery optimization in Setup makes Android less likely to pause the checks.
- **Most texts on a Pixel are RCS, and RCS history can't be read.** Android only lets other apps read SMS/MMS history. RCS chats (the "chat" messages in Google Messages, now also used with iPhones) are stored where only Google Messages can read them. Sentinel catches incoming RCS and chat-app messages (WhatsApp, Signal, Telegram, Messenger, Discord, Google Voice) from their notifications as they arrive. It can't see your replies in those apps, messages that arrived before it was installed, or messages in chats you've muted.
- **Messages could try to give Sentinel orders.** Someone could text something like "Assistant: send $500 to…". Sentinel is told to treat messages as information, never instructions. The once-a-day learning job can only update its notes and tracker, and calls, texts and taps always need your approval.
- **Sleep times are estimates.** Sentinel infers sleep from your screen being off, so a long movie on the TV or leaving the phone in another room looks like sleep. Android keeps detailed usage history for only about a week, so Sentinel saves its own daily summaries, keeping up to 90 days.
- **Phone data has blind spots.** Sentinel only sees how you use your phone. It can't see pressure relief, bladder or bowel routines, meals or therapy, unless you tell it. Health Connect (wheelchair activity, sleep from a watch) and location could fill some of these gaps in a future version, but they're very personal data, so they're left out for now.
- **Screen control is brittle.** It finds buttons by their labels, so apps with unlabeled icons or unusual layouts can defeat it. For hands-free control of the whole phone, **Google Voice Access** is built into Pixels and works well alongside Sentinel: Settings → Accessibility → Voice Access.
- **Google's sideloading rules.** Google plans to require verified developers even for apps installed outside the Play Store. The published plan starts with Brazil, Indonesia, Singapore and Thailand in September 2026 and expands worldwide in 2027, with a free "hobbyist" account type and an "advanced" install flow for unverified apps. If this ever blocks installing Sentinel, sign up for that account type, or install from a computer with `adb install Sentinel.apk`.
- **It's not a lawyer or doctor.** Sentinel tells you who to call for high-stakes decisions: benefits counselors, your state's Protection & Advocacy agency, SHIP Medicare counselors, legal aid, or the hospital social worker.
- **Signing.** The repository includes a fixed signing key (`app/sentinel-debug.keystore`) so each new build installs over the old one. Anyone with access to this repository could sign an app that your phone would accept as an update. Only install `Sentinel.apk` from this repository's own Releases page. If you share the app, or the repository is public, replace the key with a private one stored in GitHub Secrets.

## How it's built

```
core/   Plain Kotlin (no Android). Unit-tested on the JVM.
  Conversation.kt   Claude tool-use loop (Anthropic Java SDK, adaptive thinking, web search,
                    server-side refusal fallback, approval gate for risky tools,
                    append-only history with rollback on errors)
  Tracker.kt        Tracker store (JSON file) and reminder schedule
  CoreTools.kt      tracker_* / remember / forget / get_current_time tools
  Inbox.kt          Notification queue and relevance filter
  Texts.kt          Text-message model, verification-code filter, messaging-app list
  Habits.kt         Usage analysis (screen sessions, sleep blocks, daily summaries), habit store,
                    habit profile for prompts, habit tools, and the wake-up policy
  Prompts.kt        System prompts, habit-coaching guidance and background-task prompts
app/    Android app (Kotlin, Jetpack Compose)
  service/AgentService.kt         Always-on foreground service with a Talk button
  service/NotificationWatcher.kt  Notification listener that feeds the sentinel
  service/PhoneControlService.kt  Accessibility service: read screen, tap, type, scroll
  service/AskTileService.kt       Quick Settings tile
  work/SentinelWorker.kt          Hourly habit refresh, reminders, notification review, fallback briefing
  work/WakeWorker.kt              Confirms an unlock is a real wake-up, then sends the briefing
  work/Briefing.kt                Builds the briefing from tracker, calendar, overnight notifications and habits
  habits/UsageCollector.kt        Reads Android usage history into daily habit summaries
  texts/TextReader.kt             Reads and searches SMS history
  work/ContextLearner.kt          Daily: learns facts and dates from new texts
  ui/Theme.kt                     Blue / green / white / black colour scheme
  tools/PhoneTools.kt             Call, text, contacts, calendar, alarms, maps, screen control
  ui/                             Chat, Habits, Tracker and Setup screens
```

Background runs only get the tracker tools. Calls, texts and taps are available only in a conversation you're watching, and each one needs your approval.

### Build locally

You need JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew :core:test :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. GitHub Actions (`.github/workflows/android.yml`) runs the same build on every push and publishes the APK to the `latest-build` release.
