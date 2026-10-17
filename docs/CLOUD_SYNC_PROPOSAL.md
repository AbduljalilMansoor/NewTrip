# New Trip: Cloud Export & Sync Proposal

Status: **proposal** (nothing in this document is built yet, except the foundations listed in section 7).

## 1. Goal

Let a driver keep trips safely off the car screen, see them on a phone or computer, and survive a head-unit reset, without
making the app depend on a service that can disappear or that holds data the user did not choose to share.

## 2. Constraints that shape the design

| Constraint | Consequence |
|---|---|
| The car screen has no practical keyboard | No typing passwords. Sign-in must happen on the user's phone (code or QR). |
| Chery/Jetour head units usually have **no Google Play Services** | Google Sign-In and Firebase client SDKs are unavailable. Use plain OAuth 2.0 over HTTPS. |
| Connectivity is intermittent (phone hotspot, Wi-Fi at home) | Uploads must be queued, resumable and retried; never block a trip. |
| Trips are small (a few hundred KB of JSON) | A simple file upload per trip is enough. No database needed on the device. |
| Data is personal (times, speeds, consumption patterns) | Explicit consent, minimal data, encryption in transit, deletion on request. |
| App is sideloaded and updated from GitHub | Prefer a design with **no server we must operate** for the first release. |

## 3. Options compared

