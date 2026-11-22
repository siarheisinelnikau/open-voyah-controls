# Factory launcher home screen

This maps the home screen of the factory launcher on the central display so that overrides of its cards, tiles and panels can be planned and tested without the car. Two cards have overrides so far, each behind a setting that is off by default: the navigation card (see [Navigation card replacement](#navigation-card-replacement)) and the weather card's data (see [Home weather card](FRIDA_AGENTS.md#home-weather-card)). Nothing else here is implemented, and none of it is a reviewed injection target. The panel buttons that Controls already replaces are in [Injection targets and controls](INJECTION_MAP.md).

**Source.** Captured on 2026-11-21 from one H97C head unit:

- Firmware: Android 11, build `RQ3A.211001.001`.
- Launcher: `com.qinggan.app.launcher` 1.0.6, versionCode 14, APK sha256 `d1d5e3de…`.
- Hiboard: `com.qinggan.app.hiboard`, APK sha256 `25014d1a…`.

**How it was captured.** Read-only: the debug view server (`DUMP`/`CAPTURE`), `uiautomator dump`, `screencap` and `dumpsys window`. The APKs were then decompiled for static analysis.

**What was live and what was not.**

- Only the first pager page was on screen while the unit was connected.
- Page 2, the lowered-screen layout and the pull-down panels come from the view dump (page 2 and the lowered layout are inflated but hidden) and from the decompiled code.

**Raw material stays local.** Raw dumps, screenshots, element PNGs and the proprietary APKs stay outside the repository; they can show the map, media, contacts and account names. The structure-only fixture is [`tests/fixtures/launcher-home-h97c.json`](../tests/fixtures/launcher-home-h97c.json). It holds the home window's view tree (class, resource id, bounds, visibility, clickability), and `tests/home-screen-fixture.test.cjs` keeps it free of screen text.

**Conventions.**

- Bounds are `[x, y, width, height]` in pixels of display 0 (1920x720, 160 dpi), as the view server reported them.
- Views on a scrolled-out page report x ≥ 1920.
- `Class.method:line` references point into the jadx 1.5.1 output of the launcher above.

## Windows on the central display

| Window title | Owner | Type | Frame | Role |
| --- | --- | --- | --- | --- |
| `…launcher.activity.MainActivity` | `com.qinggan.app.launcher` | base application, translucent | full screen | Home: cards, all-apps page, lowered-screen layout |
| `MainScreenNavigationBar` | `com.qinggan.app.launcher` | 2110 | `[0,0,145,720]` | Left panel, see [the panel map](INJECTION_MAP.md#standard-launcher-panel) |
| `StatusBar1` | `com.qinggan.systemui` | 2118 | `[0,0,1920,50]` | Top bar: in-car PM2.5, climate fan/mode/recirculation, DVR, Bluetooth, Wi-Fi, mobile signal, clock |
| `HiboardDelegate` | `com.qinggan.app.hiboard` | 2106 | `[145,0,1775,50]` | Touch strip that pulls down the top panel |
| `VehicleHiboardDelegate` | `com.qinggan.app.hiboard` | 2106 | `[0,0,30,720]` | Touch strip on the left edge (over the panel) for the vehicle panel. Its visible handle `line_view` is `[10,290,6,140]` |
| `IslandContainer` | `com.qinggan.app.islandapp` | 2112 | `[670,10,580,6]` | Status "island" pill at the top centre (collapsed `container` `[890,10,140,6]`) |
| (untitled) | `com.qinggan.visiblesay` | system overlay | off screen | Voice "say what you see" helper |

The launcher process is `android.uid.system` and `persistent`, and `MainActivity` is `singleTask`. The same process hosts the passenger home `secondlauncher.activity.SecondMainActivity` on display 1. Most shared classes serve both displays and take a screen id: `AllAppBarView`, `AllAppDataManager`, `AppLauncher`, `NavigationBar`.

## Home window structure

```
main_root (FrameLayout)
├─ screen_up_main (FrameLayout, VISIBLE in normal mode)
│  ├─ home_view_pager  SlowViewPager [144,0,1776,720], swipe disabled, 2 pages
│  │  ├─ page 1: HomeCardFragmentNew → home_card_container HomeCardView97C [144,52,1776,668]
│  │  │          └─ rcy_view RecyclerView, horizontal 2-row grid of cards
│  │  └─ page 2: AllAppFragment → all_app_view AllAppBarView (2x6 paged grid of all_app_item)
│  └─ home_page_indicator MyPageIndicator (only while page 2 shows)
└─ screen_down_main (FrameLayout, GONE unless the display is lowered)
   ├─ screen_down_nav WidgetDownScreenView, screen_down_media HomeMediaView97c, screen_down_phone PhoneCardView
   ├─ change_to_screen_up View
   └─ tab_media_view TabMediaView
```

**Fields.** `MainActivity` holds the frame in these fields:

- Pager and pages: `viewPager`, `mPageIndicator`, `mHomeFragmentPagerAdapter`, `mHomeCardFragment`, `mAllAppFragment`.
- Roots: `mScreenUpRootView`, `mScreenDownRootView`.
- State: `showAllApp`, `mScreenLiftMode`.

**Pager.** Swiping between the two pages is off (`MainActivity.onCreate:158-159`). Page 2 opens only when the panel's All apps button sends a HOME intent with `showAllApp=true`.

**Home key.** A plain Home (`onNewIntent`, then the posted `onResume` runnable) returns to page 1 without animation and scrolls the app grid back to its first sub-page. It does not reset the card strip's scroll position.

**Controls interaction.** With Controls' reviewed panel assignments, the central `mainApps` button opens the Controls grid by default. Factory page 2 is then reached through the button's hold, which invokes the original click.

**Config changes.**

- Handled in place: locale, layout direction, orientation and screen size. A language change only runs the `onLocaleChanged` chain.
- Activity recreated: uiMode, density or font scale. The activity's fragment fields then point at stale fragments, so hooks should find fragments through the FragmentManager.

## Page 1: cards

`HomeCardView.loadDefaultData()` builds the card list in code, and `HomeCardView97C.buildCardInfoByType(CardType)` turns each type into a view.

**Layout.**

- Fixed columns: navigation | media | weather over apps | an optional fourth column.
- The fourth column follows the car's configuration (`AppUtils` via `VehicleConfigHelper`). Seat and fragrance hardware gives seat over fragrance, as on this unit. Only one of them gives that one as a big card. Neither gives no fourth column.
- Large cards fill a column (576x652); medium cards pair up in one (576x326 each). Column pitch is 592 px, so the fourth column sits at x = 1920 until the strip is scrolled.

**Editing.** Users cannot edit, reorder or hide cards.

- No edit mode is ever entered.
- The drag helper is never attached.
- The saved order (SharedPreferences `HOME_CARD_VIEW_97C_SP`, key `CARD_INFO`) is written but never read back.
- No card has a long-click action.

| Card (`CardType`) | View class | Bounds | Clickable view → action | Redraws from |
| --- | --- | --- | --- | --- |
| Navigation (`NavCard`, large) | `homecard.HomeNaviCardView` → `large_bd_navi` → `com.baidu.widgetmap.MapWidgetLayout` + `SurfaceView surfaceview` | `[144,52,576,652]` | `MapWidgetLayout.dispatchTouchEvent` treats a press and release within 10 px as a tap and consumes it. It forwards the tap point to Baidu (`MapWidgetManager`, request 1108), or, when the Baidu service is not connected, starts `com.baidu.naviauto/.NaviAutoActivity` (`MapWidgetManager.openMap`). | A virtual display named `widget` from the Baidu navigation app is drawn into the SurfaceView, so `CAPTURE` shows it blank. Also Baidu SDK callbacks and power-state callbacks. |
| Media (`MediaCard`, large) | `mediaCard.home.view.v97c.HomeMediaView97c` → `h97cV2.BigMediaView97cV2` | `[736,52,576,652]` | `layout_container` (card) opens the current source's player via `MediaJumpUtils`. `ll_title` `[752,68,204,68]` shows the source tabs `fl_rcy`/`media_tab` (they hide after 5 s), and a tab plays that source. `uranus_iv_last` `[756,584,170,88]`, `uranus_play_or_pause` `[960,571,133,133]` and `uranus_iv_next` `[1127,584,170,88]` call `MediaManager.playPrevious` / `playOrPause` / `playNext`. `link_btn` (Bluetooth source, phone disconnected) opens `com.qinggan.settingnetwork.BLUETOOTH`. `iv_lrc` toggles a lyrics line. Progress `progress_bar` is display only. | Media sessions through `MediaManager` → `BaseWidgetHelper` → `MediaInfoHelper97C`; progress polled about once a second |
| Weather (`WeatherCard`, medium) | `homecard.HomeWeatherCardView` | `[1328,52,576,326]` | Whole card (unnamed child FrameLayout) opens the launcher's own `WeatherActivity`. | EventBus `UpdateLiveWeatherEvent` / `UpdateLiveWeatherError`; clock `weather_time` and date `weather_date` tick by themselves |
| Apps (`AppCard`, medium) | `homecard.HomeAppCardView` → `all_app_list` (3-column grid) | `[1328,378,576,326]`; tiles 171x126 at x 1344/1536/1728, y 410/548 | Six fixed subtypes in code: User Center, Phone, Settings, Video, HiCar, DVR (DVR only when the car config has it). Each `all_app_item` calls `AppLauncher.startAppBySubType(context, subType, 0)`; HiCar starts a service. The listener is set again on every bind. | Theme and locale rebinds |
| Seat (`VehicleCard`, medium) | `homecard.HomeSeatCardView` | `[1920,52,576,326]` (fourth column) | `seat_medium_rl` / `seat_medium_iv` open action `com.qinggan.app.vehicle.SHOW_SEAT`. `card_seat_medium_wind_iv`, `card_seat_medium_heading_iv` and `card_seat_medium_massage_iv` (90x90) step the driver seat's ventilation, heating and massage through off → 3 → 2 → 1 → off. They write `CanBusManager.setVehicleState` with `FRONT_SEAT_VENTILATION_COMMAND_LEFT`, `FRONT_SEAT_HEATING_COMMAND_LEFT` and `FRONT_SEAT_MASS_INTEN_LEFT`, and the card re-reads CAN state 3 s later. | `CanBusListener.onVehicleStateChanged` → `updateCardViewByCan` |
| Fragrance (`MiniScene`, medium) | `homecard.HomeFragranceCardView` | `[1920,378,576,326]` (fourth column) | `air_medium_bg` opens `SHOW_FRAGRANCE` (the `air_medium_cl` listener does nothing). `card_air_switch_zhuzi` / `_mudan` / `_shuigan` select scent 1 / 2 / 3 with `IVI_FRAG_TASTE`. Tapping the active scent turns fragrance off (`FCM_SW_REQ`), and tapping any scent while it is off turns it on. | CAN callbacks; scent names from system settings `fg_name_0x` |
| Big seat / big fragrance (`BigVehicleCard`, `BigSceneCard`) | `HomeSeatBigCardView`, `HomeFragranceBigCardView` | not on this unit | Same ids and logic as the medium cards. | Same |

Every card extends `homecard.BaseHomeCardView` (a `FrameLayout` implementing `CardInfoInter`), except the media card, which is `HomeMediaCard`. Each card sits in a `CustomRoundedFrameLayout` `content_view` inside a holder from `card_item_big` / `card_item_min` (`CardAdapter`). In the medium holders that is under `fy_root` / `root_view`.

## Page 2: all apps

**Grid.** `AllAppBarView` is a RecyclerView: a `PagerGridLayoutManager` of 2 rows x 6 columns, scrolled horizontally in sub-pages of 12, with a snap helper. `MyPageIndicator` shows one dot per sub-page. There is never a third pager page; more apps only add sub-pages.

**Tiles.** Each tile `all_app_item` is a `PressedEffectLayout` 280x189 (alpha 0.6 while pressed) with:

- `item_icon`, a Fresco `SimpleDraweeView` 150x150. Its *background* is set from the skin drawable. Only mini-app tiles use `setImageURI`.
- `item_name`, the label.

Folder tiles reuse the id with four small icons.

**List source.** Not a PackageManager query, and there is no package add/remove receiver.

- The list is a fixed set of OEM subtypes, filtered by vehicle configuration and `QGSettings` package states (`AppUtils.generateApp`).
- The order is saved per account in SharedPreferences (file = account id or `guest`, key `all_apps`); without a saved order a 25-entry default list is used (`AllAppDataManager`).
- It reloads at process start and on account login.

**Click and hold.**

- Click: every tile click goes through `AllAppBarView.onClick` (which jadx decompiles badly), and most subtypes then call `AppLauncher.startAppBySubType(context, subType, screenId)`.
- Hold: drag-and-drop with a jiggle animation, saved through `AllAppDataManager.swapApp`. There is no uninstall or edit menu.

**Launch path.** `AppLauncher` builds the intent in this order:

1. The registered interceptors in the public static list `AppLauncher.sLauncherInterceptors`. The launcher registers one itself, mapping Navigation to the Baidu navigation app.
2. A fixed switch of explicit actions and classes.
3. `getLaunchIntentForPackage`.

It then adds `FLAG_ACTIVITY_NEW_TASK` and calls `ActivityAnimUtils.startActivityByAnim(context, intent, displayId)`. That sets the launch display, uses a custom or no animation, and calls `startActivity`. There is no OEM multi-window call on this path.

**Unknown subtypes.** A subtype with no interceptor ends in `getLaunchIntentForPackage(null)`, which on Android 11 starts some unrelated launchable app. A custom tile needs its interceptor (or click hook) in place first.

## Navigation card replacement

**Setting.** **Yandex Navigator instead of the Baidu map on the home screen**, under the panel assignments, off by default. The agent side is described in [Home navigation card](FRIDA_AGENTS.md#home-navigation-card).

**How it is done.** It follows [Override approaches](#override-approaches): an overlay holding the navigator's traffic widget, added as the top child of `HomeNaviCardView` (not `large_bd_navi`, which the map code clears and makes visible again), with the Baidu map paused underneath. Taps are taken in `MapWidgetLayout.dispatchTouchEvent`, so the overlay holds no listener of its own. The media, weather, apps and seat cards are not touched.

**Options that were weighed.**

| Option | Verdict |
| --- | --- |
| Host an app widget from a Yandex app (`AppWidgetHost` in the launcher) | Chosen. Yandex Navigator 25.8.2 on the unit publishes six traffic widgets (`ru.yandex.yandexmaps.widget.traffic.internal.TrafficWidget{2x1,2x2,3x1,3x2,5x1,5x2}Provider`, 110 dp tall at most): a Yandex map with traffic around the car (`traffic_widget_map_view`), the traffic score, a route line and the forecast by hour. Each declares a configuration activity. Yandex Maps is not installed; Yandex Browser and Yandex Music have widgets too. The unit had no widget host before (`dumpsys appwidget`). |
| Run Yandex Navigator on a virtual display drawn into the card, as Baidu does | Rejected. One navigator task cannot be in the card and on screen at once; moving it between displays recreates its surface and can disturb active guidance; it doubles the GPU load. |
| Draw a Yandex map in the card | Postponed. WebView refuses to run in the launcher (a system-uid process), so Controls would have to draw the map and embed it (`SurfaceControlViewHost`, Android 11). The Yandex map SDKs need a personal API key and location access. |
| Show the navigator's own state from its notification | Fallback when the navigator has no widget or binding fails. Without a route the navigator had no notification (checked on the unit). |

**Not established.** Whether the traffic widget draws without its configuration screen, how it fills the tall card (its smallest provider is 110 × 110 dp), and what the navigator's notification holds during guidance.

## Lowered-screen layout

**What lowered means.** `com.qinggan.utils.ScreenLiftUtils.getScreenLiftState()` reads `persist.qg.canbus.bcm_screenAutoLiftFdb` (default 2 = normal; 1 = lowered). The code calls 1 the lowered-screen mode of the motorised display.

**Switching.**

- At runtime the broadcast `action.qg.layout.changed` (extra `type`) reaches `SkinBroadCastReceiver`.
- From there `MainActivity.doScreenLift` swaps `screen_up_main` and `screen_down_main`, and every card's `doScreenLift` and the panel follow.
- Whether this unit ever reports 1, and which component sends the broadcast, is not established.

**Lowered-layout widgets.**

| View | Class | Actions |
| --- | --- | --- |
| `screen_down_nav` | `com.baidu.widgetmap.WidgetDownScreenView` | `widget_downscreen_home_iv` / `widget_downscreen_work_iv` start guidance home/work (`MapAutoService` request 1008) or open address setup (1005); `iv_exit` stops guidance; any other touch opens the map |
| `screen_down_media` | `HomeMediaView97c` → `DropMediaCard` | `uranus_drop_iv_previous` / `_play_or_pause` / `_next` control playback; `uranus_drop_root_container` opens the player; `iv_icon` picks the source |
| `screen_down_phone` | `com.qinggan.aar_phone.PhoneCardView` | `call_log`, `phone_contacts` open the Bluetooth phone app on that tab; `dial_key` and the card open its main screen; `connect_bt_tv` opens Bluetooth settings |
| `change_to_screen_up` | `View` | Long press sends a test broadcast that nothing in the launcher acts on |

## Pull-down panels (Hiboard)

Both pull-down panels belong to a separate process, `com.qinggan.app.hiboard` (sources under `com.qinggan.app.hiboard`). The launcher does not handle the gestures. It only closes panels with the broadcast `android.intent.action.hiboard_control`, whose extra `hiboardId` is 1 for the top panel, 2 for the vehicle panel, 4 and 8 for the passenger ones. The receiver needs no permission.

**Windows.**

- All Hiboard windows are type 2106. That puts them above the island (2112), the status bar (2118) and the left panel (2110), so an open panel draws over the left panel.
- Which display a window goes to is chosen by the OEM window field `memoryType` (1 = display 0, 2 = display 1).
- The strips are added only once the system property `ignoreActivation` is true, or after the launcher starts `HiboardService`. While that has not happened there are no pull-downs.

**Opening and closing.**

- A panel window is added on the first drag from its strip and removed when fully closed. That is why neither panel was in the live window list.
- On release, a fling of at least 500 px/s opens or closes the panel; otherwise it snaps to the nearer state (`BlindsWindow.handleTouch`).

### Top panel (`system.HiboardBar`)

**Windows and gesture.**

- Strip: `HiboardDelegate` `[145,0,1775,50]`. Its touch listener `HiboardBar$9` feeds `BlindsWindow`.
- Panel: full width and 720 px tall (560 px when the display is lowered), sliding down from y = −720.
- It closes on a drag up, a touch to the right of the 1534 px card, voice, the broadcast above, or any change of top activity. It has no timer.

**Layout.**

- `HiboardBar.initPop` picks `hiboard_layout_h97` on H97/H97A/H97C, or `hiboard_layout_h97y` on overseas 97 units. The model comes from NVRAM item 43 and `ro.build.product`.
- The layout is inflated once with `LayoutInflater`; the generated ViewBinding classes are unused.
- Every tile goes through `HiboardBar.onClick(View)` and `onLongClick(View)`.
- The tiles are fixed in XML with no stored order. Only wireless charging (online vehicle config) and some overseas tiles change visibility.

| Tile id | Tap | Long press |
| --- | --- | --- |
| `account_*` | User Center (`com.qinggan.app.login`); refused while lowered | – |
| `message_center_layout` | Opens `com.qinggan.app.messagecenter` | – |
| `theme_icon` | Theme pop-up (`ThemeManager`) with cluster theme sync | – |
| `main_screen_dormant`, `second_screen_dormant` | `PowerManager.enter/exitScreenOff` for display 1 or 2 | – |
| `wifi_icon`, `bluetooth_icon` | Not toggles: open the compact pages `com.qinggan.settingnetwork.WIFI` / `.BLUETOOTH` | Full settings pages `com.qinggan.app.setting.WIFI` / `.BLUETOOTH` |
| `settings_icon` | Opens `com.qinggan.app.setting` | – |
| `wireless_charge_icon` | CAN `WCM_SWITCH` on/off | – |
| `iv_widget_mute` | `AudioPolicyManager.setMasterMute` | – |
| `privacy_mode_icon` | Confirmation, then CAN `IVI_PRIVACY_SETTING` with camera/microphone controls and setting `disturb_mode` | – |
| `iv_auto_light` | Backlight dimmer auto / manual | – |
| Sliders | Media volume (stream 3), navigation volume (stream 1), backlight 0–99 | – |

**Lowered display.** The panel becomes a 1275x560 card without theme, settings, wireless charging or message center, and long presses only show a toast.

### Left vehicle panel (`vehiclehiboard.main.VehicleHiBoardBarNew`)

**Windows and gesture.**

- Strip: `VehicleHiboardDelegate` `[0,0,30,720]`. It lies over the left 30 px of the panel window, and its handle line is the short vertical mark at the screen edge.
- Panel: 1224x720 (540 px tall when lowered), sliding in from x = −1194.
- It also opens by itself on a shift into P (preference `show_vehicle_hiboard_parking`) and then closes after 10 s.

**Tiles.**

- Users can configure the tiles; edit mode is off while lowered. There are 33 tile types (25 small, 2 middle, 6 large), created by `ComponentViewManager.createComponentView` from `WidgetInfo.operation`.
- Taps write CAN signals through `CanBusManager.setVehicleState`: windows, locks, seat heat/vent, fuel flap, charge port, parking brake, trunk, power mode, ambient light, sunroof.
- Long presses usually open a vehicle-settings page. Seat memory uses `SeatService`.

**Storage.** The layout is stored in SharedPreferences `launcher_data`:

- `left_small_widget_list` and `left_big_widget_list` hold the placed tiles; the `right_*` keys hold the tile library.
- The lists are rebuilt from per-model defaults when NVRAM item 51 changes.

### Island

`IslandContainer` (`com.qinggan.app.islandapp`) is a 580x6 window at the top centre showing a 140x6 pill while idle. When something is attached it grows to show media, a call, seat memory, a navigation place or a DVR storage warning, and each of those views handles its own taps. The container itself has no tap handler. Over the idle pill the Hiboard strip is on top, so a tap or swipe there reaches the strip.

### Hiboard override notes

Hiboard is not an injection target today; adding it means a new reviewed target process, like the text-adapter targets in [Injection targets and controls](INJECTION_MAP.md#target-processes).

**Top panel.**

- Intercept a tile: hook `HiboardBar.onClick` / `onLongClick` and filter by view id.
- Remap a launch: Hiboard has its own `com.qinggan.app.base.utils.AppLauncher.sLauncherInterceptors`, separate from the launcher's.
- Add a tile:
  - Because the panel is inflated once, an inserted view survives theme, locale and lift changes.
  - There is no free slot, so hide one tile and insert at its index. Refresh the new tile after `updateTheme` and `onLocaleChanged`.
  - `amendThemeIfNeeded` reverts head-unit theme changes that do not also change the cluster.
- Disable the gesture: remove the delegate window, or consume touches in `HiboardBar$9.onTouch`.

**Vehicle panel.**

- Intercept a tile: hook the small/middle/large component views' tap and long-press lambdas and filter on `operation`.
- Rebuilds: `reLoad()` recreates every tile, and CAN callbacks redraw tile state.
- Risks:
  - An unknown `operation` crashes the process.
  - Pressing Done in edit mode writes injected entries into `launcher_data`.

## Override approaches

The panel adapter `hooks/launcher-buttons.js` already runs inside the launcher process, so home overrides would be one more module of the main launcher bundle. They can reuse its action model and `createActionLauncher(Java)`, which starts a Controls action or app on a given display.

**Attaching late.** The launcher is persistent. When the agent attaches after boot, `MainActivity`, both pages and the app list already exist, so overrides must act on the live objects, not only on construction paths. Code that runs once at process start (`Application.onCreate`, the default interceptor list) can only be hooked with spawn gating.

| Goal | Hook point | Notes |
| --- | --- | --- |
| Remap what a home app tile or all-apps tile opens | Add an `AppLauncher$LauncherInterceptor` at index 0 of `AppLauncher.sLauncherInterceptors`. It returns an Intent for a subtype, or null to keep the factory one. | No method hook needed. It also changes the same subtypes when launched from the passenger display and from voice, so the interceptor must check the screen id and subtype. |
| Intercept a card click | Pre-hook the card's `onClick` (`HomeSeatCardView.onClick`, `HomeFragranceCardView.onClick`, `BigMediaView97cV2.onClick`) or `MapWidgetLayout.dispatchTouchEvent`. For the app card, the anonymous listener calls `AppLauncher.startAppBySubType`. | Keep a hold or explicit "factory" fallback, as the panel does. |
| Catch-all launch sink | `ActivityAnimUtils.startActivityByAnim(Context, Intent, int)` | Sees most launcher-started activities, including from the panel. Good for logging, too broad to remap blindly. |
| Replace one card's content (running launcher) | Add an overlay as the top child of the card's own FrameLayout and hide the original content. For media use `BigMediaView97cV2`, not `HomeMediaView97c`, which clears its children. For navigation use `HomeNaviCardView`, not `large_bd_navi`, which the map code clears. | Survives rebinds (the adapter only sets corners and the divider). |
| Replace a card type at construction | `HomeCardView97C.buildCardInfoByType(CardType)` returns another `CardInfoInter`. The APK ships `launcher.test.*TestCard` placeholders. | Runs only when the card list is built (activity creation), so it needs early attach or an activity recreation. |
| Reorder or hide cards (running launcher) | Change `CardDataManager.childDates`, call its private `updateCardDatas()`, then `cardAdapter.notifyDataSetChanged()`. | Each type may appear once, and medium cards must pair. Only `notifyDataSetChanged` is safe: a second holder for the same card type crashes (`notifyItemChanged`, setting the adapter again). |
| Add or replace an all-apps tile | Post-hook `AppUtils.generateApp` or the private `AllAppDataManager.loadData` to insert into `mMainAllApps`, then call `AllAppDataManager.reload()` on the UI thread. Set icon and label in post-hooks of `AllAppAdapter.updateItemTheme` and `onBindViewHolder`. | A missing name resource crashes binding. A custom subtype needs an interceptor (see the unknown-subtype note above). |
| Add a pager page | Add a plain androidx `Fragment` with a marker argument to the pager adapter's `mFragments`, call `notifyDataSetChanged()`, and return the custom view from `onCreateView` for that marker. | Not through `getPageFragments` (it casts to `BaseFragment`). Swiping is off, so the page is opened with `setCurrentItem`. |
| Hide page 2 | Make the adapter's `getCount` return 1, or keep `showAllApp` false and leave `mainApps` assigned. | Controls' default `mainApps` assignment already keeps users off page 2 unless they hold the button. |

**What undoes an override.**

- Theme changes call every card's `updateTheme` twice and then rebind all holders. That rebinding replaces the app card's tile listeners.
- Locale changes reset labels.
- A screen-lift change re-inflates the map widget when its surface was destroyed.
- The seat card repaints 3 s after a tap, and the media tabs hide after 5 s.
- Detaching a card unregisters its listeners. Detaching the media card also posts a sticky `cardSate=false` event to the island app.
- Activity recreation and a launcher process restart drop instance-level changes; class-level hooks survive.

## Not established

- The live content of page 2, the lowered-screen layout and the pull-down panels. Only the first page was on screen while the unit was connected.
- Which top-panel layout this unit inflates (it depends on NVRAM item 43 and `ro.build.product`), its stored vehicle-panel tiles, and the current `ignoreActivation` and lift property values.
- What the Baidu service does with a forwarded map tap.
- Whether this unit can report the lowered-screen state.
- Who reads the `@gesture(mode=[HORIZONTAL])` and `@hide_uc(value=[true])` content descriptions on card views. Probably the voice "say what you see" service; neither the launcher nor Hiboard parses them.
- Voice-driven card clicks.
- Two methods decompile with warnings and need bytecode checks before hooking: `AllAppBarView.onClick` and `AppUtils.generateHomeCardApp`.
