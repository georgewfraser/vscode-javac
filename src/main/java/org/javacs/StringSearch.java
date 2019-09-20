package org.javacs;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Translated from https://golang.org/src/strings/search.go

// Search efficiently finds strings in a source text. It's implemented
// using the Boyer-Moore string search algorithm:
// https://en.wikipedia.org/wiki/Boyer-Moore_string_search_algorithm
// https://www.cs.utexas.edu/~moore/publications/fstrpos.pdf (note: this aged
// document uses 1-based indexing)
class StringSearch {
    // pattern is the string that we are searching for in the text.
    private final byte[] pattern;

    // badCharSkip[b] contains the distance between the last byte of pattern
    // and the rightmost occurrence of b in pattern. If b is not in pattern,
    // badCharSkip[b] is len(pattern).
    //
    // Whenever a mismatch is found with byte b in the text, we can safely
    // shift the matching frame at least badCharSkip[b] until the next time
    // the matching char could be in alignment.
    // TODO 256 is not coloring
    private final int[] badCharSkip = new int[256];

    // goodSuffixSkip[i] defines how far we can shift the matching frame given
    // that the suffix pattern[i+1:] matches, but the byte pattern[i] does
    // not. There are two cases to consider:
    //
    // 1. The matched suffix occurs elsewhere in pattern (with a different
    // byte preceding it that we might possibly match). In this case, we can
    // shift the matching frame to align with the next suffix chunk. For
    // example, the pattern "mississi" has the suffix "issi" next occurring
    // (in right-to-left order) at index 1, so goodSuffixSkip[3] ==
    // shift+len(suffix) == 3+4 == 7.
    //
    // 2. If the matched suffix does not occur elsewhere in pattern, then the
    // matching frame may share part of its prefix with the end of the
    // matching suffix. In this case, goodSuffixSkip[i] will contain how far
    // to shift the frame to align this portion of the prefix to the
    // suffix. For example, in the pattern "abcxxxabc", when the first
    // mismatch from the back is found to be in position 3, the matching
    // suffix "xxabc" is not found elsewhere in the pattern. However, its
    // rightmost "abc" (at position 6) is a prefix of the whole pattern, so
    // goodSuffixSkip[3] == shift+len(suffix) == 6+5 == 11.
    private final int[] goodSuffixSkip;

    StringSearch(String pattern) {
        this(pattern.getBytes());
    }

    StringSearch(byte[] pattern) {
        this.pattern = pattern;
        this.goodSuffixSkip = new int[pattern.length];

        // last is the index of the last character in the pattern.
        var last = pattern.length - 1;

        // Build bad character table.
        // Bytes not in the pattern can skip one pattern's length.
        for (var i = 0; i < badCharSkip.length; i++) {
            badCharSkip[i] = pattern.length;
        }
        // The loop condition is < instead of <= so that the last byte does not
        // have a zero distance to itself. Finding this byte out of place implies
        // that it is not in the last position.
        for (var i = 0; i < last; i++) {
            badCharSkip[pattern[i] + 128] = last - i;
        }

        // Build good suffix table.
        // First pass: set each value to the next index which starts a prefix of
        // pattern.
        var lastPrefix = last;
        for (var i = last; i >= 0; i--) {
            if (hasPrefix(pattern, new Slice(pattern, i + 1))) lastPrefix = i + 1;
            // lastPrefix is the shift, and (last-i) is len(suffix).
            goodSuffixSkip[i] = lastPrefix + last - i;
        }
        // Second pass: find repeats of pattern's suffix starting from the front.
        for (var i = 0; i < last; i++) {
            var lenSuffix = longestCommonSuffix(pattern, new Slice(pattern, 1, i + 1));
            if (pattern[i - lenSuffix] != pattern[last - lenSuffix]) {
                // (last-i) is the shift, and lenSuffix is len(suffix).
                goodSuffixSkip[last - lenSuffix] = lenSuffix + last - i;
            }
        }
    }

    int next(String text) {
        return next(text.getBytes());
    }

