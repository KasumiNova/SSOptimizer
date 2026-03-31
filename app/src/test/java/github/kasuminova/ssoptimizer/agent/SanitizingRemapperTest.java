package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SanitizingRemapperTest {

    @Test
    void legalNameUnchanged() {
        var remapper = new SanitizingRemapper();
        assertEquals("traverse", remapper.sanitize("traverse"));
        assertFalse(remapper.isModified());
    }

    @Test
    void dotReplacedWithToken() {
        var remapper = new SanitizingRemapper();
        assertEquals("if$dot$new", remapper.sanitize("if.new"));
        assertTrue(remapper.isModified());
    }

    @Test
    void semicolonReplacedWithToken() {
        var remapper = new SanitizingRemapper();
        assertEquals("foo$semi$bar", remapper.sanitize("foo;bar"));
        assertTrue(remapper.isModified());
    }

    @Test
    void arrayBracketReplacedWithToken() {
        var remapper = new SanitizingRemapper();
        assertEquals("get$arr$0", remapper.sanitize("get[0"));
        assertTrue(remapper.isModified());
    }

    @Test
    void slashReplacedWithToken() {
        var remapper = new SanitizingRemapper();
        assertEquals("a$slash$b", remapper.sanitize("a/b"));
        assertTrue(remapper.isModified());
    }

    @Test
    void multipleIllegalChars() {
        var remapper = new SanitizingRemapper();
        assertEquals("a$dot$b$semi$c", remapper.sanitize("a.b;c"));
        assertTrue(remapper.isModified());
    }

    @Test
    void initAndClinitUnchanged() {
        var remapper = new SanitizingRemapper();
        assertEquals("<init>", remapper.sanitize("<init>"));
        assertEquals("<clinit>", remapper.sanitize("<clinit>"));
        assertFalse(remapper.isModified());
    }

    @Test
    void resetClearsModifiedFlag() {
        var remapper = new SanitizingRemapper();
        remapper.sanitize("if.new");
        assertTrue(remapper.isModified());
        remapper.reset();
        assertFalse(remapper.isModified());
    }

    @Test
    void mapMethodNameDelegatesToSanitize() {
        var remapper = new SanitizingRemapper();
        assertEquals("if$dot$new", remapper.mapMethodName("com/example/Foo", "if.new", "()V"));
    }

    @Test
    void mapFieldNameDelegatesToSanitize() {
        var remapper = new SanitizingRemapper();
        assertEquals("field$dot$name", remapper.mapFieldName("com/example/Foo", "field.name", "I"));
    }

    @Test
    void mapInvokeDynamicDelegatesToSanitize() {
        var remapper = new SanitizingRemapper();
        assertEquals("do$dot$call", remapper.mapInvokeDynamicMethodName("do.call", "()V"));
    }
}
