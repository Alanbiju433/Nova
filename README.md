# Nova — your AI health buddy (v1.0)

An Android app (Kotlin + Jetpack Compose). Nova is a friendly robot with an EMO-style face that lives on your phone. It logs food from photos or voice, builds reminders around your real weekly routine, coaches workouts with the camera, and helps with study and work. It works offline first; the AI is optional and uses your own Google Gemini key.

Portfolio write-up: [docs/PORTFOLIO.md](docs/PORTFOLIO.md) · Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · Play release guide: [docs/play/PLAY_RELEASE.md](docs/play/PLAY_RELEASE.md) · Privacy: [docs/play/privacy.html](docs/play/privacy.html)

## New in 1.0 — ready for Google Play
- **First-run setup, with nothing personal built in.** Name, age, height and weight feed a target calculator (Mifflin–St Jeor, safe calorie floor). Setup also covers your goal, food culture and rules, health notes, town (weather), currency, and an 18+ confirmation with a clear "not medical advice" note.
- **Schedule builder ("My week").** Add work shifts (pay rate, unpaid break, travel time), classes, online classes, worship, socials and other events. Rules like "only / not in week 4 of the month" are supported, along with walk time, bedtime and workout days. Reminders, shift pay, insights, briefings and calendar sync all come from this. It can be exported and imported as text.
- **AI consent.** Nothing goes to Google until you switch the AI on. There is a privacy policy link and a **Delete all my data** button in Settings.
- **Play build vs full build.** The `play` build has no self-updater and doesn't use the install-packages or battery-optimisation permissions. The `full` build (sideloaded APK) keeps the GitHub self-updater.
- **Platform:** targets Android 16 (API 36), adaptive and themed launcher icon, proper notification icon.
- **Region-aware:** emergency advice matches the phone's country (999/111, 112, 911…) and uses your currency symbol. Malayalam flavour (Manglish and Kerala festivals) is optional.
- **76 automated tests.** New tests cover routine rules, the target calculator, and a guard that fails the build if the developer's personal data ever shows up in the app source.

## Install
- **From Google Play:** coming soon (closed testing).
- **APK:** install `Nova-v1.0.0-full.apk` and allow "install unknown apps".

Then run setup. For the AI, get a free key at https://aistudio.google.com → **Get API key**, paste it on the last setup step, and switch the AI on.

## What it does
| Tab | What's in it |
|---|---|
| **Nova** | Animated robot, rings for protein, calories and steps, chat by voice or text, food and outfit photos, a morning briefing, and phone commands ("set an alarm for 6", "call Mum", "navigate to the station"). |
| **Food** | Daily log, barcode scan, fridge-photo meal ideas, grocery list, hands-free cooking guide, 2-day meal prep, optional liver-friendly rating. |
| **Body** | Check-in and journal, a 4-week beginner plan, camera rep counter and form coach, gym log with progression, progress photos. |
| **Life** | Pet level and streaks, water, focus timer with eye breaks, wind-down breathing, weekly coach review, money, football, memory, challenges. |
| **Work** | Job tracker and voice mock interviews, dissertation pace and milestones, flashcards from slide photos, shift pay and payslip check. |
| **Health** | Test results with trends, medicines as prescribed (Nova never suggests doses), follow-ups, doctor PDF summary. |

## Build it yourself
- **Debug:** `./gradlew assemblePlayDebug` or `./gradlew assembleFullDebug`
- **Tests:** `./gradlew testPlayDebugUnitTest`
- **Play upload (AAB):** `./gradlew bundlePlayRelease`. This needs `keystore/upload.properties` with the fields `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- **Sideload APK:** `./gradlew assembleFullRelease` (signed with `keystore/nova-signing.keystore`)

Keystores are git-ignored. Never commit them.

## Privacy in one line
No accounts, no servers, no ads, no analytics. The data is encrypted on the phone, and the AI is opt-in with the user's own key. See the [privacy policy](docs/play/privacy.html).
