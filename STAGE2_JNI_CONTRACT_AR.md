# TransVibe — المرحلة الثانية: عقد JNI وتجهيز إعادة بناء ARM64 المرجعية

**التاريخ:** 15 نوفمبر 2026  
**النطاق:** المرحلة الثانية فقط من خطة التنفيذ. **لم تُستبدل المكتبة الأصلية، ولم يكتمل الإصلاح الثنائي.**

## 1) نقطة البداية وما ثبت

- المصدر: `TransVibe_Performance_CancelHome_COTG_2026-11-15.zip`؛ لا توجد بيانات Git داخل الحزمة؛ لم يحدث أي رفع أو دمج.
- الكود الأصلي `app/src/main/cpp/native-lib.cpp` يضم جدول `RegisterNatives` لتسع وظائف، بينما ثنائي ARM64 المرفق يحتوي أسماء ثماني وظائف فقط.
- اسم `nativeBackendForContext` غير موجود ضمن السلاسل المنتهية بصفر في `app/src/main/jniLibs/arm64-v8a/libtransvibe_native.so`. هذه قرينة قوية على اختلاف نسخة المصدر عن الثنائية؛ الاختبار الفعلي النهائي على Android ضروري.
- SHA-256 للمكتبة المرجعية القديمة: `883ba32b764037277cd4f2bc5ce32ce29b7aa504f13a7439f9c61f92ab2bf648`، الحجم 66,818,944 بايت، ELF64 AArch64 ET_DYN.
- اسم `JNI_OnLoad` موجود في جدول الرموز الديناميكية. الثنائية تعتمد `libvulkan.so` وتضم `ggml_backend_vk_init`، **لكن ذلك لا يثبت تشغيل GPU أثناء الاستدلال**.
- كود C++ الحالي ينشئ سياق Whisper باستخدام `params.use_gpu = false`؛ أما المكتبة الموجودة فقد بنيت من مصدر أو خيارات غير موثقة بالكامل. وجود `whisper_transvibe_active_backend` في الشيفرة الموردة هو تعديل محلي فوق الشجرة الموصوفة بأنها v1.9.4، ويجب إرفاق بصمة المصدر في أي بناء جديد.
- في Kotlin تعيد `runtimeBackend` القيمة `UNVERIFIED_REBUILD_GPU_LIBRARY` عند غياب الواجهة. لا يجوز استخدام هذه القيمة دليلا على GPU أو CPU.

## 2) الاختبارات أولا: الاختبار الفاشل قبل الإصلاح

1. أُنشئ `tools/verify_native_jni_contract.py` للتحقق من تسع تواقيع جدول C++ ومقابلات Kotlin وELF ARM64 ووجود أسماء التسجيل داخل الثنائية.
2. نُفذ على الملف الحقيقي: **FAIL** — الدالة `nativeBackendForContext` مفقودة؛ رمز الخروج `1`.
3. أُنشئ `tools/test_native_jni_contract.py`: خمس حالات للتحقق من صحة الكاشف، والتواقيع، وELF، والكشف عن تسجيل ناقص؛ **5/5 PASS** على المضيف. هذه اختبارات لأداة الفحص ولا تعني أن الثنائية سليمة.
4. أُنشئ `WhisperNativeContractInstrumentationTest.kt` لفحص تحميل المكتبة واستدعاءات الوظائف التسع عبر JNI وإرجاع أخطاء `INVALID_HANDLE` المنظمة. يستخدم انعكاس Java لفحص `nativeBackendForContext` مباشرة، لأن Kotlin تخفي `UnsatisfiedLinkError` في الواجهة العامة. يوجد اختبار إضافي **اختياري** لتحميل نموذج Tiny مثبت مسبقا إن تم تمرير مساره في `transvibeTestWhisperModelPath`؛ لا ينشئ أو يحذف أي نموذج.
5. اختبارات Android تلك **لم تُشغل** لعدم توفر جهاز/بيئة Android متصلة بهذا التنفيذ. وهي متوقعة الفشل على الثنائية القديمة عند فحص backend.

## 3) تجهيز بناء CPU مرجعي، دون استبدال الثنائية المستقرة

أُنشئ `tools/build_native_arm64_cpu_reference.sh` للبناء باستخدام Android NDK متوافق مع المضيف، CMake، Ninja، مع الخيارات:

- `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-26`, `CMAKE_BUILD_TYPE=Release`
- `GGML_VULKAN=OFF`, `GGML_OPENMP=OFF`, `TRANSVIBE_ARMV82_DOTPROD_FP16=OFF`
- إنتاج `libtransvibe_native.so` في `build/native-arm64-cpu-reference/` **وليس فوق مكتبة التطبيق الحالية**.
- تشغيل فحص JNI الثابت على الثنائية الجديدة، ثم كتابة `transvibe_native_provenance.txt` بالبصمات والنسخ وخيارات البناء.

