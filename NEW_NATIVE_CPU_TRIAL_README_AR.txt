مكتبة Whisper التجريبية CPU ARM64
تم وضع libtransvibe_native.so الجديدة مكان المكتبة السابقة داخل هذه الحزمة التجريبية.
تم بناء المكتبة في GitHub Actions run 43742006605.
المصدر: https://github.com/bingattban/TransVibe-Native-Builder/actions/runs/43742006605
لم يتم اختبار هذه المكتبة فعليا داخل تطبيق TransVibe على الهاتف ولا يوجد ضمان بتسريع الأداء.
احتفظ بالمشروع القديم وبالنماذج المثبتة. لا تحذف بيانات التطبيق.
احتفظ بمكتبات AAR الأصلية في app/libs لأنها غير مضمنة في الحزمة.
شغّل اختبارات JNI على الهاتف قبل اعتماد النسخة كتحديث أساسي.
SHA256 libtransvibe_native.so: c090407ec4950a1b4502c973bac36ab05dc20518e3be8759122c5458d52f7a5a
