package org.javacs_server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

class FileManagerWrapper implements StandardJavaFileManager {
    private final StandardJavaFileManager delegate;

    FileManagerWrapper(StandardJavaFileManager delegate) {
        this.delegate = delegate;
    }

    private boolean notModuleInfo(JavaFileObject file) {
        return file == null || !file.getName().endsWith("module-info.java");
    }

    private boolean notModuleInfo(File file) {
        return file == null || !file.getName().endsWith("module-info.java");
    }

    private boolean notModuleInfo(Path file) {
        return file == null || !file.getFileName().endsWith("module-info.java");
    }

    private JavaFileObject skipModuleInfo(JavaFileObject file) {
        if (file == null) return null;
        if (file.getName().endsWith("module-info.java")) return null;
        else return file;
    }

    private FileObject skipModuleInfo(FileObject file) {
        if (file == null) return null;
        if (file.getName().endsWith("module-info.java")) return null;
        else return file;
    }

    private <T> Iterable<T> filter(Iterable<T> in, Predicate<T> f) {
        return StreamSupport.stream(in.spliterator(), false).filter(f)::iterator;
    }

    private Iterable<? extends JavaFileObject> removeModuleInfo(Iterable<? extends JavaFileObject> in) {
        return filter(in, this::notModuleInfo);
    }

    private Iterable<JavaFileObject> removeModuleInfoInvariant(Iterable<JavaFileObject> in) {
        return filter(in, this::notModuleInfo);
    }

    private Iterable<? extends File> removeModuleInfoFile(Iterable<? extends File> in) {
        return filter(in, this::notModuleInfo);
    }

    private Iterable<? extends Path> removeModuleInfoPath(Iterable<? extends Path> in) {
        return filter(in, this::notModuleInfo);
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return delegate.isSameFile(a, b);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        return removeModuleInfo(delegate.getJavaFileObjectsFromFiles(files));
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(Iterable<? extends Path> paths) {
        return removeModuleInfo(delegate.getJavaFileObjectsFromPaths(paths));
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return removeModuleInfo(delegate.getJavaFileObjects(files));
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(Path... paths) {
        return removeModuleInfo(delegate.getJavaFileObjects(paths));
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        return removeModuleInfo(delegate.getJavaFileObjectsFromStrings(names));
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return removeModuleInfo(delegate.getJavaFileObjects(names));
    }

    @Override
    public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        delegate.setLocation(location, files);
    }

    @Override
    public void setLocationFromPaths(Location location, Collection<? extends Path> paths) throws IOException {
        delegate.setLocationFromPaths(location, paths);
    }

    @Override
    public void setLocationForModule(Location location, String moduleName, Collection<? extends Path> paths)
            throws IOException {
        delegate.setLocationForModule(location, moduleName, paths);
    }

    @Override
    public Iterable<? extends File> getLocation(Location location) {
        return removeModuleInfoFile(delegate.getLocation(location));
    }

    @Override
    public Iterable<? extends Path> getLocationAsPaths(Location location) {
        return removeModuleInfoPath(delegate.getLocationAsPaths(location));
    }

    @Override
    public Path asPath(FileObject file) {
        return delegate.asPath(file);
    }

    @Override
    public void setPathFactory(PathFactory f) {
        delegate.setPathFactory(f);
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
        return delegate.getClassLoader(location);
    }

    // Cache calls to list(...)
    static class Key {
        final Location location;
        final String packageName;
        final JavaFileObject.Kind kind;
        final boolean recurse;

        Key(Location location, String packageName, JavaFileObject.Kind kind, boolean recurse) {
            this.location = location;
            this.packageName = packageName;
            this.kind = kind;
            this.recurse = recurse;
        }

        @Override
        public boolean equals(Object candidate) {
            if (!(candidate instanceof Key)) return false;
            var that = (Key) candidate;

            return Objects.equals(this.location, that.location)
                    && Objects.equals(this.packageName, that.packageName)
                    && Objects.equals(this.kind, that.kind)
                    && Objects.equals(this.recurse, that.recurse);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, packageName, kind, recurse);
        }
    }

    // Store previous calls to list, because listing directories and .jar files is expensive
    private Map<Key, List<JavaFileObject>> cache = new HashMap<>();

    private List<JavaFileObject> loadCache(Key key) {
        try {
            var list = new ArrayList<JavaFileObject>();
            var it =
                    removeModuleInfoInvariant(
                            delegate.list(key.location, key.packageName, Collections.singleton(key.kind), key.recurse));
            for (var file : it) list.add(file);
            return list;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        // If search source path, skip cache
        // TODO does this actually do anything?
        if (location == StandardLocation.SOURCE_PATH
                || location == StandardLocation.MODULE_SOURCE_PATH
                || location == StandardLocation.SOURCE_OUTPUT)
            return removeModuleInfoInvariant(delegate.list(location, packageName, kinds, recurse));

        // Search for each kind separately to improve cacheability
        var result = new ArrayList<JavaFileObject>();
        for (var kind : kinds) {
            var list = cache.computeIfAbsent(new Key(location, packageName, kind, recurse), this::loadCache);
            result.addAll(list);
        }
        return result;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return delegate.inferBinaryName(location, file);
    }

    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return delegate.handleOption(current, remaining);
    }

    @Override
    public boolean hasLocation(Location location) {
        return delegate.hasLocation(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
            throws IOException {
        return skipModuleInfo(delegate.getJavaFileForInput(location, className, kind));
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        return skipModuleInfo(delegate.getJavaFileForOutput(location, className, kind, sibling));
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return skipModuleInfo(delegate.getFileForInput(location, packageName, relativeName));
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling)
            throws IOException {
        return skipModuleInfo(delegate.getFileForOutput(location, packageName, relativeName, sibling));
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        return delegate.getLocationForModule(location, moduleName);
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject fo) throws IOException {
        return delegate.getLocationForModule(location, fo);
    }

    @Override
    public <S> ServiceLoader<S> getServiceLoader(Location location, Class<S> service) throws IOException {
        return delegate.getServiceLoader(location, service);
    }

    @Override
    public String inferModuleName(Location location) throws IOException {
        return delegate.inferModuleName(location);
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        return delegate.listLocationsForModules(location);
    }

    @Override
    public boolean contains(Location location, FileObject fo) throws IOException {
        if (fo.getName().endsWith("module-info.java")) return false;

        return delegate.contains(location, fo);
    }

    @Override
    public int isSupportedOption(String option) {
        return delegate.isSupportedOption(option);
    }
}