**نتيجة التجربة هنا:** لم يتم إنشاء `.so` جديدة: `ANDROID_NDK_HOME` غير متوفر. هذا حاجز بيئي مثبت، ولا يجوز التحايل عليه بنسخة SO أو AAR وهمية.

لتجربة البناء في بيئة **مضيفة متوافقة مع NDK**:

```bash
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
bash tools/build_native_arm64_cpu_reference.sh
python3 tools/verify_native_jni_contract.py --library build/native-arm64-cpu-reference/libtransvibe_native.so
```

**ملاحظة خاصة بـCode On The Go:** بعض توزيعات Android NDK تتضمن أدوات Linux x86_64 لا تعمل مباشرة على هاتف ARM64؛ لا تستخدمها وكأنها أداة Android ARM64 أصلية. يجب اختيار بيئة بناء Native مدعومة فعلا قبل إعادة إنشاء المكتبة.

## 4) الفحوص المنفذة ونتائجها

| الفحص | النتيجة |
|---|---|
| `bash tools/audit_project.sh` | PASS |
| `python3 tools/audit_imports.py` | PASS |
| `python3 tools/verify_regression_guards.py` | PASS |
| `python3 tools/test_native_jni_contract.py` | PASS — خمس حالات |
| `python3 tools/verify_native_jni_contract.py` على SO الحالية | **FAIL متوقع — غياب nativeBackendForContext** |
| `bash tools/build_native_arm64_cpu_reference.sh` | BLOCKED — Android NDK غير موجود |
| اختبارات الجهاز `connectedDebugAndroidTest` | NOT RUN — بيئة الجهاز غير متاحة |
| Gradle `testDebugUnitTest`, `lintDebug`, `assembleDebug` | NOT RUN — لا Gradle 9.6.1 محليا ولا AAR الأصلية |
| قياسات ASR/RTF والحرارة/الذاكرة | NOT RUN — لا اختبار هاتف |

## 5) الملفات المضافة وحدود التغيير

- `tools/verify_native_jni_contract.py` — حارس عقد JNI مستقل.
- `tools/test_native_jni_contract.py` — اختبارات وحدة لأداة الحراسة.
- `tools/build_native_arm64_cpu_reference.sh` — بناء CPU منفصل وقابل للتتبع عند توفر NDK.
- `app/src/androidTest/java/com/transvibe/app/nativebridge/whisper/WhisperNativeContractInstrumentationTest.kt` — فحوص JNI الفعلية عند تشغيل اختبارات Android.
- `STAGE2_JNI_CONTRACT_AR.md` — هذا التقرير.

**لم تتغير** ملفات Kotlin الإنتاجية، ولا `native-lib.cpp`، ولا `app/build.gradle.kts` أو `settings.gradle.kts`، ولا `libtransvibe_native.so` أو ملفات الواجهات أو قاعدة البيانات. لا يتأثر أداء التطبيق الحالي أو بيانات المستخدم بسبب هذه الإضافات وحدها.

## 6) شروط الاعتماد التالية — إلزامية

1. بناء نسخة CPU جديدة من المصدر المثبت باستخدام NDK مناسب، واجتياز `verify_native_jni_contract.py` عليها.
2. اختبار كل وظائف JNI التسع على هاتف ARM64، بما فيها `nativeBackendForContext`، والتأكد من عدم وجود `UnsatisfiedLinkError`.
3. تمرير نموذج Tiny صحيح عبر حجة الاختبار `transvibeTestWhisperModelPath`، والتحقق من نتيجة backend الفعلية (`CPU|...` في بناء CPU المرجعي).
4. قياس Base/Small على المقطع المرجعي، وثبات النص والتوقيت، وفحص إلغاء الاستدلال في أول ووسط ونهاية المهمة، والتحقق من عدم تسرب الموارد.
5. اجتياز اختبارات الوحدة وlint وبناء Debug، ثم اختبارات Release المناسبة، وتوثيق SHA الثنائية النهائية.
6. عدم استبدال المكتبة القديمة على هاتف المستخدم قبل استيفاء كل ما سبق وتوفير آلية تراجع.

**قرار المرحلة:** غير مكتملة / غير معتمدة. أمكن تجهيز اختبارات ووسائل إعادة بناء، لكن المكتبة الجديدة واختبارات Android المطلوبة غير متاحة في هذه البيئة. **لا يُبدأ تنفيذ المرحلة الثالثة المعتمدة على محرك JNI موثق قبل إغلاق هذا العائق.**
