بما أنك تريد الاحتفاظ بالأنيميشن، فالحل هو إنشاء ملفات الأنيميشن المفقودة بدلاً من حذفها. سأزودك بمحتويات الملفات الأربعة المطلوبة مع تعليمات واضحة.

---

📁 الخطوة 1: إنشاء مجلد anim (إذا لم يكن موجوداً)

اذهب إلى مسار المشروع:

```
app/src/main/res/
```

وأنشئ مجلداً جديداً باسم anim (إذا لم يكن موجوداً بالفعل).

---

🎬 الخطوة 2: إنشاء ملفات الأنيميشن الأربعة

1. slide_in_left.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android">
    <translate
        android:duration="250"
        android:fromXDelta="-100%"
        android:toXDelta="0%" />
    <alpha
        android:duration="250"
        android:fromAlpha="0.8"
        android:toAlpha="1.0" />
</set>
```

2. slide_out_right.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android">
    <translate
        android:duration="250"
        android:fromXDelta="0%"
        android:toXDelta="100%" />
    <alpha
        android:duration="250"
        android:fromAlpha="1.0"
        android:toAlpha="0.8" />
</set>
```

3. slide_in_right.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android">
    <translate
        android:duration="250"
        android:fromXDelta="100%"
        android:toXDelta="0%" />
    <alpha
        android:duration="250"
        android:fromAlpha="0.8"
        android:toAlpha="1.0" />
</set>
```

4. slide_out_left.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android">
    <translate
        android:duration="250"
        android:fromXDelta="0%"
        android:toXDelta="-100%" />
    <alpha
        android:duration="250"
        android:fromAlpha="1.0"
        android:toAlpha="0.8" />
</set>
```

---

✅ الخطوة 3: إبقاء استدعاءات الأنيميشن كما هي

لا تحتاج لحذف أي شيء، فقط تأكد من وجود الملفات أعلاه في res/anim/.

ملاحظة: الأنيميشن أعلاه تضيف تأثير تلاشي (alpha) بجانب الانزلاق لتعطي مظهراً أكثر سلاسة.

---

📦 الخطوة 4: تنظيف وإعادة بناء المشروع

· Build → Clean Project
· Build → Rebuild Project

ثم أعد تثبيت التطبيق. سيعمل الآن بالأنيميشن المطلوبة دون انهيار.

---

🔍 ملاحظة بخصوص TransitionManager

إذا استمر التطبيق بالتعطل بعد إضافة الأنيميشن، فالمشكلة قد تكون في TransitionManager.beginDelayedTransition التي أضفتها في الإصدار الجديد. جرب حذف هذه السطور مؤقتًا (من زر التبويبات وزر إغلاق البحث) لأنها ليست ضرورية لعمل الأنيميشن الأساسية.

إذا أردت الاحتفاظ بها، تأكد من استيراد:

```kotlin
import android.transition.TransitionManager
```

وليس أي مكتبة أخرى.