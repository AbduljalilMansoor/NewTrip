# New Trip

تطبيق لشاشة جيتور: اضغط **START** في بداية الرحلة و **END** في نهايتها،
فيعطيك تقريراً مقسّماً حسب **نمط القيادة** (Sport / Eco / Normal / Others / **Park**)
وحسب **الكهرباء (EV)** أو **المحرك (Engine)**.

## ما يسجله
| EV | Engine |
|---|---|
| Duration, Charge start/end, Re-charged, kWh consumed, Distance, Average speed | Duration, Fuel start/end, Refueled, Fuel consumed, Distance, Average speed |

## مصادر البيانات
| المعلومة | الإشارة |
|---|---|
| نمط القيادة | `HCU_DRIVEMODE_JT` (0=ECO، 1=NORMAL، 2=SPORT، الباقي Others) |
| كهرباء أو محرك | `ENGINESPEED` ≥ 350 rpm = محرك |
| السرعة والمسافة | `VEHICLESPEEDVSOSIG` |
| الكهرباء المستهلكة والمسترجعة | `BMS_44_PACKPOWERREALTIME` |
| البنزين المستهلك | `FUELROLLINGCOUNTER` (النبضة = 0.0000788519 لتر) |
| البطارية % | `BMS_SOCLIGHT` |
| الوقود % | VHAL `FUEL_LEVEL / INFO_FUEL_CAPACITY` |

الإشارات السريعة تُستقبل لحظياً عبر مستمع Autolink (`AlListener`)، والباقي يُقرأ كل ثانية.

## هيكل المشروع
- `autolink-stubs/` واجهة فارغة لمكتبة Autolink، للبناء فقط ولا تدخل في التطبيق.
- `CarBridge` الاتصال بالسيارة.
- `TripSession` تجميع الرحلة.
- `TripService` خدمة تعمل أثناء الرحلة فقط.
- `MainActivity` الشاشة الرئيسية.
- `ReportActivity` التقرير.

## المعايرة (أول رحلة)
من زر **⚙ تشخيص**:
- تأكد أن "المستمع اللحظي" مفعّل، وأن نبضات عداد الوقود تزيد مع عمل المحرك.
- إذا ظهرت "الكهرباء المسترجعة" تزيد أثناء القيادة على الكهرباء، اضغط **عكس اتجاه البطارية**.

## الصلاحيات (اختياري، للمسار الاحتياطي)
```
adb shell pm grant com.newtrip.app android.car.permission.CAR_SPEED
adb shell pm grant com.newtrip.app android.car.permission.CAR_ENERGY
adb shell pm grant com.newtrip.app android.car.permission.CAR_MILEAGE
adb shell pm grant com.newtrip.app android.car.permission.CAR_POWERTRAIN
```

## الملفات
كل رحلة تُحفظ في:
`/sdcard/Android/data/com.newtrip.app/files/trips/trip_<وقت>.json`

## القادم
- المرحلة 2: لوحة التقرير بالتصميم الكامل.
- المرحلة 3: تصدير PDF و CSV.


---

## Park
- خانة **Park** تُحتسب كلما كان الغِيار **P** (`VCU_1_G_PRNDGEARACT = 1`)، بغض النظر عن نمط القيادة. الوقوف عند إشارة والغِيار D لا يُحتسب.
- داخلها تبقى القسمة EV / Engine: كهرباء المكيف والأجهزة، أو محرك يعمل للشحن/التبريد (وقود + kWh مسترجعة).
- رحلة بلا مسافة ومدتها أكثر من دقيقة تُحفظ لتسجيل استهلاك الوقوف.

## البناء (APK)
1. أنشئ مستودعاً جديداً على GitHub وارفع محتويات هذا المجلد.
2. تبويب **Actions** ← يعمل تلقائياً **Build New Trip APK** (أو شغّله يدوياً).
3. حمّل `NewTrip.apk` من **Releases** أو من Artifacts.
4. (اختياري) أضف سرّ `SIGNING_KEY` (ملف jks بصيغة base64) ليتحدّث التطبيق فوق النسخة السابقة؛ بدونه يُولَّد مفتاح مؤقت لكل بناء فيلزم حذف النسخة القديمة قبل تثبيت الجديدة.
5. التحديث من داخل التطبيق يعمل تلقائياً: اسم المستودع (owner/repo) يُحقن وقت البناء من GitHub Actions، فلا تعديل يدوي. (لصفحة التثبيت فعّل Settings ← Pages ← Source: GitHub Actions؛ وبدونها يبقى بناء الـ APK والـ Release يعملان.)

التطبيق مستقل تماماً: معرّفه `com.newtrip.app`، ويمكن تثبيته بجانب أي تطبيق آخر.


## حجم النافذة
New Trip يعمل كنافذة عائمة بحجم **70% من الشاشة** (في الوسط) وكل المقاسات داخلها أصغر بـ **30%** (الخطوط والعدادات والجداول…). لتغيير النسبة عدّل `FACTOR` في `UiScale.java` (1.0 = بلا تصغير).
