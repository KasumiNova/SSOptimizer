package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NameTranslatorTest {

    @Test
    void translateNull() {
        assertNull(NameTranslator.translate(null));
    }

    @Test
    void translateCleanName() {
        assertEquals("normalMethod", NameTranslator.translate("normalMethod"));
    }

    @Test
    void translateDot() {
        assertEquals("if$dot$new", NameTranslator.translate("if.new"));
    }

    @Test
    void translateSemicolon() {
        assertEquals("field$semi$", NameTranslator.translate("field;"));
    }

    @Test
    void translateBracket() {
        assertEquals("$arr$Lcom", NameTranslator.translate("[Lcom"));
    }

    @Test
    void translateSlash() {
        assertEquals("com$slash$foo", NameTranslator.translate("com/foo"));
    }

    @Test
    void translateMultipleChars() {
        assertEquals("if$dot$new$semi$", NameTranslator.translate("if.new;"));
    }

    @Test
    void translateMappedClassName() {
        assertEquals("com/fs/graphics/TextureLoader", NameTranslator.translate("com/fs/graphics/TextureLoader"));
    }

    @Test
    void translateMappedMemberName() {
        assertEquals("cacheSize", NameTranslator.translate("a"));
        assertEquals("reloadCache", NameTranslator.translate("b"));
    }
}
