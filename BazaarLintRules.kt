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
import org.jetbrains.uast.*
import org.jetbrains.uast.util.isKotlin

/**
 * رجیستری و دیتکتور یکپارچه برای قانون "عدم override متد plugins() در کلاس‌های غیر-final"
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

    private const val ALLOW_ANNOTATION = "com.farsitel.bazaar.lintrules.annotation.AllowPluginsOverride"

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

            node.methods
                .filter { it.name == "plugins" && context.evaluator.isOverride(it) }
                .forEach { method ->
                    val fix = buildSmartFix(node, method)
                    val message = buildSmartMessage(node)

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
            val psiClass = uClass.javaPsi ?: return false
            return PACKAGES.any { pkg ->
                BASE_CLASSES.any { base ->
                    context.evaluator.inheritsFrom(psiClass, "$pkg.$base", true)
                }
            }
        }

        private fun hasAllowAnnotation(uClass: UClass): Boolean {
            return uClass.findAnnotation(ALLOW_ANNOTATION) != null
        }

        private fun buildSmartMessage(uClass: UClass): String {
            val className = uClass.name ?: "کلاس"
            val suggestion = "کلاس را `final` کنید یا از `@AllowPluginsOverride` استفاده کنید."

            return """
                `$className` متد `plugins()` را override کرده اما `final` نیست.
                
                $suggestion
                از ارث‌بری چندلایه `plugins()` جلوگیری کنید.
            """.trimIndent()
        }

        private fun buildSmartFix(uClass: UClass, method: UMethod): LintFix? {
            val fixes = mutableListOf<LintFix>()

            // 1. افزودن `final` به کلاس
            if (!uClass.isFinal) {
                val sourcePsi = uClass.sourcePsi ?: return null
                val className = uClass.name ?: return null

                if (isKotlin(uClass)) {
                    val classKeyword = if (uClass.hasModifier(UastModifier.OPEN)) "open class" else "class"
                    fixes += fix()
                        .name("کلاس را final کنید")
                        .replace()
                        .pattern("""\b$classKeyword\s+$className\b""")
                        .with("final class $className")
                        .range(context.getLocation(sourcePsi))
                        .autoFix()
                        .build()
                } else {
                    val modifierList = uClass.javaPsi?.modifierList
                    if (modifierList != null) {
                        val currentText = modifierList.text ?: ""
                        fixes += fix()
                            .name("کلاس را final کنید")
                            .replace()
                            .range(context.getLocation(modifierList))
                            .text(currentText)
                            .with("final $currentText".trim())
                            .autoFix()
                            .build()
                    }
                }
            }

            // 2. حذف کلمه `override` (فقط در Kotlin)
            if (isKotlin(uClass) && method.hasModifier(UastModifier.OVERRIDE)) {
                val overrideToken = method.uastModifierList
                    ?.modifierNodes
                    ?.find { it.text == "override" }
                    ?: return@forEach

                fixes += fix()
                    .name("حذف override")
                    .replace()
                    .range(context.getLocation(overrideToken))
                    .text("override")
                    .with("")
                    .autoFix()
                    .build()
            }

            // 3. افزودن @AllowPluginsOverride
            fixes += createAddAnnotationFix(uClass)

            return if (fixes.isNotEmpty()) fix().composite(*fixes.toTypedArray()) else null
        }

        private fun createAddAnnotationFix(uClass: UClass): LintFix {
            val sourcePsi = uClass.sourcePsi ?: return fix().build()
            val file = context.psiFile ?: return fix().build()
            val packageName = file.packageName
            val importExists = file.findImportByName(ALLOW_ANNOTATION) != null
            val needImport = !importExists && packageName?.contains("com.farsitel.bazaar") != true

            val importFix = if (needImport) {
                fix()
                    .name("افزودن import")
                    .replace()
                    .range(context.getLocation(file))
                    .text("")
                    .with("import $ALLOW_ANNOTATION\n\n")
                    .autoFix()
                    .build()
            } else null

            val annotationFix = fix()
                .name("افزودن @AllowPluginsOverride")
                .replace()
                .range(context.getLocation(sourcePsi))
                .text(sourcePsi.text.lines().firstOrNull { it.trim().startsWith("class") || it.trim().startsWith("@") } ?: "")
                .with("@\( ALLOW_ANNOTATION\n \){sourcePsi.text.lines().first()}")
                .autoFix()
                .build()

            return if (importFix != null) {
                fix().composite(importFix, annotationFix)
            } else {
                annotationFix
            }
        }
    }
}

class PluginsOverrideTest : AbstractLintTest() {

    private val baseFragmentStub = kotlin("""
        package com.farsitel.bazaar.ui
        abstract class BaseFragment { open fun plugins(): List<Any> = emptyList() }
    """.trimIndent())

    private val badCase = kotlin("""
        package com.farsitel.bazaar.feature.payment
        
        import com.farsitel.bazaar.ui.BaseFragment
        
        class MyFragment : BaseFragment() {
            override fun plugins() = listOf(MyPlugin())
        }
    """.trimIndent())

    @Test
    fun `non-final class overriding plugins reports error`() {
        lint()
            .files(baseFragmentStub, badCase)
            .issues(PluginsOverrideInNonFinalClassDetector.ISSUE)
            .run()
            .expectErrorCount(1)
            .expectContains("MyFragment")
            .expectContains("final")
    }
} 