    int next(byte[] text) {
        return next(ByteBuffer.wrap(text));
    }

    int next(ByteBuffer text) {
        return next(text, 0);
    }

    int next(ByteBuffer text, int startingAfter) {
        var i = startingAfter + pattern.length - 1;
        while (i < text.limit()) {
            // Compare backwards from the end until the first unmatching character.
            var j = pattern.length - 1;
            while (j >= 0 && text.get(i) == pattern[j]) {
                i--;
                j--;
            }
            if (j < 0) {
                return i + 1; // match
            }
            i += Math.max(badCharSkip[text.get(i) + 128], goodSuffixSkip[j]);
        }
        return -1;
    }

    boolean isWordChar(byte b) {
        char c = (char) (b + 128);
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '$' || c == '_';
    }

    boolean startsWord(ByteBuffer text, int offset) {
        if (offset == 0) return true;
        return !isWordChar(text.get(offset - 1));
    }

    boolean endsWord(ByteBuffer text, int offset) {
        if (offset + 1 >= text.limit()) return true;
        return !isWordChar(text.get(offset + 1));
    }

    boolean isWord(ByteBuffer text, int offset) {
        return startsWord(text, offset) && endsWord(text, offset + pattern.length - 1);
    }

    int nextWord(String text) {
        return nextWord(text.getBytes());
    }

    int nextWord(byte[] text) {
        return nextWord(ByteBuffer.wrap(text));
    }

    int nextWord(ByteBuffer text) {
        var i = 0;
        while (true) {
            i = next(text, i);
            if (i == -1) return -1;
            if (isWord(text, i)) return i;
            i++;
        }
    }

