# Testing

## Automated

```bash
cd backend && npm test                   # 18 tests: API, auth, anti-cheat, trades, redemption, admin, rate limits, drop math
cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest
```

The Android suite has 14 tests:
- **GameLogicTest**: physics, collisions, Endless reaching Platinum with an autopilot on 5 seeds and 4 screen heights, difficulty ramp, capsule spacing, rarity distribution over 300k rolls, Celestial rate under 0.2%, earned items never dropping, guest rewards and daily caps, sealed capsules for accounts, adventure unlocks and mastery, and save round-trips.
- **everyAdventureLevelIsBeatable**: a search solver proves all 20 levels can be finished. Levels are deterministic, so the input sequence it finds is a real solution.
- **ScreenshotTest**: renders real screens through the game code with Robolectric native graphics and writes them to `mobile/androidApp/build/screens/*.png`, for phone, small and tablet aspect ratios.
- **OnlineIntegrationTest**: runs the real `Api`/`Account` client against a running backend. It covers register, a too-fast run being rejected, server-opened capsules, offline queueing then sync, friends, messages and notifications, and clean sign-out on an invalid session. It's skipped if no server is up. Start one with `npm start`, or set `PAKPAK_TEST_SERVER`.

## Manual checklist on a real device
Install `mobile/dist/PakPak-debug.apk` (Android) or the iOS build from [IOS.md](IOS.md), and run the backend reachable from the phone. Run through the list on both platforms.

1. **Offline first.** Use airplane mode, launch the app, play Endless and an Adventure level, and open the Collection. Everything should work and the badge should show OFFLINE.
2. Tap Friends, Messages or Trading while offline. You should get a "You're offline" explanation. Online but signed out, you should get a "Sign in needed" prompt.
3. Go online. The badge should show ONLINE. Register, and check the recovery code appears exactly once.
4. Play a run with a capsule. The results should say "opening…", then reveal the items, with coins matching the Profile screen.
5. Turn airplane mode on and play 2 runs. Profile → Account should show 2 pending runs. Turn airplane mode off and they should sync on their own within a few seconds.
6. Use a second phone or account: add a friend by `PK-` ID, chat, and check read status. Block them, and messaging should stop.
7. Trade: offer a duplicate. It should only complete after both of you confirm. Cancelling should leave items unchanged.
8. Press Home mid-run. The game should be paused on return. Kill the app, reopen it, and progress should still be there.
9. Admin: verify and reject a claim, edit the notice, and see the app show the new notice after it reconnects.
10. Check the Settings toggles (music, sound effects, vibration), and test on a small phone and a tablet.
