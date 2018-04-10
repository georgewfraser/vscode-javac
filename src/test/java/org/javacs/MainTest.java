package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public class MainTest {
    @Test
    public void checkJavacClassLoader()
            throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
                    InvocationTargetException {
        assertThat(Main.checkJavacClassLoader(), not(instanceOf(ChildFirstClassLoader.class)));

        ClassLoader langTools = LangTools.createLangToolsClassLoader();
        Class<?> main = langTools.loadClass("org.javacs.Main");
        assertThat(
                "Main was loaded by ChildFirstClassLoader",
                main.getClassLoader(),
                instanceOf(ChildFirstClassLoader.class));
        Method checkJavacClassLoader = main.getMethod("checkJavacClassLoader");
        assertThat(
                "JavacTool was loaded by ChildFirstClassLoader",
                checkJavacClassLoader.invoke(null),
                instanceOf(ChildFirstClassLoader.class));
    }
}