    private boolean hasPrefix(byte[] s, Slice prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if (s[i] != prefix.get(i)) return false;
        }
        return true;
    }

    private int longestCommonSuffix(byte[] a, Slice b) {
        int i = 0;
        for (; i < a.length && i < b.length(); i++) {
            if (a[a.length - 1 - i] != b.get(b.length() - 1 - i)) {
                break;
            }
        }
        return i;
    }

    private static class Slice {
        private final byte[] target;
        private int from, until;

        int length() {
            return until - from;
        }

        byte get(int i) {
            return target[from + i];
        }

        public Slice(byte[] target, int from) {
            this(target, from, target.length);
        }

        public Slice(byte[] target, int from, int until) {
            this.target = target;
            this.from = from;
            this.until = until;
        }
    }

    private static final ByteBuffer SEARCH_BUFFER = ByteBuffer.allocateDirect(1 * 1024 * 1024);

    // TODO cache the progress made by searching shorter queries
    static boolean containsWordMatching(Path java, String query) {
        if (FileStore.activeDocuments().contains(java.toUri())) {
            var text = FileStore.contents(java);
            return matchesTitleCase(text, query);
        }
        try (var channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            var limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            var chars = Charset.forName("UTF-8").decode(SEARCH_BUFFER);
            return matchesTitleCase(chars, query);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean containsWord(Path java, String query) {
        var search = new StringSearch(query);
        if (FileStore.activeDocuments().contains(java.toUri())) {
            var text = FileStore.contents(java).getBytes();
            return search.nextWord(text) != -1;
        }
        try (var channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            var limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            return search.nextWord(SEARCH_BUFFER) != -1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean containsString(Path java, String query) {
        var search = new StringSearch(query);
        if (FileStore.activeDocuments().contains(java.toUri())) {
            var text = FileStore.contents(java).getBytes();
            return search.next(text) != -1;
        }
        try (var channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            var limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            return search.next(SEARCH_BUFFER) != -1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check if `candidate` contains all the characters of `find`, in-order, case-insensitive. Matches can be
     * discontinuous if the letters of `find` match the first letters of words in `candidate` For example, fb matches
     * FooBar, but it doesn't match Foobar (exposed for testing)
     */
    static boolean matchesTitleCase(CharSequence candidate, String find) {
        Objects.requireNonNull(candidate, "candidate is null");
        int i = 0;

        tokenLoop:
        while (i < candidate.length()) {
            i = startOfToken(candidate, i);

            for (char f : find.toCharArray()) {
                // If we have reached the end of candidate without matching all of find, fail
                if (i >= candidate.length()) return false;
                // If the next character in candidate matches, advance i
                else if (Character.toLowerCase(f) == Character.toLowerCase(candidate.charAt(i))) i++;
                else {
                    // Find the start of the next word
                    while (i < candidate.length()) {
                        char c = candidate.charAt(i);
                        // If the next character is not a word, try again with the next token
                        if (!isWordChar(c)) continue tokenLoop;
                        // TODO match things like fb ~ foo_bar
                        boolean isStartOfWord = Character.isUpperCase(c);
                        boolean isMatch = Character.toLowerCase(f) == Character.toLowerCase(c);
                        if (isStartOfWord && isMatch) {
                            i++;
                            break;
                        } else i++;
                    }
                    if (i >= candidate.length()) return false;
                }
            }
            // All of find was matched!
            return true;
        }
        return false;
    }

    private static int startOfToken(CharSequence candidate, int offset) {
        while (offset < candidate.length()) {
            char c = candidate.charAt(offset);
            if (isWordChar(c)) break;
            offset++;
        }
        return offset;
    }

    private static boolean isWordChar(char c) {
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '$';
    }

    static Cache<String, Boolean> cacheContainsClass = new Cache<>();

    static boolean containsClass(Path file, String simpleName) {
        if (cacheContainsClass.needs(file, simpleName)) {
            cacheContainsClass.load(file, simpleName, containsString(file, "class " + simpleName));
            // TODO verify this by actually parsing the file
        }
        return cacheContainsClass.get(file, simpleName);
    }

    static boolean containsImport(Path file, String toPackage, String toClass) {
        if (toPackage.isEmpty()) return true;
        var samePackage = Pattern.compile("^package +" + toPackage + ";");
        var importClass = Pattern.compile("^import +" + toPackage + "\\." + toClass + ";");
        var importStar = Pattern.compile("^import +" + toPackage + "\\.\\*;");
        var importStatic = Pattern.compile("^import +static +" + toPackage + "\\." + toClass);
        var startOfClass = Pattern.compile("^[\\w ]*class +\\w+");
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (startOfClass.matcher(line).find()) return false;
                if (samePackage.matcher(line).find()) return true;
                if (importClass.matcher(line).find()) return true;
                if (importStar.matcher(line).find()) return true;
                if (importStatic.matcher(line).find()) return true;
                if (importClass.matcher(line).find()) return true;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    /** Find all already-imported symbols in all .java files in workspace */
    static ExistingImports existingImports(Collection<Path> allJavaFiles) {
        var classes = new HashSet<String>();
        var packages = new HashSet<String>();
        var importClass = Pattern.compile("^import +(([\\w\\.]+)\\.\\w+);");
        var importStar = Pattern.compile("^import +([\\w\\.]+)\\.\\*;");
        var importSimple = Pattern.compile("^import +(\\w+);");
        for (var path : allJavaFiles) {
            try (var lines = FileStore.lines(path)) {
                for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                    // If we reach a class declaration, stop looking for imports
                    // TODO This could be a little more specific
                    if (line.contains("class")) break;
                    // import foo.bar.Doh;
                    var matchesClass = importClass.matcher(line);
                    if (matchesClass.matches()) {
                        String className = matchesClass.group(1), packageName = matchesClass.group(2);
                        packages.add(packageName);
                        classes.add(className);
                    }
                    // import foo.bar.*
                    var matchesStar = importStar.matcher(line);
                    if (matchesStar.matches()) {
                        var packageName = matchesStar.group(1);
                        packages.add(packageName);
                    }
                    // import Doh
                    var matchesSimple = importSimple.matcher(line);
                    if (matchesSimple.matches()) {
                        var className = matchesSimple.group(1);
                        classes.add(className);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return new ExistingImports(classes, packages);
    }

    private static Optional<String> resolveSymbol(String unresolved, ExistingImports imports, Set<String> classPath) {
        // Try to disambiguate by looking for exact matches
        // For example, Foo is exactly matched by `import com.bar.Foo`
        // Foo is *not* exactly matched by `import com.bar.*`
        var candidates = imports.classes.stream().filter(c -> c.endsWith("." + unresolved)).collect(Collectors.toSet());
        if (candidates.size() > 1) {
            LOG.warning(
                    String.format("%s is ambiguous between previously imported candidates %s", unresolved, candidates));
            return Optional.empty();
        } else if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }

        // Try to disambiguate by looking at package names
        // Both normal imports like `import com.bar.Foo`, and star-imports like `import com.bar.*`,
        // are used to generate package names
        candidates =
                classPath
                        .stream()
                        .filter(c -> lastName(c).equals(unresolved))
                        .filter(c -> imports.packages.contains(mostName(c)))
                        .collect(Collectors.toSet());
        if (candidates.size() > 1) {
            LOG.warning(String.format("%s is ambiguous between package-based candidates %s", unresolved, candidates));
            return Optional.empty();
        } else if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }

        // If there is only one class on the classpath with this name, use it
        candidates = classPath.stream().filter(c -> lastName(c).equals(unresolved)).collect(Collectors.toSet());

        if (candidates.size() > 1) {
            LOG.warning(String.format("%s is ambiguous between classpath candidates %s", unresolved, candidates));
        } else if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        } else {
            LOG.warning(unresolved + " does not appear on the classpath");
        }

        // Try to import from java stdlib
        Comparator<String> order =
                Comparator.comparing(
                        c -> {
                            if (c.startsWith("java.lang")) return 1;
                            else if (c.startsWith("java.util")) return 2;
                            else if (c.startsWith("java.io")) return 3;
                            else return 4;
                        });
        return candidates.stream().filter(c -> c.startsWith("java.")).sorted(order).findFirst();
    }

    static Map<String, String> resolveSymbols(
            Set<String> unresolvedSymbols, ExistingImports imports, Set<String> classPath) {
        var result = new HashMap<String, String>();
        for (var s : unresolvedSymbols) {
            resolveSymbol(s, imports, classPath).ifPresent(resolved -> result.put(s, resolved));
        }
        return result;
    }

    // TODO this doesn't work for inner classes, eliminate
    static String mostName(String name) {
        var lastDot = name.lastIndexOf('.');
        return lastDot == -1 ? "" : name.substring(0, lastDot);
    }

    // TODO this doesn't work for inner classes, eliminate
    static String lastName(String name) {
        int i = name.lastIndexOf('.');
        if (i == -1) return name;
        else return name.substring(i + 1);
    }

    static String fileName(URI uri) {
        var parts = uri.toString().split("/");
        if (parts.length == 0) return "";
        return parts[parts.length - 1];
    }

    static String packageName(Path file) {
        var packagePattern = Pattern.compile("^package +(.*);");
        var startOfClass = Pattern.compile("^[\\w ]*class +\\w+");
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (startOfClass.matcher(line).find()) return "";
                var matchPackage = packagePattern.matcher(line);
                if (matchPackage.matches()) {
                    var id = matchPackage.group(1);
                    return id;
                }
            }
        } catch (MalformedInputException e) {
            LOG.warning("Malformed input in file " + file.toString() + ": " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // TODO fall back on parsing file
        return "";
    }

    static boolean matchesPartialName(CharSequence candidate, CharSequence partialName) {
        if (candidate.length() < partialName.length()) return false;
        for (int i = 0; i < partialName.length(); i++) {
            if (candidate.charAt(i) != partialName.charAt(i)) return false;
        }
        return true;
    }

    private static final Logger LOG = Logger.getLogger("main");
}

class ExistingImports {
    /** Fully-qualified names of classes that have been imported on the source path */
    final Set<String> classes;
    /** Package names from star-imports like `import java.util.*` */
    final Set<String> packages;

    ExistingImports(Set<String> classes, Set<String> packages) {
        this.classes = classes;
        this.packages = packages;
    }
}
