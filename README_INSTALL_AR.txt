تعليمات تركيب تحديث TransVibe

1) انسخ مشروعك العامل على الهاتف احتياطياً.
2) فك ملفات هذه الحزمة في جذر مشروع TransVibe الحالي (حيث يوجد app وtools).
3) لا تلمس مكتباتك الحقيقية في app/libs أو app/src/main/jniLibs.
4) حذْف الملفات القديمة أدناه **اختياري لتنظيف المصدر**؛ لا تحتاج إلى حذفها لتعمل الأيقونة والترحيب، لكن حُذفت مراجعها من التنقل الجديد:
   - app/src/main/java/com/transvibe/app/presentation/components/TransVibeCanvasIcon.kt
   - app/src/main/java/com/transvibe/app/presentation/startup/StartupNavigationGate.kt
   - app/src/main/java/com/transvibe/app/presentation/startup/StartupScreen.kt
   - app/src/main/res/drawable/ic_launcher_background.xml
   - app/src/main/res/drawable/ic_launcher_foreground.xml
   - app/src/main/res/drawable/ic_launcher_monochrome.xml
   - app/src/main/res/mipmap-hdpi/welcome_app_icon.png
   - app/src/test/java/com/transvibe/app/presentation/startup/StartupNavigationGateTest.kt
   - checkthermal/Main.kt
   - checkthermal/thermal.jar
   - validation/PART5_ASR_AUDIT.md
   - validation/audit-imports.log
   - validation/audit-project.log
   - validation/gradle-offline.log
   - validation/kotlinc-domain.log
   - validation/part5-audit-imports.log
   - validation/part5-audit-project.log

مهم: لا تحذف موديلات الجهاز أو app/src/main/assets/vad أو طبقة AiResourceManager.
5) شغل :app:assembleDebug وثبت الناتج فوق النسخة الحالية دون مسح البيانات.
6) اختبر الأيقونة، الترحيب أول مرة، الرئيسية، جاهزية النماذج، وشريط التنقل السفلي.

هذه الحزمة تتضمن مكتبة Whisper الأصلية الموجودة في المشروع المرفق دون تعديل، لكنها لا تتضمن ملفات AAR المحلية. احتفظ بنسخك الاحتياطية ولا تحذف مكتبات التطبيق أو نماذج الهاتف.
