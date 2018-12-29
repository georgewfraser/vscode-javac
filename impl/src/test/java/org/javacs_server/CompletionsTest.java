package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.stream.Collectors;
import org.javacs.lsp.CompletionItem;
import org.javacs_server.lsp.*;
import org.junit.Ignore;
import org.junit.Test;

public class CompletionsTest extends CompletionsBase {

    @Test
    public void staticMember() {
        var file = "/org/javacs_server/example/AutocompleteStaticMember.java";

        // Static methods
        var suggestions = insertText(file, 5, 38);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic", "class"));
        assertThat(suggestions, not(hasItems("testField", "testMethod", "getClass")));
    }

    @Test
    public void staticReference() {
        var file = "/org/javacs_server/example/AutocompleteStaticReference.java";

        // Static methods
        var suggestions = insertText(file, 7, 48);

        assertThat(suggestions, hasItems("testMethod", "testMethodStatic", "new"));
        assertThat(suggestions, not(hasItems("class")));
    }

    @Test
    public void member() {
        var file = "/org/javacs_server/example/AutocompleteMember.java";

        // Virtual testMethods
        var suggestions = insertText(file, 5, 14);

        assertThat(
                "excludes static members",
                suggestions,
                not(
                        hasItems(
                                "testFieldStatic",
                                "testMethodStatic",
                                "testFieldStaticPrivate",
                                "testMethodStaticPrivate",
                                "class",
                                "AutocompleteMember")));
        assertThat(
                "includes non-static members",
                suggestions,
                hasItems("testFields", "testMethods", "testFieldsPrivate", "testMethodsPrivate", "getClass"));
        assertThat("excludes constructors", suggestions, not(hasItem(startsWith("AutocompleteMember"))));
    }

    @Test
    public void fieldFromInitBlock() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // f
        var suggestions = insertText(file, 8, 10);