| | A. Share / USB (exists) | B. User's own cloud drive | C. Our own backend | D. Phone companion app |
|---|---|---|---|---|
| What | CSV/PDF → Share, Save to Downloads | App uploads trips to the user's Google Drive / OneDrive app folder | App uploads to a server we run (e.g. object storage + small API) | A phone app pairs with the car and pulls trips |
| Operating cost | none | **none** (user's storage) | hosting + maintenance | two apps to build and ship |
| Who owns the data | user | **user** | us (custodian) | user |
| Web dashboard / multi-car | no | no (files only) | **yes** | partly |
| Works without Play Services | yes | **yes** (REST + OAuth device flow) | yes | needs pairing transport |
| Effort | done | **small–medium** | medium–large | large |
| Main risk | manual | provider policy/quota changes | security, privacy, uptime, cost | pairing reliability |

## 4. Recommendation: three phases

**Phase 1 (done): manual export.** CSV and PDF, Share and Save to Downloads, auto-export after each trip.

**Phase 2 (recommended next): "Back up to my cloud" using the user's own drive.**
Zero hosting cost, the user keeps ownership, and it fits a head unit with no Play Services.

1. Settings → *Cloud backup* → choose Google Drive or OneDrive.
2. The screen shows a short code and a QR code (OAuth 2.0 *Device Authorization Grant*, RFC 8628). The user approves on their phone.
3. The app stores the refresh token encrypted with an Android Keystore key and asks for the **narrowest scope**: an app-private folder
   (Google: `drive.appdata` or `drive.file`; Microsoft: `Files.ReadWrite.AppFolder`). The app can then see only the files it created.
4. After each trip ends (and when the car is switched off, on Wi-Fi/hotspot), the sync engine uploads that trip.

**Phase 3 (optional, only if there is demand): hosted account + web dashboard.**
Needed only for multi-car households, fleet owners or a shared web view. Adds a small API in front of object storage, user accounts,
and the privacy obligations of being a data custodian, so it should be a deliberate decision, not a default.

## 5. Sync design (applies to phases 2 and 3)

**Unit of sync = one finished trip, immutable.** Every trip has a stable UUID (`TripSession.id`, already written to the trip JSON and to every CSV row as *Trip ID*).

```
/NewTrip/
  device.json                      { "installId": "<random UUID>", "appVersion": "...", "schema": 2 }
  trips/2026/10/<tripId>.json.gz   the full trip (cells, tiers, grid, 30 s windows, events)
  index.csv                        one summary row per trip (the same columns as trips.csv)
  deleted/<tripId>                 tombstone, so a delete on one device is honoured everywhere
```

- **Upload queue:** a local list of trip IDs not yet confirmed uploaded. A trip is marked done only after the provider returns success and the size/hash matches.
- **Idempotent:** the object name is the trip ID, so a retry or a second device can never create a duplicate.
- **Resumable + backoff:** retry with exponential backoff (1 min → 6 h); resumable upload sessions for larger files; stop on authentication errors and show a banner.
- **Policy:** upload only on unmetered/hotspot-approved networks (setting), never during an active trip, and at most N files per run to keep the head unit responsive.
- **Conflicts:** none by design (immutable trips, append-only index). Edits to a trip do not exist; deletes use tombstones.
- **Restore:** a new install lists `trips/`, downloads missing IDs, and rebuilds the local list. This also gives migration between cars.
- **Versioning:** the trip JSON carries `v`; readers ignore unknown fields so older apps keep working.
- **Compression:** gzip (trip JSON compresses roughly 5–8×). A 100 MB local budget then holds several times more trips in the cloud.

## 6. Privacy and security

- **What is uploaded:** timestamps, speeds, battery and fuel figures, temperatures, mode and SOC settings. The app does **not** record GPS location or the VIN. Keep it that way; if location is ever added, it must be a separate opt-in.
- **Identity:** a random install ID, not the VIN, name or phone number.
- **Consent:** off by default, an explicit screen that lists what is sent, and a one-tap *Disconnect and delete cloud copy*.
- **In transit:** TLS only. **At rest on the device:** tokens in Keystore-backed encrypted storage.
- **Optional client-side encryption:** AES-256-GCM with a key derived from a user passphrase, so even the cloud provider cannot read trips (trade-off: a lost passphrase means lost data).
- **Regulation:** with a hosted backend (phase 3) we become a data controller. Bahrain's Personal Data Protection Law (and GDPR for EU users) would require a lawful basis (consent), a privacy notice, access/export and deletion on request, breach handling and data-retention limits. Phase 2 avoids most of this because the data stays in the user's own account.

## 7. What is already in place

- Stable trip IDs (`TripSession.id`, legacy trips get `legacy-<startTime>`).
- CSV export with a *Trip ID* column in every table, plus PDF; `ExportProvider` for sharing.
- Storage monitor with a configurable limit and alerts (the same budget logic can drive "upload then free space").
- Background post-trip hook (`PostTrip`) where the upload step would run, and a reliable car-off signal (`FLZCU_9_POWERMODE`) for "upload when the car is switched off".
- Update channel already uses GitHub, so no new infrastructure is needed to ship the feature.

## 8. Estimated work (phase 2, one provider first)

| Item | Effort |
|---|---|
| Device-code sign-in screen with QR, token storage | 2–3 days |
| Provider client (Drive **or** OneDrive): create folder, upload, list, download | 2–3 days |
| Sync queue, retry/backoff, network policy, settings UI | 3–4 days |
| Restore flow + tombstones + tests | 2–3 days |
| Second provider | 2 days |

Main technical risk: provider rules for device-flow scopes and app verification can change, so confirm them (and any app-review requirement for Drive scopes) before committing to a provider.

## 9. Decisions needed

1. Which provider first: Google Drive or OneDrive? (Most Arabic-market users have both; Drive is the more common default.)
2. Is a web dashboard or a shared multi-car view wanted? If not, stop at phase 2.
3. Should client-side encryption be offered in the first release or later?
4. Retention: keep everything, or keep the last N months in the cloud?

---

### ملخص بالعربية

الهدف: نسخ الرحلات خارج شاشة السيارة بدون خادم نشغّله نحن. **التوصية:** المرحلة 1 (متوفرة): تصدير CSV وPDF والمشاركة. المرحلة 2 (التالية): «نسخ احتياطي إلى سحابتي» عبر Google Drive أو OneDrive باستخدام تسجيل الدخول بالرمز/QR من الهاتف (لأن الشاشة بلا لوحة مفاتيح وبلا خدمات Google Play)، مع مجلد خاص بالتطبيق وأقل صلاحيات ممكنة، ورفع كل رحلة مرة واحدة بمعرّف ثابت بعد انتهائها وعلى شبكة Wi-Fi/هوتسبوت، مع إعادة المحاولة. المرحلة 3 (اختيارية): حساب وخادم ولوحة ويب، وتستلزم التزامات خصوصية أكبر. البيانات لا تتضمن موقع GPS ولا رقم الهيكل VIN، والميزة معطّلة افتراضياً وبموافقة صريحة.
