بعد تعديل ملف `layout_main_menu.xml` وإضافة العناصر المطلوبة، تبقى لديك ثلاث تعديلات ضرورية في ملف `MainActivity.kt` لضمان نجاح البناء. إليك القائمة خطوة بخطوة:

---

### ✅ الخطوات المتبقية

#### 1. تعديل دالة `initViews()` لإصلاح مشكلة `tabsHeaderContainer`

افتح ملف `MainActivity.kt` واذهب إلى دالة `initViews()` (حوالي السطر 160). ابحث عن هذا الكود:

```kotlin
// قد لا يكون هذا العنصر موجودًا في التخطيط القديم، نتحقق منه
tabGroupsContainer = findViewById<LinearLayout?>(R.id.tabGroupsContainer)?.apply {
    // إذا كان موجودًا فسنستخدمه، وإلا ننشئه برمجيًا (fallback)
} ?: run {
    // fallback: إنشاء LinearLayout أفقي لإظهار المجموعات
    LinearLayout(this).also {
        it.id = R.id.tabGroupsContainer
        it.orientation = LinearLayout.HORIZONTAL
        // سنضيفه إلى الواجهة المناسبة (على سبيل المثال أعلى tabsRecycler)
        val parent = tabsOverlay.findViewById<LinearLayout>(R.id.tabsHeaderContainer)
        parent?.addView(it, 0)  // نضعه قبل العناصر الأخرى
    }
}
```

**استبدله بالكود التالي** (لأن العنصر موجود بالفعل في XML ولن نحتاج لإنشائه):

```kotlin
tabGroupsContainer = findViewById<LinearLayout>(R.id.tabGroupsContainer)
```

> **شرح:** في ملف `activity_main.xml` لديك `LinearLayout` بمعرّف `tabGroupsContainer` داخل `HorizontalScrollView`، وهو جاهز للاستخدام. لا داعي للـ fallback.

---

#### 2. إصلاح خطأ `getBoolean` (السطر 729)

اذهب إلى دالة `shouldInterceptRequest` (داخل `createNewWebView`). ابحث عن السطر التالي:

```kotlin
if (prefsManager.getBoolean("disable_intercept", false)) return null
```

**استبدله بـ**:

```kotlin
if (prefsManager.sharedPreferences.getBoolean("disable_intercept", false)) return null
```

> **ملاحظة:** إذا كان كلاس `PrefsManager` لا يعرض `sharedPreferences` كخاصية عامة، يمكنك إضافة دالة `getBoolean` إلى الكلاس بدلًا من ذلك (انظر الخيار البديل في الرد السابق).

---

#### 3. إضافة استيراد `WindowInsetsCompat` (السطران 857 و 860)

في أعلى ملف `MainActivity.kt` أضف هذا السطر مع بقية الاستيرادات:

```kotlin
import androidx.core.view.WindowInsetsCompat
```

ضعها مثلاً بعد `import androidx.core.view.WindowInsetsControllerCompat`.

---

### 🚀 بعد تطبيق هذه التعديلات

سيكون كود `MainActivity.kt` خاليًا من الأخطاء وسيتم بناء التطبيق بنجاح. يمكنك الآن تشغيل:

```bash
./gradlew assembleDebug
```

أو الضغط على **Run** في Android Studio.

---

### 📌 ملخص سريع

| الملف | الإجراء المطلوب |
|-------|----------------|
| `layout_main_menu.xml` | ✅ تم تحديثه (أضفنا `menuShare` و `menuClearData`) |
| `MainActivity.kt` | 🔧 **عدّل `initViews()`** – احذف كتلة `?: run { ... }` |
| `MainActivity.kt` | 🔧 **أصلح `getBoolean`** – استخدم `prefsManager.sharedPreferences.getBoolean(...)` |
| `MainActivity.kt` | 🔧 **أضف الاستيراد** `import androidx.core.view.WindowInsetsCompat` |

بعد إتمام هذه الخطوات الثلاث، لن تظهر أي من الأخطاء الستة المذكورة في السجلات. إذا واجهت أي مشكلة أخرى، أرسل لي رسالة الخطأ الجديدة وسنساعدك فورًا.