        assertThat(suggestions, hasItems("testFields", "testFieldStatic", "testMethods", "testMethodStatic"));
    }

    @Test
    public void thisDotFieldFromInitBlock() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // this.f
        var suggestions = insertText(file, 9, 15);

        assertThat(suggestions, hasItems("testFields", "testMethods"));
        assertThat(suggestions, not(hasItems("testFieldStatic", "testMethodStatic")));
    }

    @Test
    public void classDotFieldFromInitBlock() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // AutocompleteMembers.f
        var suggestions = insertText(file, 10, 30);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testMethods")));
    }

    @Test
    public void fieldFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // f
        var suggestions = insertText(file, 22, 10);

        assertThat(
                suggestions,
                hasItems("testFields", "testFieldStatic", "testMethods", "testMethodStatic", "testArguments"));
    }

    @Test
    public void thisDotFieldFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // this.f
        var suggestions = insertText(file, 23, 15);

        assertThat(suggestions, hasItems("testFields", "testMethods"));
        assertThat(suggestions, not(hasItems("testFieldStatic", "testMethodStatic", "testArguments")));
    }

    @Test
    public void classDotFieldFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // AutocompleteMembers.f
        var suggestions = insertText(file, 24, 30);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testMethods", "testArguments")));
    }

    @Test
    public void thisRefMethodFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // this::m
        var suggestions = insertText(file, 25, 59);

        assertThat(suggestions, hasItems("testMethods"));
        assertThat(suggestions, not(hasItems("testFields", "testFieldStatic", "testMethodStatic")));
    }

    @Test
    public void classRefMethodFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // AutocompleteMembers::m
        var suggestions = insertText(file, 26, 74);

        assertThat(suggestions, hasItems("testMethodStatic", "testMethods"));
        assertThat(suggestions, not(hasItems("testFields", "testFieldStatic")));
    }

    @Test
    @Ignore // javac doesn't give us helpful info about the fact that static initializers are static
    public void fieldFromStaticInitBlock() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // f
        var suggestions = insertText(file, 16, 10);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testMethods")));
    }

    @Test
    public void classDotFieldFromStaticInitBlock() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // AutocompleteMembers.f
        var suggestions = insertText(file, 17, 30);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testMethods")));
    }

    @Test
    public void classRefFieldFromStaticInitBlock() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // AutocompleteMembers::m
        var suggestions = insertText(file, 17, 30);

        assertThat(suggestions, hasItems("testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testFieldStatic", "testMethods")));
    }

    @Test
    public void fieldFromStaticMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // f
        var suggestions = insertText(file, 30, 10);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic", "testArguments"));
        assertThat(suggestions, not(hasItems("testFields", "testMethods")));
    }

    @Test
    public void classDotFieldFromStaticMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // AutocompleteMembers.f
        var suggestions = insertText(file, 31, 30);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testMethods", "testArguments")));
    }

    @Test
    public void classRefFieldFromStaticMethod() {
        var file = "/org/javacs_server/example/AutocompleteMembers.java";

        // TODO
        // AutocompleteMembers::m
        var suggestions = insertText(file, 17, 30);

        assertThat(suggestions, hasItems("testMethodStatic"));
        assertThat(suggestions, not(hasItems("testFields", "testFieldStatic", "testMethods")));
    }

    private static String sortText(CompletionItem i) {
        if (i.sortText != null) return i.sortText;
        else return i.label;
    }

    @Test
    public void otherMethod() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // new AutocompleteMember().
        var suggestions = insertText(file, 5, 34);

        assertThat(suggestions, not(hasItems("testFieldStatic", "testMethodStatic", "class")));
        assertThat(suggestions, not(hasItems("testFieldStaticPrivate", "testMethodStaticPrivate")));
        assertThat(suggestions, not(hasItems("testFieldsPrivate", "testMethodsPrivate")));
        assertThat(suggestions, hasItems("testFields", "testMethods", "getClass"));
    }

    @Test
    public void otherStatic() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // AutocompleteMember.
        var suggestions = insertText(file, 7, 28);

        assertThat(suggestions, hasItems("testFieldStatic", "testMethodStatic", "class"));
        assertThat(suggestions, not(hasItems("testFieldStaticPrivate", "testMethodStaticPrivate")));
        assertThat(suggestions, not(hasItems("testFieldsPrivate", "testMethodsPrivate")));
        assertThat(suggestions, not(hasItems("testFields", "testMethods", "getClass")));
    }

    @Test
    public void otherDotClassDot() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // AutocompleteMember.class.
        var suggestions = insertText(file, 8, 33);

        assertThat(suggestions, hasItems("getName", "getClass"));
        assertThat(suggestions, not(hasItems("testFieldStatic", "testMethodStatic", "class")));
        assertThat(suggestions, not(hasItems("testFieldStaticPrivate", "testMethodStaticPrivate")));
        assertThat(suggestions, not(hasItems("testFieldsPrivate", "testMethodsPrivate")));
        assertThat(suggestions, not(hasItems("testFields", "testMethods")));
    }

    @Test
    public void otherClass() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Auto?
        var suggestions = insertText(file, 6, 13);

        assertThat(suggestions, hasItems("AutocompleteOther", "AutocompleteMember"));
    }

    @Test
    public void arrayLength() {
        var file = "/org/javacs_server/example/AutocompleteArray.java";

        // a.?
        var suggestions = insertText(file, 7, 11);

        assertThat(suggestions, hasItems("length"));
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void addImport() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Name of class
        var items = items(file, 9, 17);

        for (var item : items) {
            if ("ArrayList".equals(item.label)) {
                assertThat(item.additionalTextEdits, not(nullValue()));
                assertThat(item.additionalTextEdits, not(empty()));

                return;
            }
        }

        fail("No ArrayList in " + items);
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void dontImportSamePackage() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Name of class
        var items = items(file, 6, 10);

        for (var item : items) {
            if ("AutocompleteMember".equals(item.label)) {
                assertThat(item.additionalTextEdits, either(empty()).or(nullValue()));

                return;
            }
        }

        fail("No AutocompleteMember in " + items);
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void dontImportJavaLang() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Name of class
        var items = items(file, 11, 38);

        for (var item : items) {
            if ("ArrayIndexOutOfBoundsException".equals(item.label)) {
                assertThat(item.additionalTextEdits, either(empty()).or(nullValue()));

                return;
            }
        }

        fail("No ArrayIndexOutOfBoundsException in " + items);
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void dontImportSelf() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Name of class
        var items = items(file, 6, 10);

        for (var item : items) {
            if ("AutocompleteOther".equals(item.label)) {
                assertThat(item.additionalTextEdits, either(empty()).or(nullValue()));

                return;
            }
        }

        fail("No AutocompleteOther in " + items);
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void dontImportAlreadyImported() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Name of class
        var items = items(file, 12, 14);

        for (var item : items) {
            if ("Arrays".equals(item.label)) {
                assertThat(item.additionalTextEdits, either(empty()).or(nullValue()));

                return;
            }
        }

        fail("No Arrays in " + items);
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void dontImportAlreadyImportedStar() {
        var file = "/org/javacs_server/example/AutocompleteOther.java";

        // Name of class
        var items = items(file, 10, 26);

        for (var item : items) {
            if ("ArrayBlockingQueue".equals(item.label)) {
                assertThat(item.additionalTextEdits, either(empty()).or(nullValue()));

                return;
            }
        }

        fail("No ArrayBlockingQueue in " + items);
    }

    @Test
    public void fromClasspath() {
        var file = "/org/javacs_server/example/AutocompleteFromClasspath.java";

        // Static methods
        var items = items(file, 8, 17);
        var suggestions = items.stream().map(i -> i.label).collect(Collectors.toSet());

        assertThat(suggestions, hasItems("add", "addAll"));
    }

    @Test
    public void betweenLines() {
        var file = "/org/javacs_server/example/AutocompleteBetweenLines.java";

        // Static methods
        var suggestions = insertText(file, 9, 18);

        assertThat(suggestions, hasItems("add"));
    }

    @Test
    public void reference() {
        var file = "/org/javacs_server/example/AutocompleteReference.java";

        // Static methods
        var suggestions = insertTemplate(file, 7, 21);

        assertThat(suggestions, not(hasItems("testMethodStatic")));
        assertThat(suggestions, hasItems("testMethods", "getClass"));
    }

    @Test
    @Ignore // This has been subsumed by Javadocs
    public void docstring() {
        var file = "/org/javacs_server/example/AutocompleteDocstring.java";
        var docstrings = documentation(file, 8, 14);

        assertThat(docstrings, hasItems("A testMethods", "A testFields"));

        docstrings = documentation(file, 12, 31);

        assertThat(docstrings, hasItems("A testFieldStatic", "A testMethodStatic"));
    }

    @Test
    public void classes() {
        var file = "/org/javacs_server/example/AutocompleteClasses.java";

        // Fix?
        var suggestions = insertText(file, 5, 12);

        assertThat(suggestions, hasItems("FixParseErrorAfter"));

        // Some?
        suggestions = insertText(file, 6, 13);

        assertThat(suggestions, hasItems("SomeInnerClass"));

        // List?
        suggestions = insertText(file, 7, 12);

        assertThat(suggestions, hasItems("List"));
    }

    @Test
    public void editMethodName() {
        var file = "/org/javacs_server/example/AutocompleteEditMethodName.java";

        // Static methods
        var suggestions = insertText(file, 5, 21);

        assertThat(suggestions, hasItems("getClass"));
    }

    @Test
    @Ignore // This has been subsumed by Javadocs
    public void restParams() {
        var file = "/org/javacs_server/example/AutocompleteRest.java";

        // Static methods
        var items = items(file, 5, 18);
        var suggestions = items.stream().map(i -> i.label).collect(Collectors.toSet());
        var details = items.stream().map(i -> i.detail).collect(Collectors.toSet());

        assertThat(suggestions, hasItems("restMethod"));
        assertThat(details, hasItems("void (String... params)"));
    }

    @Test
    public void constructor() {
        var file = "/org/javacs_server/example/AutocompleteConstructor.java";

        // Static methods
        var suggestions = insertText(file, 5, 25);

        assertThat(suggestions, hasItem(startsWith("AutocompleteConstructor")));
        assertThat(suggestions, hasItem(startsWith("AutocompleteMember")));
    }

    @Ignore // We are now managing imports with FixImports
    @Test
    public void autoImportConstructor() {
        var file = "/org/javacs_server/example/AutocompleteConstructor.java";

        // Static methods
        var items = items(file, 6, 19);
        var suggestions = items.stream().map(i -> i.insertText).collect(Collectors.toList());

        assertThat(suggestions, hasItems("ArrayList<>($0)"));

        for (var each : items) {
            if (each.insertText.equals("ArrayList<>"))
                assertThat("new ? auto-imports", each.additionalTextEdits, both(not(empty())).and(not(nullValue())));
        }
    }

    @Ignore
    @Test
    public void importFromSource() {
        var file = "/org/javacs_server/example/AutocompletePackage.java";
        var suggestions = insertText(file, 3, 12);

        assertThat("Does not have own package class", suggestions, hasItems("javacs_server"));
    }

    @Test
    public void importFromClasspath() {
        var file = "/org/javacs_server/example/AutocompletePackage.java";
        var suggestions = insertText(file, 5, 13);

        assertThat("Has class from classpath", suggestions, hasItems("util"));
    }

    // TODO top level of import
    @Ignore
    @Test
    public void importFirstId() {
        var file = "/org/javacs_server/example/AutocompletePackage.java";

        // import ?
        var suggestions = insertText(file, 7, 9);

        assertThat("Has class from classpath", suggestions, hasItems("com", "org"));
    }

    @Test
    public void emptyClasspath() {
        var file = "/org/javacs_server/example/AutocompletePackage.java";

        // Static methods
        var suggestions = insertText(file, 6, 12);

        assertThat("Has deeply nested class", suggestions, not(hasItems("google.common.collect.Lists")));
    }

    @Test
    public void importClass() {
        var file = "/org/javacs_server/example/AutocompletePackage.java";

        // Static methods
        var items = items(file, 4, 32);
        var suggestions = items.stream().map(i -> i.label).collect(Collectors.toList());

        assertThat(suggestions, hasItems("OtherPackagePublic"));
        assertThat(suggestions, not(hasItems("OtherPackagePrivate")));

        // Imports are now being managed by FixImports
        // for (var item : items) {
        //     if (item.label.equals("OtherPackagePublic"))
        //         assertThat(
        //                 "Don't import when completing imports",
        //                 item.additionalTextEdits,
        //                 either(empty()).or(nullValue()));
        // }
    }

    @Test
    public void otherPackageId() {
        var file = "/org/javacs_server/example/AutocompleteOtherPackage.java";

        // Static methods
        var items = items(file, 5, 14);
        var suggestions = items.stream().map(i -> i.label).collect(Collectors.toList());

        assertThat(suggestions, hasItems("OtherPackagePublic"));
        assertThat(suggestions, not(hasItems("OtherPackagePrivate")));

        // for (var item : items) {
        //     if (item.label.equals("OtherPackagePublic"))
        //         assertThat("Auto-import OtherPackagePublic", item.additionalTextEdits, not(empty()));
        // }
    }

    @Test
    public void fieldFromStaticInner() {
        var file = "/org/javacs_server/example/AutocompleteOuter.java";

        // Initializer of static inner class
        var suggestions = insertText(file, 12, 14);

        assertThat(suggestions, hasItems("testMethodStatic", "testFieldStatic"));
        // TODO this is not visible
        // assertThat(suggestions, not(hasItems("testMethods", "testFields")));
    }

    @Test
    public void fieldFromInner() {
        var file = "/org/javacs_server/example/AutocompleteOuter.java";

        // Initializer of inner class
        var suggestions = insertText(file, 18, 14);

        assertThat(suggestions, hasItems("testMethodStatic", "testFieldStatic"));
        assertThat(suggestions, hasItems("testMethods", "testFields"));
    }

    @Test
    public void classDotClassFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteInners.java";

        // AutocompleteInners.I
        var suggestions = insertText(file, 5, 29);

        assertThat("suggests qualified inner class declaration", suggestions, hasItem("InnerClass"));
        assertThat("suggests qualified inner enum declaration", suggestions, hasItem("InnerEnum"));
    }

    @Test
    public void innerClassFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteInners.java";

        // I
        var suggestions = insertText(file, 6, 10);

        assertThat("suggests unqualified inner class declaration", suggestions, hasItem("InnerClass"));
        assertThat("suggests unqualified inner enum declaration", suggestions, hasItem("InnerEnum"));
    }

    @Test
    public void newClassDotInnerClassFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteInners.java";

        // new AutocompleteInners.I
        var suggestions = insertText(file, 10, 33);

        assertThat("suggests qualified inner class declaration", suggestions, hasItem("InnerClass"));
        // TODO you can't actually make an inner enum
        // assertThat("does not suggest enum", suggestions, not(hasItem("InnerEnum")));
    }

    @Test
    public void newInnerClassFromMethod() {
        var file = "/org/javacs_server/example/AutocompleteInners.java";

        // new Inner?
        var suggestions = insertText(file, 11, 18);

        assertThat("suggests unqualified inner class declaration", suggestions, hasItem("InnerClass"));
        // TODO you can't actually make an inner enum
        // assertThat("does not suggest enum", suggestions, not(hasItem("InnerEnum")));
    }

    @Test
    public void innerEnum() {
        var file = "/org/javacs_server/example/AutocompleteInners.java";
        var suggestions = insertText(file, 15, 40);

        assertThat("suggests enum constants", suggestions, hasItems("Foo"));
    }

    @Test
    public void enumConstantFromSourcePath() {
        var file = "/org/javacs_server/example/AutocompleteCase.java";
        var suggestions = insertText(file, 6, 18);

        assertThat("suggests enum options", suggestions, containsInAnyOrder("Foo", "Bar"));
    }

    @Test
    public void enumConstantFromClassPath() {
        var file = "/org/javacs_server/example/AutocompleteCaseFromClasspath.java";
        var suggestions = insertText(file, 8, 18);

        assertThat("suggests enum options", suggestions, containsInAnyOrder("FULL", "LONG", "MEDIUM", "SHORT"));
    }

    @Test
    public void staticStarImport() {
        var file = "/org/javacs_server/example/AutocompleteStaticImport.java";
        var suggestions = insertText(file, 9, 15);

        assertThat("suggests star-imported static method", suggestions, hasItems("emptyList"));
    }

    @Test
    public void staticImport() {
        var file = "/org/javacs_server/example/AutocompleteStaticImport.java";
        var suggestions = insertText(file, 10, 10);

        assertThat("suggests star-imported static field", suggestions, hasItems("BC"));
    }

    @Test
    public void staticImportSourcePath() {
        var file = "/org/javacs_server/example/AutocompleteStaticImport.java";
        var suggestions = insertText(file, 11, 10);

        assertThat(
                "suggests star-imported public static field from source path",
                suggestions,
                hasItems("publicStaticFinal"));
        assertThat(
                "suggests star-imported package-private static field from source path",
                suggestions,
                hasItems("packagePrivateStaticFinal"));
    }

    @Test
    public void withinConstructor() {
        var file = "/org/javacs_server/example/AutocompleteContext.java";
        var suggestions = insertText(file, 8, 38);

        assertThat("suggests local variable", suggestions, hasItems("length"));
    }

    @Test
    @Ignore
    public void onlySuggestOnce() {
        var file = "/org/javacs_server/example/AutocompleteOnce.java";
        var suggestions = insertCount(file, 5, 18);

        assertThat("suggests Signatures", suggestions, hasKey("Signatures"));
        assertThat("suggests Signatures only once", suggestions, hasEntry("Signatures", 1));
    }

    @Test
    public void overloadedOnSourcePath() {
        var file = "/org/javacs_server/example/OverloadedMethod.java";
        var detail = detail(file, 9, 13);

        assertThat("suggests empty method", detail, hasItem("void overloaded()"));
        assertThat("suggests int method", detail, hasItem("void overloaded(i)"));
        assertThat("suggests string method", detail, hasItem("void overloaded(s)"));
    }

    @Test
    public void overloadedOnClassPath() {
        var file = "/org/javacs_server/example/OverloadedMethod.java";
        var detail = detail(file, 10, 26);

        assertThat("suggests empty method", detail, hasItem("List<E> of()"));
        assertThat("suggests one-arg method", detail, hasItem("List<E> of(e1)"));
        // assertThat("suggests vararg method", detail, hasItem("of(elements)"));
    }

    @Test
    public void packageName() {
        var file = "/org/javacs_server/example/AutocompletePackageName.java";
        var suggestions = insertText(file, 1, 5);

        assertThat(suggestions, hasItem(startsWith("package org.javacs_server.example;")));
    }

    @Test
    public void className() {
        var file = "/org/javacs_server/example/AutocompleteClassName.java";
        var suggestions = insertText(file, 1, 2);

        assertThat(suggestions, hasItem(startsWith("class AutocompleteClassName")));
    }

    @Test
    public void annotationInInnerClass() {
        var file = "/org/javacs_server/example/AnnotationInInnerClass.java";
        var suggestions = insertText(file, 6, 17);

        assertThat(suggestions, hasItem(startsWith("Override")));
    }

    @Test
    public void overrideMethod() {
        var file = "/org/javacs_server/example/AutocompleteOverride.java";
        var suggestions = insertText(file, 8, 15);

        assertThat(suggestions, hasItem(containsString("void superMethod() {")));
    }

    @Test
    public void implementsKeyword() {
        var file = "/org/javacs_server/example/AutocompleteImplements.java";
        var suggestions = insertText(file, 3, 34);

        assertThat(suggestions, hasItem(containsString("implements")));
    }

    @Test
    public void importStaticPackage() {
        var file = "/org/javacs_server/example/AutocompleteImportStatic.java";
        var suggestions = insertText(file, 3, 20);

        assertThat(suggestions, hasItem(containsString("util")));
    }
}
