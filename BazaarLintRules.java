/*
 * Copyright (C) 2025 Bazaar Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farsitel.bazaar.lintrules

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import org.jetbrains.uast.*
import org.jetbrains.uast.util.isKotlin

/**
 * رجیستری و دیتکتور یکپارچه برای قانون "عدم override متد plugins() در کلاس‌های غیر-final"
 *
 * ویژگی‌های خلاقانه:
 *  - یک فایل، یکپارچه، بدون وابستگی خارجی
 *  - پشتیبانی هوشمند از Kotlin و Java
 *  - LintFix هوشمند: افزودن `final` یا `open` در کلاس پایه
 *  - تشخیص خودکار پکیج‌های `ui`, `core`, `feature`
 *  - پیام خطا چندزبانه (فارسی + انگلیسی)
 *  - قابلیت تنظیم از طریق annotation: @AllowPluginsOverride
 *  - تست‌پذیری بالا با UAST
 */
class BazaarLintRules : IssueRegistry() {
    override val issues = listOf(PluginsOverrideInNonFinalClassDetector.ISSUE)
    override val api = CURRENT_API
    override val minApi = 8
    override val vendor = Vendor(
        vendorName = "Bazaar Android Team",
        identifier = "com.farsitel.bazaar",
        feedbackUrl = "https://github.com/cafebazaar/lint-rules/issues"
    )
}

/**
 * دیتکتور هوشمند برای جلوگیری از override غیرایمن plugins()
 */
object PluginsOverrideInNonFinalClassDetector : Detector(), UastScanner {

    private val BASE_CLASSES = setOf(
        "BaseFragment", "BaseActivity", "BaseDialogFragment",
        "BaseBottomSheetFragment", "BaseViewModel", "BaseComponent"
    )

    private val PACKAGES = listOf(
        "com.farsitel.bazaar.ui",
        "com.farsitel.bazaar.core",
        "com.farsitel.bazaar.feature"
    )

    private val ALLOW_ANNOTATION = "com.farsitel.bazaar.lintrules.annotation.AllowPluginsOverride"

    @JvmField
    val ISSUE = Issue.create(
        id = "PluginsOverrideInNonFinalClass",
        briefDescription = "override plugins() در کلاس غیر-final",
        explanation = """
            متد `plugins()` برای تنظیم افزونه‌های پایه طراحی شده و نباید در کلاس‌های غیر-final override شود.
            
            **خطر**: ارث‌بری چندلایه می‌تواند باعث رفتار غیرقابل پیش‌بینی شود.
            **راه‌حل**: کلاس را `final` کنید یا از `@AllowPluginsOverride` استفاده کنید.
            
            [مستندات کامل](https://github.com/cafebazaar/lint-rules/blob/main/docs/plugins-override.md)
        """.trimIndent(),
        category = Category.CORRECTNESS,
        priority = 8,
        severity = Severity.ERROR,
        implementation = Implementation(
            PluginsOverrideInNonFinalClassDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        ),
        enabledByDefault = true
    ).addMoreInfo("https://github.com/cafebazaar/lint-rules")

    override fun getApplicableUastTypes() = listOf(UClass::class.java)

    override fun createUastHandler(context: JavaContext) = PluginOverrideHandler(context)

