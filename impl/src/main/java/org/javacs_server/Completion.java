package org.javacs_server;

import javax.lang.model.element.Element;

/**
 * Union of the different types of completion provided by JavaCompilerService. Only one of the members will be non-null.
 */
public class Completion {
    public final Element element;
    public final PackagePart packagePart;
    public final String keyword;
    public final ClassName className;
    public final Snippet snippet; // TODO separate label and insertText

    private Completion(Element element, PackagePart packagePart, String keyword, ClassName className, Snippet snippet) {
        this.element = element;
        this.packagePart = packagePart;
        this.keyword = keyword;
        this.className = className;
        this.snippet = snippet;
    }

    public static Completion ofElement(Element element) {
        return new Completion(element, null, null, null, null);
    }

    public static Completion ofPackagePart(String fullName, String name) {
        return new Completion(null, new PackagePart(fullName, name), null, null, null);
    }

    public static Completion ofKeyword(String keyword) {
        return new Completion(null, null, keyword, null, null);
    }

    public static Completion ofClassName(String className, boolean isImported) {
        return new Completion(null, null, null, new ClassName(className, isImported), null);
    }

    public static Completion ofSnippet(String label, String snippet) {
        return new Completion(null, null, null, null, new Snippet(label, snippet));
    }

    public static class ClassName {
        public final String name;
        public final boolean isImported;

        public ClassName(String name, boolean isImported) {
            this.name = name;
            this.isImported = isImported;
        }
    }

    public static class PackagePart {
        public final String fullName, name;

        public PackagePart(String fullName, String name) {
            this.fullName = fullName;
            this.name = name;
        }
    }

    public static class Snippet {
        public final String label, snippet;

        public Snippet(String label, String snippet) {
            this.label = label;
            this.snippet = snippet;
        }
    }
}
