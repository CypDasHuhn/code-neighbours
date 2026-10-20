package dev.codenb.frontend

import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.SourceSpan
import dev.codenb.core.EntitySymbolKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinEntityDiscoveryTest {
    private val graphId = GraphId("graph")
    private val fileUri = "file:///workspace/app/Example.kt"

    @Test
    fun discoversTopLevelDeclarationsWithTargetSymbolKinds() {
        val source =
            """
            package app

            data class Data(val value: Int)

            operator fun Data.plus(other: Data): Data = this

            interface Service

            object Registry

            enum class Color {
                RED,
                GREEN,
            }

            typealias Name = String

            val answer: Int = 42
            """.trimIndent()
        val symbols =
            listOf(
                ds(
                    source,
                    "Data",
                    SymbolKind.Struct,
                    start = "data class Data",
                    children = listOf(ds(source, "value", SymbolKind.Variable, fromIndex = source.indexOf("class Data"))),
                ),
                ds(source, "plus", SymbolKind.Operator, start = "operator fun Data.plus"),
                ds(source, "Service", SymbolKind.Interface, start = "interface Service"),
                ds(source, "Registry", SymbolKind.Object, start = "object Registry"),
                ds(
                    source,
                    "Color",
                    SymbolKind.Enum,
                    start = "enum class Color",
                    children =
                        listOf(
                            ds(source, "RED", SymbolKind.EnumMember),
                            ds(source, "GREEN", SymbolKind.EnumMember),
                        ),
                ),
                ds(source, "Name", SymbolKind.Class, start = "typealias Name"),
                ds(source, "answer", SymbolKind.Property, start = "val answer"),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(
            listOf(
                "app.Data",
                "app.Data.value",
                "app.plus",
                "app.Service",
                "app.Registry",
                "app.Color",
                "app.Color.RED",
                "app.Color.GREEN",
                "app.Name",
                "app.answer",
            ),
            nodes.map { it.qualifiedName },
        )
        assertKind(nodes[0], EntitySymbolKind.CLASS)
        assertKind(nodes[1], EntitySymbolKind.PROPERTY)
        assertKind(nodes[2], EntitySymbolKind.FUNCTION)
        assertKind(nodes[3], EntitySymbolKind.INTERFACE)
        assertKind(nodes[4], EntitySymbolKind.OBJECT)
        assertKind(nodes[5], EntitySymbolKind.ENUM)
        assertKind(nodes[6], EntitySymbolKind.ENUM_MEMBER)
        assertKind(nodes[8], EntitySymbolKind.TYPE_ALIAS)
        assertTrue(nodes[8].signatureFingerprint?.contains("typealias Name = String") == true)
        assertTrue(nodes[8].signatureFingerprint?.contains("String") == true)
        assertTrue(nodes.all { it.fileUri == fileUri })
        assertTrue(nodes.all { it.parent == null })
    }

    @Test
    fun discoversMembersNestedTypesAndCompanions() {
        val source =
            """
            package app

            class Outer {
                fun run(input: Int): Int = input

                val computed: Int = 1

                class Nested

                object Companion

                companion object Factory {
                    fun create(): Outer = Outer()

                    val default: Outer = Outer()
                }
            }
            """.trimIndent()
        val symbols =
            listOf(
                ds(
                    source,
                    "Outer",
                    SymbolKind.Class,
                    start = "class Outer",
                    children =
                        listOf(
                            ds(source, "run", SymbolKind.Function, start = "fun run"),
                            ds(source, "computed", SymbolKind.Property, start = "val computed"),
                            ds(source, "Nested", SymbolKind.Class, start = "class Nested"),
                            ds(source, "Companion", SymbolKind.Object, start = "object Companion"),
                            ds(
                                source,
                                "Factory",
                                SymbolKind.Object,
                                start = "companion object Factory",
                                children =
                                    listOf(
                                        ds(source, "create", SymbolKind.Function, start = "fun create"),
                                        ds(source, "default", SymbolKind.Property, start = "val default"),
                                    ),
                            ),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(
            listOf(
                "app.Outer",
                "app.Outer.run",
                "app.Outer.computed",
                "app.Outer.Nested",
                "app.Outer.Companion",
                "app.Outer.Factory",
                "app.Outer.Factory.create",
                "app.Outer.Factory.default",
            ),
            nodes.map { it.qualifiedName },
        )
        assertEquals(nodes.size, nodes.map { it.qualifiedName }.distinct().size)
        assertKind(nodes[1], EntitySymbolKind.METHOD)
        assertKind(nodes[2], EntitySymbolKind.PROPERTY)
        assertKind(nodes[3], EntitySymbolKind.CLASS)
        assertKind(nodes[4], EntitySymbolKind.OBJECT)
        assertKind(nodes[5], EntitySymbolKind.OBJECT)
        assertKind(nodes[6], EntitySymbolKind.METHOD)
    }

    @Test
    fun distinguishesTopLevelNestedAndUnnamedCompanions() {
        val source =
            """
            package app

            object Companion

            class Regular {
                object Companion
            }

            class WithCompanion {
                companion object {
                    fun make(): Int = 1
                }
            }
            """.trimIndent()
        val regularStart = source.indexOf("class Regular")
        val withCompanionStart = source.indexOf("class WithCompanion")
        val symbols =
            listOf(
                ds(source, "Companion", SymbolKind.Object, start = "object Companion"),
                ds(
                    source,
                    "Regular",
                    SymbolKind.Class,
                    start = "class Regular",
                    children =
                        listOf(
                            ds(
                                source,
                                "Companion",
                                SymbolKind.Object,
                                start = "object Companion",
                                fromIndex = regularStart,
                            ),
                        ),
                ),
                ds(
                    source,
                    "WithCompanion",
                    SymbolKind.Class,
                    start = "class WithCompanion",
                    children =
                        listOf(
                            fullRangeDs(
                                source,
                                "Companion",
                                SymbolKind.Object,
                                "companion object",
                                "object",
                                fromIndex = withCompanionStart,
                                children = listOf(ds(source, "make", SymbolKind.Function, start = "fun make")),
                            ),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(
            listOf(
                "app.Companion",
                "app.Regular",
                "app.Regular.Companion",
                "app.WithCompanion",
                "app.WithCompanion.Companion",
                "app.WithCompanion.Companion.make",
            ),
            nodes.map { it.qualifiedName },
        )
        assertKind(nodes[0], EntitySymbolKind.OBJECT)
        assertKind(nodes[2], EntitySymbolKind.OBJECT)
        assertKind(nodes[4], EntitySymbolKind.OBJECT)
        assertKind(nodes[5], EntitySymbolKind.METHOD)
    }

    @Test
    fun includesPublicConstructorsAndExcludesPrivateAndInitConstructors() {
        val source =
            """
            package app

            class PublicPrimary(val value: Int) {
                init {
                    println(value)
                }

                constructor(name: String) : this(name.length)

                constructor(name: String, flag: Boolean) : this(name.length)
            }

            class PrivatePrimary private constructor(val value: Int)

            class PrivateSecondary {
                private constructor(value: Int) : this()
            }

            class InternalPrimary internal constructor(val value: Int)

            open class ProtectedPrimary protected constructor(val value: Int)
            """.trimIndent()
        val secondConstructorStart = source.indexOf("constructor(name: String, flag")
        val symbols =
            listOf(
                ds(
                    source,
                    "PublicPrimary",
                    SymbolKind.Class,
                    start = "class PublicPrimary",
                    children =
                        listOf(
                            fullRangeDs(source, "<class initializer>", SymbolKind.Constructor, "init {", "}"),
                            fullRangeDs(source, "init", SymbolKind.Constructor, "init {", "}"),
                            fullRangeDs(source, "<init>", SymbolKind.Constructor, "init {", "}"),
                            fullRangeDs(
                                source,
                                "PublicPrimary",
                                SymbolKind.Constructor,
                                "class PublicPrimary",
                                ")",
                            ),
                            fullRangeDs(
                                source,
                                "PublicPrimary",
                                SymbolKind.Constructor,
                                "constructor(name: String)",
                                ")",
                            ),
                            fullRangeDs(
                                source,
                                "PublicPrimary",
                                SymbolKind.Constructor,
                                "constructor(name: String, flag: Boolean)",
                                ")",
                                fromIndex = secondConstructorStart,
                            ),
                        ),
                ),
                ds(
                    source,
                    "PrivatePrimary",
                    SymbolKind.Class,
                    start = "class PrivatePrimary",
                    children =
                        listOf(
                            fullRangeDs(source, "PrivatePrimary", SymbolKind.Constructor, "class PrivatePrimary", ")"),
                        ),
                ),
                ds(
                    source,
                    "PrivateSecondary",
                    SymbolKind.Class,
                    start = "class PrivateSecondary",
                    children =
                        listOf(
                            fullRangeDs(source, "PrivateSecondary", SymbolKind.Constructor, "private constructor(value: Int)", ")"),
                        ),
                ),
                ds(
                    source,
                    "InternalPrimary",
                    SymbolKind.Class,
                    start = "class InternalPrimary",
                    children =
                        listOf(
                            fullRangeDs(source, "InternalPrimary", SymbolKind.Constructor, "class InternalPrimary", ")"),
                        ),
                ),
                ds(
                    source,
                    "ProtectedPrimary",
                    SymbolKind.Class,
                    start = "open class ProtectedPrimary",
                    children =
                        listOf(
                            fullRangeDs(source, "ProtectedPrimary", SymbolKind.Constructor, "open class ProtectedPrimary", ")"),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)
        val constructors = nodes.filter { it.qualifiedName == "app.PublicPrimary.constructor" }

        assertEquals(
            listOf(
                "app.PublicPrimary",
                "app.PublicPrimary.constructor",
                "app.PublicPrimary.constructor",
                "app.PublicPrimary.constructor",
                "app.PrivatePrimary",
                "app.PrivateSecondary",
                "app.InternalPrimary",
                "app.InternalPrimary.constructor",
                "app.ProtectedPrimary",
                "app.ProtectedPrimary.constructor",
            ),
            nodes.map { it.qualifiedName },
        )
        assertEquals(3, constructors.size)
        assertEquals(3, constructors.map { it.id }.distinct().size)
        assertEquals(
            listOf(
                "constructor(val value: Int)",
                "constructor(name: String)",
                "constructor(name: String, flag: Boolean)",
            ),
            constructors.map { it.signatureFingerprint },
        )
        assertKind(constructors[0], EntitySymbolKind.CONSTRUCTOR)
        assertTrue(nodes.none { it.qualifiedName?.startsWith("app.PrivatePrimary.constructor") == true })
        assertTrue(nodes.none { it.qualifiedName?.startsWith("app.PrivateSecondary.constructor") == true })
        assertKind(nodes.single { it.qualifiedName == "app.InternalPrimary.constructor" }, EntitySymbolKind.CONSTRUCTOR)
        assertKind(nodes.single { it.qualifiedName == "app.ProtectedPrimary.constructor" }, EntitySymbolKind.CONSTRUCTOR)
    }

    @Test
    fun excludesPrivateAndIncludesInternalAndProtected() {
        val source =
            """
            package app

            private class Hidden {
                class Leaked
            }

            private fun hiddenFunction() = Unit

            @Deprecated("old")
            private class AnnotatedHidden

            internal class VisibleInternal

            internal fun internalFunction() = Unit

            public class VisiblePublic

            class Holder {
                private val secret = 1

                private fun hidden() = Unit

                internal fun internalMember() = Unit

                protected fun protectedMember() = Unit

                fun visible() = Unit
            }
            """.trimIndent()
        val holderStart = source.indexOf("class Holder")
        val symbols =
            listOf(
                ds(
                    source,
                    "Hidden",
                    SymbolKind.Class,
                    start = "private class Hidden",
                    children = listOf(ds(source, "Leaked", SymbolKind.Class, start = "class Leaked")),
                ),
                ds(source, "hiddenFunction", SymbolKind.Function, start = "private fun hiddenFunction"),
                ds(source, "AnnotatedHidden", SymbolKind.Class, start = "@Deprecated(\"old\")\nprivate class AnnotatedHidden"),
                ds(source, "VisibleInternal", SymbolKind.Class, start = "internal class VisibleInternal"),
                ds(source, "internalFunction", SymbolKind.Function, start = "internal fun internalFunction"),
                ds(source, "VisiblePublic", SymbolKind.Class, start = "public class VisiblePublic"),
                ds(
                    source,
                    "Holder",
                    SymbolKind.Class,
                    start = "class Holder",
                    children =
                        listOf(
                            ds(source, "secret", SymbolKind.Property, start = "private val secret"),
                            ds(source, "hidden", SymbolKind.Function, start = "private fun hidden", fromIndex = holderStart),
                            ds(source, "internalMember", SymbolKind.Function, start = "internal fun internalMember"),
                            ds(source, "protectedMember", SymbolKind.Function, start = "protected fun protectedMember"),
                            ds(source, "visible", SymbolKind.Function, start = "fun visible"),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)
        val names = nodes.map { it.qualifiedName }

        assertEquals(
            listOf(
                "app.VisibleInternal",
                "app.internalFunction",
                "app.VisiblePublic",
                "app.Holder",
                "app.Holder.internalMember",
                "app.Holder.protectedMember",
                "app.Holder.visible",
            ),
            names,
        )
        assertFalse(names.any { it == "app.Hidden" })
        assertFalse(names.any { it == "app.Hidden.Leaked" })
        assertFalse(names.any { it == "app.hiddenFunction" })
        assertFalse(names.any { it == "app.AnnotatedHidden" })
        assertFalse(names.any { it == "app.Holder.secret" })
        assertFalse(names.any { it == "app.Holder.hidden" })
    }

    @Test
    fun excludesDeclarationsScopedToAFunction() {
        val source =
            """
            package app

            fun container() {
                val local = 1

                fun localFunction() = local

                class LocalClass
            }
            """.trimIndent()
        val symbols =
            listOf(
                ds(
                    source,
                    "container",
                    SymbolKind.Function,
                    start = "fun container",
                    children =
                        listOf(
                            ds(source, "local", SymbolKind.Property, start = "val local"),
                            ds(source, "localFunction", SymbolKind.Function, start = "fun localFunction"),
                            ds(source, "LocalClass", SymbolKind.Class, start = "class LocalClass"),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(listOf("app.container"), nodes.map { it.qualifiedName })
    }

    @Test
    fun distinguishesOverloadsWithDefaultAndMultilineParameters() {
        val source =
            """
            package app

            fun render(a: Int = 0, b: String): String = b

            fun render(a: Int = 0, b: Int): Int = b

            fun convert(
                input: Int,
            ): String = input.toString()

            fun convert(
                input: String,
            ): String = input
            """.trimIndent()
        val secondRenderStart = source.indexOf("render") + 1
        val secondConvertStart = source.indexOf("convert") + 1
        val symbols =
            listOf(
                ds(source, "render", SymbolKind.Function, start = "fun render"),
                ds(source, "render", SymbolKind.Function, start = "fun render", fromIndex = secondRenderStart),
                ds(source, "convert", SymbolKind.Function, start = "fun convert", end = "input.toString()"),
                ds(source, "convert", SymbolKind.Function, start = "fun convert", end = "= input", fromIndex = secondConvertStart),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)
        assertEquals(4, nodes.size)
        assertEquals(4, nodes.map { it.id }.distinct().size)
        assertTrue(nodes.none { it.unstable })
        assertTrue(nodes[0].signatureFingerprint?.contains("b: String") == true)
        assertTrue(nodes[1].signatureFingerprint?.contains("b: Int") == true)
        assertTrue(nodes[2].signatureFingerprint?.contains("input: Int") == true)
        assertTrue(nodes[2].signatureFingerprint?.contains("): String") == true)
        assertTrue(nodes[3].signatureFingerprint?.contains("input: String") == true)
    }

    @Test
    fun distinguishesExtensionReceiversAndExpressionBodyFunctions() {
        val source =
            """
            package app

            fun String.parse(): String = this

            fun Int.parse(): Int = this

            fun expression(value: Int): Int = value + 1
            """.trimIndent()
        val intParseStart = source.indexOf("fun Int.parse")
        val symbols =
            listOf(
                ds(source, "parse", SymbolKind.Function, start = "fun String.parse"),
                ds(source, "parse", SymbolKind.Function, start = "fun Int.parse", fromIndex = intParseStart),
                ds(source, "expression", SymbolKind.Function, start = "fun expression"),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(3, nodes.size)
        assertEquals(3, nodes.map { it.id }.distinct().size)
        assertTrue(nodes[0].signatureFingerprint?.contains("fun String.parse(): String") == true)
        assertTrue(nodes[1].signatureFingerprint?.contains("fun Int.parse(): Int") == true)
        assertEquals("fun expression(value: Int): Int", nodes[2].signatureFingerprint)
        assertFalse(nodes[2].signatureFingerprint?.contains("value + 1") == true)
    }

    @Test
    fun skipsContextReceiverPrefixesBeforeTheDeclarationKeyword() {
        val source =
            """
            package app

            context(Foo) private fun hidden()
            context(Foo) fun visible(): Int = 1

            private context(Foo) fun hiddenFirst()
            context(Foo) private class HiddenClass
            context(Foo) class VisibleClass

            @OptIn(Foo::class)
            context(Foo) fun annotatedVisible(): Int = 2
            """.trimIndent()
        val symbols =
            listOf(
                ds(source, "hidden", SymbolKind.Function, start = "context(Foo) private fun hidden"),
                ds(source, "visible", SymbolKind.Function, start = "context(Foo) fun visible"),
                ds(source, "hiddenFirst", SymbolKind.Function, start = "private context(Foo) fun hiddenFirst"),
                ds(source, "HiddenClass", SymbolKind.Class, start = "context(Foo) private class HiddenClass"),
                ds(source, "VisibleClass", SymbolKind.Class, start = "context(Foo) class VisibleClass"),
                ds(
                    source,
                    "annotatedVisible",
                    SymbolKind.Function,
                    start = "@OptIn(Foo::class)\ncontext(Foo) fun annotatedVisible",
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)
        val names = nodes.map { it.qualifiedName }

        assertEquals(listOf("app.visible", "app.VisibleClass", "app.annotatedVisible"), names)
        assertTrue(nodes[0].signatureFingerprint?.startsWith("fun visible(): Int") == true)
        assertTrue(nodes[1].signatureFingerprint?.startsWith("class VisibleClass") == true)
        assertTrue(nodes[2].signatureFingerprint?.startsWith("fun annotatedVisible(): Int") == true)
        assertTrue(nodes.none { it.signatureFingerprint?.contains("context(") == true })
        assertTrue(nodes.none { it.signatureFingerprint?.contains("@OptIn") == true })
        assertTrue(nodes.none { it.qualifiedName == "app.hidden" })
        assertTrue(nodes.none { it.qualifiedName == "app.hiddenFirst" })
        assertTrue(nodes.none { it.qualifiedName == "app.HiddenClass" })
    }

    @Test
    fun usesTheRealDeclarationKeywordAfterAnnotations() {
        val source =
            """
            package app

            @OptIn(Foo::class)
            typealias Name = String

            @OptIn(Foo::class)
            private class HiddenClass

            @OptIn(Foo::class)
            private enum class HiddenEnum {
                VALUE,
            }

            @OptIn(Foo::class)
            @Suppress("private")
            class VisibleClass

            @OptIn(Foo::class)
            @Suppress("private")
            enum class VisibleEnum {
                VALUE,
            }
            """.trimIndent()
        val visibleEnumStart = source.indexOf("enum class VisibleEnum")
        val symbols =
            listOf(
                ds(source, "Name", SymbolKind.Class, start = "@OptIn(Foo::class)\ntypealias Name"),
                ds(source, "HiddenClass", SymbolKind.Class, start = "@OptIn(Foo::class)\nprivate class HiddenClass"),
                ds(source, "HiddenEnum", SymbolKind.Enum, start = "@OptIn(Foo::class)\nprivate enum class HiddenEnum"),
                ds(
                    source,
                    "VisibleClass",
                    SymbolKind.Class,
                    start = "@OptIn(Foo::class)\n@Suppress(\"private\")\nclass VisibleClass",
                ),
                ds(
                    source,
                    "VisibleEnum",
                    SymbolKind.Enum,
                    start = "@OptIn(Foo::class)\n@Suppress(\"private\")\nenum class VisibleEnum",
                    children =
                        listOf(
                            ds(source, "VALUE", SymbolKind.EnumMember, fromIndex = visibleEnumStart),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(
            listOf("app.Name", "app.VisibleClass", "app.VisibleEnum", "app.VisibleEnum.VALUE"),
            nodes.map { it.qualifiedName },
        )
        assertKind(nodes[0], EntitySymbolKind.TYPE_ALIAS)
        assertTrue(nodes[0].signatureFingerprint?.startsWith("typealias Name = String") == true)
        assertTrue(nodes[1].signatureFingerprint?.startsWith("class VisibleClass") == true)
        assertTrue(nodes[2].signatureFingerprint?.startsWith("enum class VisibleEnum") == true)
        assertTrue(nodes.none { it.signatureFingerprint?.contains("@OptIn") == true })
        assertTrue(nodes.none { it.signatureFingerprint?.contains("Suppress") == true })
        assertTrue(nodes.none { it.signatureFingerprint?.contains("Foo::class") == true })
        assertTrue(nodes.none { it.qualifiedName == "app.HiddenClass" })
        assertTrue(nodes.none { it.qualifiedName == "app.HiddenEnum" })
    }

    @Test
    fun ignoresPrivateCompanionAndTypeAliasWordsInAnnotationsAndComments() {
        val source =
            """
            package app

            @Suppress("private", "companion", "typealias")
            class Visible

            /* private companion typealias */
            class Commented

            @Suppress("private")
            object NotCompanion
            """.trimIndent()
        val symbols =
            listOf(
                ds(
                    source,
                    "Visible",
                    SymbolKind.Class,
                    start = "@Suppress(\"private\", \"companion\", \"typealias\")",
                ),
                ds(source, "Commented", SymbolKind.Class, start = "/* private companion typealias */"),
                ds(source, "NotCompanion", SymbolKind.Object, start = "@Suppress(\"private\")"),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(listOf("app.Visible", "app.Commented", "app.NotCompanion"), nodes.map { it.qualifiedName })
        assertKind(nodes[0], EntitySymbolKind.CLASS)
        assertKind(nodes[1], EntitySymbolKind.CLASS)
        assertKind(nodes[2], EntitySymbolKind.OBJECT)
    }

    @Test
    fun treatsBacktickedIdentifiersAsOpaque() {
        val source =
            """
            package app

            class `typealias` {
                class Nested
            }

            fun `a{b`(value: Int): Int = value

            fun `a=b`(value: String): String = value

            class `private`(val value: Int)

            fun `a(b`(value: Int): Int = value

            fun `a'b`(value: Int): Int = value

            fun `a"b`(value: Int): Int = value
            """.trimIndent()
        val symbols =
            listOf(
                ds(
                    source,
                    "typealias",
                    SymbolKind.Class,
                    start = "class `typealias`",
                    children = listOf(ds(source, "Nested", SymbolKind.Class, start = "class Nested")),
                ),
                ds(source, "a{b", SymbolKind.Function, start = "fun `a{b`"),
                ds(source, "a=b", SymbolKind.Function, start = "fun `a=b`"),
                ds(
                    source,
                    "private",
                    SymbolKind.Class,
                    start = "class `private`",
                    children = listOf(fullRangeDs(source, "private", SymbolKind.Constructor, "class `private`", ")")),
                ),
                ds(source, "a(b", SymbolKind.Function, start = "fun `a(b`"),
                ds(source, "a'b", SymbolKind.Function, start = "fun `a'b`"),
                ds(source, "a\"b", SymbolKind.Function, start = "fun `a\"b`"),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(
            listOf(
                "app.typealias",
                "app.typealias.Nested",
                "app.a{b",
                "app.a=b",
                "app.private",
                "app.private.constructor",
                "app.a(b",
                "app.a'b",
                "app.a\"b",
            ),
            nodes.map { it.qualifiedName },
        )
        assertKind(nodes[0], EntitySymbolKind.CLASS)
        assertKind(nodes[1], EntitySymbolKind.CLASS)
        assertKind(nodes[5], EntitySymbolKind.CONSTRUCTOR)
        assertTrue(nodes[0].signatureFingerprint?.contains("`typealias`") == true)
        assertTrue(nodes[2].signatureFingerprint?.contains("(value: Int): Int") == true)
        assertTrue(nodes[3].signatureFingerprint?.contains("(value: String): String") == true)
        assertEquals("constructor(val value: Int)", nodes[5].signatureFingerprint)
        assertEquals("fun `a(b`(value: Int): Int", nodes[6].signatureFingerprint)
        assertEquals("fun `a'b`(value: Int): Int", nodes[7].signatureFingerprint)
        assertEquals("fun `a\"b`(value: Int): Int", nodes[8].signatureFingerprint)
        assertEquals(nodes.size, nodes.map { it.id }.distinct().size)
    }

    @Test
    fun handlesBacktickedInitAndDollarNames() {
        val dollar = "${'$'}"
        val source =
            """
            package app

            class `init`(val value: Int) {
                private constructor(name: String) : this(name.length)
            }

            class SyntheticNamed {
                constructor(value: Int)
            }

            class `foo${dollar}bar` {
                class Nested
            }

            fun `foo${dollar}bar`(): Int = 1
            """.trimIndent()
        val functionStart = source.indexOf("fun `foo${dollar}bar`")
        val symbols =
            listOf(
                ds(
                    source,
                    "init",
                    SymbolKind.Class,
                    start = "class `init`",
                    children =
                        listOf(
                            fullRangeDs(source, "init", SymbolKind.Constructor, "class `init`", ")"),
                            fullRangeDs(source, "init", SymbolKind.Constructor, "private constructor(name: String)", ")"),
                        ),
                ),
                ds(
                    source,
                    "SyntheticNamed",
                    SymbolKind.Class,
                    start = "class SyntheticNamed",
                    children = listOf(fullRangeDs(source, "<init>", SymbolKind.Constructor, "constructor(value: Int)", ")")),
                ),
                ds(
                    source,
                    "foo${dollar}bar",
                    SymbolKind.Class,
                    start = "class `foo${dollar}bar`",
                    children = listOf(ds(source, "Nested", SymbolKind.Class, start = "class Nested")),
                ),
                ds(source, "foo${dollar}bar", SymbolKind.Function, start = "fun `foo${dollar}bar`", fromIndex = functionStart),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)
        val names = nodes.map { it.qualifiedName }

        assertEquals(
            listOf(
                "app.init",
                "app.init.constructor",
                "app.SyntheticNamed",
                "app.SyntheticNamed.constructor",
                "app.foo${dollar}bar",
                "app.foo${dollar}bar.Nested",
                "app.foo${dollar}bar",
            ),
            names,
        )
        assertEquals(1, nodes.count { it.qualifiedName == "app.init.constructor" })
        assertKind(nodes.single { it.qualifiedName == "app.init.constructor" }, EntitySymbolKind.CONSTRUCTOR)
        assertKind(nodes.single { it.qualifiedName == "app.SyntheticNamed.constructor" }, EntitySymbolKind.CONSTRUCTOR)
        assertEquals(2, nodes.count { it.qualifiedName == "app.foo${dollar}bar" })
        assertEquals(nodes.size, nodes.map { it.id }.distinct().size)
    }

    @Test
    fun handlesBomAndShebangPackageHeaders() {
        val bom = "\uFEFFpackage app\n\nclass Widget\n"
        val shebang = "#!/usr/bin/env kotlin\npackage app\n\nclass Widget\n"

        assertEquals("app.Widget", discoverWidget(bom).qualifiedName)
        assertEquals("app.Widget", discoverWidget(shebang).qualifiedName)
    }

    @Test
    fun stopsAtBodyBoundariesWhenDefaultsContainComparisons() {
        val base =
            """
            package app

            fun isBelow(value: Boolean = 1 < 2): Boolean = value

            class Box(val value: Boolean = 1 < 2) {
                fun member() = 1
            }
            """.trimIndent()
        val edited =
            base
                .replace("= value", "= !value")
                .replace("fun member() = 1", "fun member() = 2")
        val first = KotlinEntityDiscovery.discover(graphId, fileUri, base, comparisonSymbols(base))
        val second = KotlinEntityDiscovery.discover(graphId, fileUri, edited, comparisonSymbols(edited))

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(listOf("app.isBelow", "app.Box", "app.Box.constructor", "app.Box.member"), first.map { it.qualifiedName })
        assertFalse(first[0].signatureFingerprint?.contains("!value") == true)
        assertFalse(first[0].signatureFingerprint?.contains("= value") == true)
        assertFalse(first[1].signatureFingerprint?.contains("member") == true)
        assertEquals("constructor(val value: Boolean = 1 < 2)", first[2].signatureFingerprint)
    }

    @Test
    fun keepsConstructorContainingALocalClassWithAnInitBlock() {
        val source =
            """
            package app

            class Container {
                constructor(value: Int) {
                    class Local {
                        init {
                            println(value)
                        }
                    }
                }
            }
            """.trimIndent()
        val symbols =
            listOf(
                ds(
                    source,
                    "Container",
                    SymbolKind.Class,
                    start = "class Container",
                    children =
                        listOf(
                            fullRangeDs(source, "Container", SymbolKind.Constructor, "constructor(value: Int)", "}"),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(listOf("app.Container", "app.Container.constructor"), nodes.map { it.qualifiedName })
        assertEquals("constructor(value: Int)", nodes[1].signatureFingerprint)
    }

    @Test
    fun extractsPackageDirectivesFromTheFileHeader() {
        assertEquals(
            "Widget",
            discoverWidget(
                """
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
        assertEquals(
            "Widget",
            discoverWidget(
                """
                // package wrong
                /* package wrong */
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
        assertEquals(
            "app.Widget",
            discoverWidget(
                """
                /*
                package wrong
                */
                package app
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
        assertEquals(
            "app.Widget",
            discoverWidget(
                """
                package app/* note */
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
        assertEquals(
            "com.example.my-pkg.Widget",
            discoverWidget(
                """
                package `com.example`.`my-pkg`
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
        assertEquals(
            "app.Widget",
            discoverWidget(
                """
                @file:Suppress("package")
                package app
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
        assertEquals(
            "app.Widget",
            discoverWidget(
                """
                @file:[JvmName("X") Suppress(format(1, 2))]
                package app
                class Widget
                """.trimIndent(),
            ).qualifiedName,
        )
    }

    @Test
    fun excludesAnonymousAndSyntheticNames() {
        val source =
            """
            package app

            class Real
            """.trimIndent()
        val emptyRange = Range(Position(2, 0), Position(2, 1))
        val symbols =
            listOf(
                DocumentSymbol("<anonymous>", SymbolKind.Class, emptyRange, emptyRange),
                DocumentSymbol("", SymbolKind.Class, emptyRange, emptyRange),
                DocumentSymbol("Generated${'$'}Access", SymbolKind.Class, emptyRange, emptyRange),
                ds(source, "Real", SymbolKind.Class, start = "class Real"),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(listOf("app.Real"), nodes.map { it.qualifiedName })
    }

    @Test
    fun ignoresPrivateWordsInBodiesAndStrings() {
        val source =
            """
            package app

            fun visible(): String {
                val privateValue = "private"
                return privateValue
            }

            class Visible {
                val message: String = "private"

                fun execute(): String {
                    return "private"
                }
            }
            """.trimIndent()
        val visibleStart = source.indexOf("class Visible")
        val symbols =
            listOf(
                ds(source, "visible", SymbolKind.Function, start = "fun visible"),
                ds(
                    source,
                    "Visible",
                    SymbolKind.Class,
                    start = "class Visible",
                    children =
                        listOf(
                            ds(source, "message", SymbolKind.Property, start = "val message", fromIndex = visibleStart),
                            ds(source, "execute", SymbolKind.Function, start = "fun execute", fromIndex = visibleStart),
                        ),
                ),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(
            listOf("app.visible", "app.Visible", "app.Visible.message", "app.Visible.execute"),
            nodes.map { it.qualifiedName },
        )
        assertTrue(nodes.none { it.unstable })
    }

    @Test
    fun usesDeclarationDerivedFallbackThatSurvivesReordering() {
        val source =
            """
            package app

            /* first missing */
            /* second missing */
            """.trimIndent()
        val first = fullRangeDs(source, "missing", SymbolKind.Function, "/* first missing */", "*/")
        val second = fullRangeDs(source, "missing", SymbolKind.Function, "/* second missing */", "*/")
        val forward = KotlinEntityDiscovery.discover(graphId, fileUri, source, listOf(first, second))
        val reversed = KotlinEntityDiscovery.discover(graphId, fileUri, source, listOf(second, first))

        assertEquals(2, forward.size)
        assertEquals(2, forward.map { it.id }.distinct().size)
        assertTrue(forward.all { it.unstable })
        assertTrue(forward.all { it.signatureFingerprint?.startsWith("unstable-") == true })
        assertEquals(
            forward.associate { it.signatureFingerprint to it.id },
            reversed.associate { it.signatureFingerprint to it.id },
        )
    }

    @Test
    fun marksDuplicateIdsUnstableWithoutCollapsingThem() {
        val source =
            """
            package app

            fun duplicate() = Unit
            fun duplicate() = Unit
            """.trimIndent()
        val secondStart = source.indexOf("duplicate") + 1
        val symbols =
            listOf(
                ds(source, "duplicate", SymbolKind.Function, start = "fun duplicate"),
                ds(source, "duplicate", SymbolKind.Function, start = "fun duplicate", fromIndex = secondStart),
            )

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(2, nodes.size)
        assertEquals(2, nodes.map { it.id }.distinct().size)
        assertTrue(nodes.all { it.unstable })
        assertEquals(listOf("fun duplicate()", "fun duplicate()#0"), nodes.map { it.signatureFingerprint })
    }

    @Test
    fun keepsIdsStableAcrossRunsLineShiftsAndBodyEdits() {
        val base =
            """
            package app

            class Stable {
                fun work(value: Int): Int = value
            }
            """.trimIndent()
        val shifted = "\n\n$base"
        val editedBody = base.replace("= value", "= value + 1")
        val first = KotlinEntityDiscovery.discover(graphId, fileUri, base, stableSymbols(base))
        val second = KotlinEntityDiscovery.discover(graphId, fileUri, base, stableSymbols(base))
        val moved = KotlinEntityDiscovery.discover(graphId, fileUri, shifted, stableSymbols(shifted))
        val edited = KotlinEntityDiscovery.discover(graphId, fileUri, editedBody, stableSymbols(editedBody))

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.id }, moved.map { it.id })
        assertEquals(first.map { it.id }, edited.map { it.id })
        assertNull(moved[0].parent)
    }

    @Test
    fun preservesSpansAcrossCrlfAndUnicode() {
        val source = "package app\r\n\r\nval text = \"😀\"\r\n\r\nclass Widget\r\n"
        val symbols = listOf(ds(source, "Widget", SymbolKind.Class, start = "class Widget", end = "class Widget"))

        val nodes = KotlinEntityDiscovery.discover(graphId, fileUri, source, symbols)

        assertEquals(listOf("app.Widget"), nodes.map { it.qualifiedName })
        assertEquals(SourceSpan(4, 0, 4, "class Widget".length), nodes[0].span)
    }

    private fun discoverWidget(source: String): Node =
        KotlinEntityDiscovery
            .discover(
                graphId,
                fileUri,
                source,
                listOf(ds(source, "Widget", SymbolKind.Class, start = "class Widget")),
            ).single()

    private fun stableSymbols(source: String): List<DocumentSymbol> =
        listOf(
            ds(
                source,
                "Stable",
                SymbolKind.Class,
                start = "class Stable",
                children = listOf(ds(source, "work", SymbolKind.Function, start = "fun work")),
            ),
        )

    private fun comparisonSymbols(source: String): List<DocumentSymbol> =
        listOf(
            ds(source, "isBelow", SymbolKind.Function, start = "fun isBelow"),
            fullRangeDs(
                source,
                "Box",
                SymbolKind.Class,
                "class Box",
                "}",
                children =
                    listOf(
                        fullRangeDs(source, "Box", SymbolKind.Constructor, "class Box", ")"),
                        ds(source, "member", SymbolKind.Function, start = "fun member"),
                    ),
            ),
        )

    private fun assertKind(
        node: Node,
        kind: EntitySymbolKind,
    ) {
        assertTrue(node.id.value.contains("/${kind.name}/"), "${node.id.value} should contain ${kind.name}")
    }

    private fun ds(
        source: String,
        name: String,
        kind: SymbolKind,
        start: String = name,
        end: String? = null,
        fromIndex: Int = 0,
        children: List<DocumentSymbol> = emptyList(),
    ): DocumentSymbol {
        val selectionStart = source.indexOf(name, fromIndex)
        require(selectionStart >= 0) { "Missing symbol name $name" }
        val selectionEnd = selectionStart + name.length
        val rangeStart = source.lastIndexOf(start, selectionStart).let { if (it < 0) selectionStart else it }
        val rangeEnd =
            if (end != null) {
                val found = source.indexOf(end, rangeStart)
                if (found < 0) selectionEnd else found + end.length
            } else {
                val newline = source.indexOf('\n', selectionEnd)
                if (newline < 0) source.length else newline
            }
        return DocumentSymbol(
            name,
            kind,
            range(source, rangeStart, rangeEnd),
            range(source, selectionStart, selectionEnd),
            null,
            children,
        )
    }

    private fun fullRangeDs(
        source: String,
        name: String,
        kind: SymbolKind,
        start: String,
        end: String,
        fromIndex: Int = 0,
        children: List<DocumentSymbol> = emptyList(),
    ): DocumentSymbol {
        val startOffset = source.indexOf(start, fromIndex)
        require(startOffset >= 0) { "Missing range start $start" }
        val endOffset =
            source.indexOf(end, startOffset).let {
                if (it < 0) startOffset + start.length else it + end.length
            }
        val range = range(source, startOffset, endOffset)
        return DocumentSymbol(name, kind, range, range, null, children)
    }

    private fun range(
        source: String,
        start: Int,
        end: Int,
    ): Range = Range(position(source, start), position(source, end))

    private fun position(
        source: String,
        offset: Int,
    ): Position {
        val prefix = source.substring(0, offset)
        val line = prefix.count { it == '\n' }
        val lineStart = prefix.lastIndexOf('\n') + 1
        return Position(line, offset - lineStart)
    }
}