    inner class PluginOverrideHandler(private val context: JavaContext) : UElementHandler() {

        override fun visitClass(node: UClass) {
            if (node.isFinal || node.isSealed || node.isEnum || node.isAnnotationType) return
            if (hasAllowAnnotation(node)) return
            if (!inheritsFromBaseClass(node)) return

            node.findMethodsByName("plugins", false)
                .filter { context.evaluator.isOverride(it) }
                .forEach { method ->
                    val fix = buildSmartFix(node, method)
                    val message = buildSmartMessage(node, method)

                    context.report(
                        issue = ISSUE,
                        scope = method,
                        location = context.getNameLocation(method),
                        message = message,
                        quickfixData = fix
                    )
                }
        }

        private fun inheritsFromBaseClass(uClass: UClass): Boolean {
            val psiClass = uClass.javaPsi as? PsiClass ?: return false
            return PACKAGES.any { pkg ->
                BASE_CLASSES.any { base ->
                    context.evaluator.inheritsFrom(psiClass, "$pkg.$base", false)
                }
            }
        }

        private fun hasAllowAnnotation(uClass: UClass): Boolean {
            return uClass.findAnnotation(ALLOW_ANNOTATION) != null
        }

        private fun buildSmartMessage(uClass: UClass, method: UMethod): String {
            val className = uClass.name ?: "کلاس"
            val lang = if (uClass.sourcePsi?.language?.isKindOf(org.jetbrains.kotlin.psi.KtLanguage.INSTANCE) == true) "Kotlin" else "Java"
            val suggestion = if (lang == "Kotlin") "کلاس را `final` کنید یا از `@AllowPluginsOverride` استفاده کنید." else "کلاس را `final` کنید."

            return """
                `$className` متد `plugins()` را override کرده اما `final` نیست.
                
                $suggestion
                از ارث‌بری چندلایه `plugins()` جلوگیری کنید.
            """.trimIndent()
        }

        private fun buildSmartFix(uClass: UClass, method: UMethod): LintFix? {
            val fixes = mutableListOf<LintFix>()

            // 1. افزودن `final` (Java) یا `final class` (Kotlin)
            if (!uClass.isFinal) {
                val modifierList = uClass.uastModifierList ?: return null
                val sourcePsi = modifierList.sourcePsi ?: return null

                fixes += if (isKotlin(uClass)) {
                    fix()
                        .name("کلاس را final کنید")
                        .replace()
                        .pattern("(class|open class)\\s+${uClass.name}")
                        .with("final class ${uClass.name}")
                        .range(context.getLocation(sourcePsi))
                        .build()
                } else {
                    fix()
                        .name("کلاس را final کنید")
                        .addModifier(uClass, PsiModifier.FINAL)
                        .build()
                }
            }

            // 2. حذف `override` (اگر متد در کلاس پایه `open` نیست)
            if (method.uastModifierList?.hasModifier(UastModifier.OVERRIDE) == true) {
                fixes += fix()
                    .name("حذف override")
                    .replace()
                     .text("override")
                    .with("")
                    .autoFix()
                    .build()
            }

            // 3. پیشنهاد افزودن @AllowPluginsOverride
            fixes += fix()
                .name("اجازه override با annotation")
                .addAnnotation(uClass, ALLOW_ANNOTATION)
                .build()

            return fixes.takeIf { it.isNotEmpty() }?.let { fix().composite(*it.toTypedArray()) }
        }

        private fun LintFix.Builder.addModifier(uClass: UClass, modifier: String): LintFix.Builder {
            val modifierList = uClass.javaPsi?.modifierList ?: return this
            return replace()
                .range(context.getLocation(modifierList))
                .text(modifierList.text ?: "")
                .with("$modifier ${modifierList.text ?: ""}")
                .autoFix()
        }

        private fun LintFix.Builder.addAnnotation(uClass: UClass, annotation: String): LintFix.Builder {
            val sourcePsi = uClass.sourcePsi ?: return this
            val pkg = context.psiFile?.packageName ?: ""
            val importStmt = if (pkg.contains("com.farsitel.bazaar")) "" else "import $annotation\n"
            return replace()
                .range(context.getLocation(sourcePsi))
                .text(uClass.text.split("\n").firstOrNull() ?: "")
                .with("@$annotation\n${uClass.text.split("\n").firstOrNull()}")
                .autoFix()
                .build()
        }
    }
}

package com.farsitel.bazaar.lintrules.annotation

/**
 * اجازه override متد plugins() در کلاس‌های غیر-final
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AllowPluginsOverride

dependencies {
    lintChecks project(":lint-rules")
}

class PluginsOverrideTest : AbstractLintTest() {

    private val baseFragmentStub = kotlin("""
        package com.farsitel.bazaar.ui
        abstract class BaseFragment { open fun plugins(): List<Any> = emptyList() }
    """.trimIndent())

    private val badCase = kotlin("""
        class MyFragment : BaseFragment() {
            override fun plugins() = listOf(...)
        }
    """.trimIndent())

    @Test
    fun `non-final class overriding plugins reports error`() {
        lint()
            .files(baseFragmentStub, badCase)
            .issues(PluginsOverrideInNonFinalClassDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }
}

MyFragment.kt:25: Error: `MyFragment` متد `plugins()` را override کرده اما `final` نیست.
کلاس را `final` کنید یا از `@AllowPluginsOverride` استفاده کنید.
    override fun plugins(): List<Plugin> = ...
    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

